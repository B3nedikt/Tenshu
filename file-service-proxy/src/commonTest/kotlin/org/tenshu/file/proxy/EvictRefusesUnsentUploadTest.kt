package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.Data as FileDataProperty
import org.tenshu.core.file.File
import org.tenshu.core.file.system.ContentPinnedError
import org.tenshu.core.file.system.EvictFileBytes
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `EvictFileBytes` refuses the bytes of an upload that has not drained — the only copy — with
 * [ContentPinnedError].
 */
class EvictRefusesUnsentUploadTest {

    private suspend fun upload(proxy: org.tenshu.core.common.TestProxy, name: String) {
        proxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/".toTString(proxy.tContext),
                UploadFile.Params.FILE_NAME to name.toTString(proxy.tContext),
                UploadFile.Params.DATA to byteArrayOf(1, 2, 3).toTByteArray(proxy.tContext),
            ),
        )
    }

    private suspend fun evict(proxy: org.tenshu.core.common.TestProxy, path: String) =
        proxy.executionContext.executeAsync<Data>(
            operationId = EvictFileBytes.ID,
            caller = null,
            params = mapOf(EvictFileBytes.Params.PATH to path.toTString(proxy.tContext)),
        )

    @Test
    fun evictingUnpinnedBytesStillWorks() = runTest {
        val proxy = createFileProxy(backgroundScope)
        upload(proxy, "free.bin")

        evict(proxy, "/free.bin")

        assertNull(
            proxy.fileDatabase.readBytes("/free.bin"),
            "with nothing owed, Remove download behaves exactly as before",
        )
    }

    /** Answers only about what the test put in [queuedFor]; everything else is unowed. */
    private class QueuedUploadEmitter : FileContentSyncEmitter {
        val queuedFor = mutableSetOf<Pair<String, String>>()

        override suspend fun emitFileContent(targetEntityId: DataId, propertyId: String, filePath: String) = true

        override suspend fun shareContentToRealm(contentId: String, targetRealmOwnerId: String, targetPropertyId: String) = true

        override suspend fun hasQueuedContentFor(targetEntityId: DataId, propertyId: String): Boolean =
            (targetEntityId as DataId.StringDataId).keyValue.get() to propertyId in queuedFor
    }

    @Test
    fun evictingAFileWhoseUploadHasNotDrainedIsRefused() = runTest {
        // ⚠⚠ The trigger is a parked UPLOAD — Wi-Fi-only, a 507, or simply offline — and the relay has
        // never seen those bytes, so eviction destroys the only copy in existence.
        //
        // The automatic sweep (OfflineKeeperImplementation) consults the upload journal too. Without
        // the check here, *Remove download* — and flipping a folder to ONLINE_ONLY, which evicts each
        // child — would walk straight past it.
        val emitter = QueuedUploadEmitter()
        val proxy = createFileProxy(
            backgroundScope,
            syncBlockEmitterHolder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter },
        )
        upload(proxy, "unsent.bin")
        upload(proxy, "sent.bin")
        emitter.queuedFor += "/unsent.bin" to File.FileDataProperty.id

        // ⚠ Anti-vacuity FIRST: a guard that refused everything would pass the assertions below
        // while breaking the feature. The file with nothing owed must still evict.
        evict(proxy, "/sent.bin")
        assertNull(
            proxy.fileDatabase.readBytes("/sent.bin"),
            "a file the relay already has still evicts — the guard is not a blanket refusal",
        )

        val result = runCatching { evict(proxy, "/unsent.bin") }

        assertTrue(result.isFailure, "the eviction is refused, not silently skipped")
        assertTrue(
            result.exceptionOrNull()?.message?.contains(ContentPinnedError.ID) == true,
            "and refused with ContentPinnedError: ${result.exceptionOrNull()?.message}",
        )
        assertNotNull(
            proxy.fileDatabase.readBytes("/unsent.bin"),
            "and the bytes stay — nothing else has a copy",
        )
    }
}
