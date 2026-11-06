package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.TestProxy
import org.tenshu.core.TenshuException
import org.tenshu.core.common.util.toTString
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.toTByteArray
import org.tenshu.file.test.createFileProxy
import org.tenshu.storage.InsufficientStorageError
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.StorageFigures
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Tier-2 quota gate on an overwrite. A replace used to skip it ("replacing bytes doesn't grow
 * usage"), but it mints a fresh contentId and emits new chunks before the superseded content is
 * reclaimed, so it grows usage by its full size. It is now gated exactly like an upload, and an
 * overwrite with unchanged bytes is a no-op that the gate never sees.
 *
 * ⚠ The first test here that wires a [StorageCapacityHolder] into the file proxy at all: every other
 * upload/replace test runs the null (no-op) gate, which is how the skip survived.
 */
class ReplaceQuotaGateTest {

    private fun holder(usedBytes: Long, limitBytes: Long) = StorageCapacityHolder().apply {
        figures = StorageFigures(hasStorage = true, usedBytes = usedBytes, limitBytes = limitBytes)
    }

    private suspend fun TestProxy.upload(name: String, bytes: ByteArray) =
        executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf<String, Data>(
                UploadFile.Params.PARENT_PATH to "/".toTString(),
                UploadFile.Params.FILE_NAME to name.toTString(),
                UploadFile.Params.DATA to bytes.toTByteArray(tContext),
            ),
        )

    private suspend fun TestProxy.replace(name: String, bytes: ByteArray) =
        executionContext.executeAsync<Data>(
            operationId = ReplaceFile.ID,
            caller = null,
            params = mapOf<String, Data>(
                ReplaceFile.Params.PARENT_PATH to "/".toTString(),
                ReplaceFile.Params.FILE_NAME to name.toTString(),
                ReplaceFile.Params.DATA to bytes.toTByteArray(tContext),
            ),
        )

    private fun TestProxy.contentIdOf(path: String): String? {
        val dataId = org.tenshu.core.entity.DataId.StringDataId.create(context = tContext, type = File.ID, keyValue = path)
        val row = executionContext.database.getData(dataId) ?: return null
        return (executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)?.get()
    }

    @Test
    fun anUploadThatDoesNotFitIsRefused() = runTest {
        val proxy = createFileProxy(storageCapacityHolder = holder(usedBytes = 995L, limitBytes = 1_000L))

        val ex = assertFailsWith<TenshuException> { proxy.upload("big.bin", ByteArray(100) { 7 }) }

        assertTrue(ex.error is InsufficientStorageError, "expected InsufficientStorageError, got ${ex.error}")
        assertEquals(null, proxy.fileDatabase.readBytes("/big.bin")?.get(), "a refused upload must write nothing")
    }

    @Test
    fun aChangedReplaceThatDoesNotFitIsRefused_andTheFileKeepsItsContent() = runTest {
        val gate = holder(usedBytes = 0L, limitBytes = 1_000L)
        val proxy = createFileProxy(storageCapacityHolder = gate)
        val original = ByteArray(50) { 1 }
        proxy.upload("doc.bin", original)
        val contentIdBefore = proxy.contentIdOf("/doc.bin")

        // The realm fills up (another device, or this one's queue) before the overwrite.
        gate.figures = StorageFigures(hasStorage = true, usedBytes = 990L, limitBytes = 1_000L)
        val ex = assertFailsWith<TenshuException> { proxy.replace("doc.bin", ByteArray(60) { 2 }) }

        assertTrue(ex.error is InsufficientStorageError, "a changed overwrite must be gated like an upload, got ${ex.error}")
        assertTrue(original.contentEquals(proxy.fileDatabase.readBytes("/doc.bin")?.get()), "the refused overwrite must leave the old bytes")
        assertEquals(contentIdBefore, proxy.contentIdOf("/doc.bin"), "the refused overwrite must not mint a contentId")
    }

    @Test
    fun anUnchangedReplaceSucceedsOnAFullRealm_andIsANoOp() = runTest {
        val gate = holder(usedBytes = 0L, limitBytes = 1_000L)
        val proxy = createFileProxy(storageCapacityHolder = gate)
        val bytes = ByteArray(50) { 3 }
        proxy.upload("same.bin", bytes)
        val contentIdBefore = proxy.contentIdOf("/same.bin")

        gate.figures = StorageFigures(hasStorage = true, usedBytes = 1_000L, limitBytes = 1_000L)
        gate.relayRejected = true
        proxy.replace("same.bin", bytes.copyOf())

        assertEquals(contentIdBefore, proxy.contentIdOf("/same.bin"), "unchanged bytes must keep the contentId")
    }

    /** Reports [queued] bytes journalled for every file, as a closed metered gate would have left them. */
    private class QueuedEmitter(private val queued: Long) : FileContentSyncEmitter {
        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String): Boolean = false
        override suspend fun queuedContentBytesFor(targetEntityId: DataId, propertyId: String): Long = queued
        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String): Boolean = true
    }

    @Test
    fun aReplaceOfAQueuedFileIsNotChargedForTheVersionItSupersedes() = runTest {
        // The journal keeps one intent per file, last wins, so the queued 60 bytes are replaced by the
        // new 80, not added to them: 80 of a 100-byte quota fits.
        val gate = holder(usedBytes = 0L, limitBytes = 1_000L)
        val proxy = createFileProxy(
            storageCapacityHolder = gate,
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = QueuedEmitter(queued = 60L) },
        )
        proxy.upload("queued.bin", ByteArray(60) { 1 })
        gate.figures = StorageFigures(hasStorage = true, usedBytes = 0L, limitBytes = 100L)
        gate.queuedContentBytes = 60L

        proxy.replace("queued.bin", ByteArray(80) { 2 })

        assertEquals(80, proxy.fileDatabase.readBytes("/queued.bin")?.get()?.size, "the replacement must land")
    }

    @Test
    fun anUploadIsStillChargedForTheWholeQueue() = runTest {
        // Only an overwrite supersedes an intent. A new file joins the queue behind it.
        val gate = holder(usedBytes = 0L, limitBytes = 100L)
        val proxy = createFileProxy(
            storageCapacityHolder = gate,
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = QueuedEmitter(queued = 60L) },
        )
        gate.queuedContentBytes = 60L

        val ex = assertFailsWith<TenshuException> { proxy.upload("new.bin", ByteArray(80) { 3 }) }

        assertTrue(ex.error is InsufficientStorageError, "got ${ex.error}")
    }

    @Test
    fun aChangedReplaceThatFitsMintsNewContent() = runTest {
        val proxy = createFileProxy(
            storageCapacityHolder = holder(usedBytes = 0L, limitBytes = 1_000_000L),
        )
        proxy.upload("edit.bin", ByteArray(50) { 4 })
        val contentIdBefore = proxy.contentIdOf("/edit.bin")

        proxy.replace("edit.bin", ByteArray(50) { 5 })

        val contentIdAfter = proxy.contentIdOf("/edit.bin")
        assertTrue(!contentIdAfter.isNullOrBlank())
        assertNotEquals(contentIdBefore, contentIdAfter, "changed bytes must mint a new contentId")
    }
}
