package org.tenshu.file.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OnDemandResolution
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.ShareFolderIntoRealm
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.test.createFileProxy
import org.tenshu.sync.common.FileContentSyncEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Content-addressing §3.4.1 slice 3-2b: Mode-B shares a file's content by **re-wrapping
 * its per-content key and copying its already-encrypted chunk blobs** from the relay-backed
 * personal content store into the realm — it does **not** read the host's plaintext.
 *
 * The historical bug this file was born to guard against: `emitFileContent` read
 * `FileDatabase` at the personal path and silently no-opped when the bytes weren't there,
 * so a file whose plaintext the host didn't hold synced its NAME to every member while its
 * content never reached the realm — recipients saw the file and could never open it. The
 * 3-2a→3-2b reshape closes it *differently*: the content comes from the relay (where any
 * uploading device put it), so a share needs no local plaintext at all. Sharing therefore
 * no longer triggers an OnDemand plaintext fetch, and an online-only file is shareable.
 */
class ShareFolderOnDemandBytesTest {

    @Test
    fun share_reSharesContentByContentId_withoutFetchingPlaintext() = runTest {
        val emitter = RecordingContentEmitter()
        val holder = SyncBlockEmitterHolder().apply { fileContentEmitter = emitter }
        val resolver = FakeOnDemandResolution()
        val proxy = createFileProxy(
            backgroundScope,
            deviceId = "device-A",
            syncBlockEmitterHolder = holder,
            onDemandResolution = resolver,
        )

        val bytes = ByteArray(64) { it.toByte() }
        proxy.executionContext.executeAsync<Data>(
            operationId = CreateFolder.ID,
            caller = null,
            params = mapOf(CreateFolder.Params.PATH to "/shared".toTString(proxy.tContext)),
        )
        proxy.executionContext.executeAsync<Data>(
            operationId = UploadFile.ID,
            caller = null,
            params = mapOf(
                UploadFile.Params.PARENT_PATH to "/shared".toTString(proxy.tContext),
                UploadFile.Params.FILE_NAME to "a.txt".toTString(proxy.tContext),
                UploadFile.Params.DATA to bytes.toTByteArray(proxy.tContext),
            ),
        )

        // The host no longer holds the plaintext — e.g. sharing from a second device that
        // never opened the file, or after the bytes were evicted. Under 3-2b this is fine:
        // the content is on the relay and is re-shared by contentId, not read from disk.
        proxy.fileDatabase.deleteFile("/shared/a.txt")
        emitter.calls.clear()
        emitter.shareCalls.clear()

        proxy.executionContext.executeAsync<Data>(
            operationId = ShareFolderIntoRealm.ID,
            caller = null,
            params = mapOf(
                ShareFolderIntoRealm.Params.FOLDER_PATH to "/shared".toTString(proxy.tContext),
                ShareFolderIntoRealm.Params.REALM_ID to "realm-1".toTString(proxy.tContext),
            ),
        )

        // No plaintext fetch: sharing does not need the bytes on this device (3-2b reads
        // the encrypted blobs from the relay), so it must NOT OnDemand-resolve File.Data.
        assertTrue(
            resolver.calls.isEmpty(),
            "3-2b must not fetch plaintext to share; got ${resolver.calls}",
        )
        // The old plaintext-reading path is gone.
        assertTrue(emitter.calls.isEmpty(), "no per-file emitFileContent under 3-2b")

        // The personal file's contentId is the re-share handle.
        val personalFile = proxy.executionContext.database.getData(
            DataId.StringDataId.create(proxy.tContext, File.ID, "/shared/a.txt")
        )!!
        val personalContentId =
            (proxy.executionContext.resolveProperty(personalFile, propertyRef(File.ContentId.id)) as TString).get()
        assertTrue(personalContentId.isNotEmpty(), "personal file must have a stamped contentId")

        // Content re-shared cross-realm by that contentId, into the realm's SharedFile slot.
        assertEquals(
            listOf(Triple(personalContentId, "realm-1", SharedFile.DATA_PROPERTY_ID)),
            emitter.shareCalls,
            "the file's content is re-shared by contentId into the realm",
        )
    }

    private class FakeOnDemandResolution : OnDemandResolution {
        val calls = mutableListOf<Pair<DataId, String>>()

        override suspend fun resolve(
            executionContext: ExecutionContext,
            entityId: DataId,
            propertyId: String,
            params: TList<Data>?,
        ): TResult<Data?> {
            calls += entityId to propertyId
            return TResult.Success.create(executionContext.context, null)
        }
    }

    /** Records emitFileContent (should stay empty under 3-2b) + shareContentToRealm calls. */
    private class RecordingContentEmitter : FileContentSyncEmitter {
        val calls = mutableListOf<Triple<String, String, String>>()
        val shareCalls = mutableListOf<Triple<String, String, String>>()

        override suspend fun emitFileContent(
            targetEntityId: DataId,
            propertyId: String,
            filePath: String,
        ): Boolean {
            calls += Triple(
                (targetEntityId as DataId.StringDataId).keyValue.get(),
                propertyId,
                filePath,
            )
            return true
        }

        override suspend fun shareContentToRealm(
            contentId: String,
            targetRealmOwnerId: String,
            targetPropertyId: String,
        ): Boolean {
            shareCalls += Triple(contentId, targetRealmOwnerId, targetPropertyId)
            return true
        }
    }
}
