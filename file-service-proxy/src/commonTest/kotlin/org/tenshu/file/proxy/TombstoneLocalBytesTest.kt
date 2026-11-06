package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * **Does a delete that arrives from another device free this device's disk?**
 *
 * `SyncProcessor.processDeleteBlock` applies an ingested tombstone as exactly one store call —
 * `executionContext.deleteData(dataId, origin = WriteOrigin.SYNC_INGEST)` — so driving that call is
 * driving the peer's whole ingest path, observer fanout included. That is what these tests do; no
 * sync stack is needed to answer the question, and mocking one would only move the answer.
 *
 * The local path (`DeleteFileImplementation`) is tested alongside as the control: it drops bytes
 * explicitly, so if the two disagree the difference is the ingest path's, not the store's.
 */
class TombstoneLocalBytesTest {

    private val bytes = ByteArray(4096) { it.toByte() }

    /** The control: deleting *here* takes the plaintext with it. */
    @Test
    fun aLocalDelete_freesTheBytes() = runTest {
        val proxy = createFileProxy()
        val entity = Entity.create<File>(
            context = proxy.tContext,
            composite = File.create(proxy.tContext, "/photo.jpg"),
        )
        proxy.executionContext.saveData(entity)
        proxy.fileDatabase.writeRawByteArray("/photo.jpg", bytes)
        assertEquals(4096L, proxy.fileDatabase.fileSize("/photo.jpg"))

        proxy.executionContext.executeAsync<Data>(
            operationId = DeleteFile.ID,
            caller = null,
            params = mapOf(DeleteFile.Params.PATH to "/photo.jpg".toTString()),
        )

        assertNull(proxy.fileDatabase.fileSize("/photo.jpg"), "the deleting device frees its disk")
    }

    /**
     * ⚠⚠ The question. A peer applies the tombstone for a **leaf file** whose bytes it had
     * downloaded. `DeletedFolderSubtreeReaper` drops the bytes of the deleted entity's *children*
     * — a leaf has none — and `OrphanedFileSweeper` collects rows, not bytes, so nothing is left
     * that could notice this plaintext.
     */
    @Test
    fun anIngestedTombstoneForALeaf_leavesThePlaintextOnDisk() = runTest {
        val proxy = createFileProxy()
        val entity = Entity.create<File>(
            context = proxy.tContext,
            composite = File.create(proxy.tContext, "/photo.jpg"),
        )
        proxy.executionContext.saveData(entity)
        proxy.fileDatabase.writeRawByteArray("/photo.jpg", bytes)

        val dataId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = File.ID,
            keyValue = "/photo.jpg",
        )
        // Byte-for-byte what SyncProcessor.processDeleteBlock does on the receiving device.
        proxy.executionContext.deleteData(dataId, origin = WriteOrigin.SYNC_INGEST)

        assertNull(
            proxy.executionContext.database.getData(dataId),
            "precondition: the row is gone, so the tombstone did apply",
        )
        assertNotNull(
            proxy.fileDatabase.fileSize("/photo.jpg"),
            "MEASUREMENT: the peer's plaintext outlives the row it belonged to. If this ever " +
                "fails, the leak has been fixed and this test should be inverted.",
        )
    }

    /**
     * The contrast that localises it: a deleted **folder** does take its children's bytes, because
     * the reaper cascades. So the gap is the root of the deleted subtree — which only holds bytes
     * when the root is itself a file, i.e. the ordinary "delete one photo" case above.
     */
    @Test
    fun anIngestedTombstoneForAFolder_doesFreeItsChildrensBytes() = runTest {
        val proxy = createFileProxy()
        proxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/album".toTString()),
        )
        val child = Entity.create<File>(
            context = proxy.tContext,
            composite = File.create(proxy.tContext, "/album/photo.jpg"),
        )
        proxy.executionContext.saveData(child)
        proxy.fileDatabase.writeRawByteArray("/album/photo.jpg", bytes)

        val childId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = File.ID,
            keyValue = "/album/photo.jpg",
        )
        val folderId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = File.ID,
            keyValue = "/album",
        )
        proxy.executionContext.deleteData(folderId, origin = WriteOrigin.SYNC_INGEST)

        // ⚠ Assert the FIXTURE before the subject. `ParentFilesRelationMaintainer` derives
        // membership from the save, and if it had not, the reaper would have seen no children at
        // all — so this test would report "the cascade is broken", a product bug that is not there.
        // The child's ROW going is the proof that the cascade actually traversed a real link.
        assertNull(
            proxy.executionContext.database.getData(childId),
            "precondition: the cascade must have reached the child at all",
        )
        assertNull(
            proxy.fileDatabase.fileSize("/album/photo.jpg"),
            "the reaper cascades into children and takes their bytes",
        )
    }
}
