package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import org.tenshu.core.collection.list.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Two-client folder/file sync regression test. Pairs a host + joiner via the
 * existing pairing flow, then has the host create a folder and upload a file
 * inside the realm root. Asserts the joiner observes both entries in its own
 * root-folder `FILES` array after the next polling cycle drains.
 *
 * Reproduces the production bug "folder upload on host doesn't appear on the
 * second device" — the existing `PairingSystemTest` only exercises Device
 * entity sync; file-service entity sync (Folder + File composites + their
 * Folder.FILES list-field merge via `FolderMerger`) had no two-client SystemTest
 * coverage until this one.
 *
 * `runBlocking` (not `runTest`) for the same reason as the other SystemTests:
 * `ProxyGateway.call` deadlocks against the test dispatcher.
 */
class FolderSyncSystemTest {

    @Test fun hostFolderAndFile_propagateToJoiner_viaRootFilesArray() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)

            // ---- Pair host + joiner. Same shape as PairingSystemTest. ----
            host.bootstrapAsHost(deviceName = "Host Tablet")
            host.tenshuClient.syncService.startPairing()
            val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)

            joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner Phone",
                otherDevicePublicKey = parsed.hostPairingPublicKey
            )
            val request = host.tenshuClient.syncService.getPendingPairingRequests()
                .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
            host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
            system.advanceUntil(timeout = 15.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            // ---- Host creates a folder + uploads a file inside the realm root. ----
            val folderPath = "/SharedFolder"
            val fileName = "shared.txt"
            val filePath = "/$fileName"
            val fileBytes = byteArrayOf(7, 8, 9)

            val createResult = host.tenshuClient.fileService.defaultFileSystem
                .createFolder(folderPath)
            assertTrue(
                createResult is TResult.Success,
                "Host createFolder should succeed; got $createResult"
            )

            val uploadResult = host.tenshuClient.fileService.defaultFileSystem
                .uploadFile(parentPath = "/", fileName = fileName, data = fileBytes)
            assertTrue(
                uploadResult is TResult.Success,
                "Host uploadFile should succeed; got $uploadResult"
            )

            // Sanity: host's own DB should hold both entries in the root files
            // array. Same direct read pattern as `CreateFolderOperationTest` and
            // `UploadFileOperationTest`. If this fails the bug is in the host's
            // own write path, not the sync layer.
            assertTrue(
                host.executionContext.rootFilePaths().containsAll(setOf(folderPath, filePath)),
                "Host should see its own folder + file locally; got ${host.executionContext.rootFilePaths()}"
            )

            // ---- Wait for sync to drain to the joiner. ----
            // pollingIntervalMs=100 in systemTestPlatformModule; same wall-clock
            // budget as the device-propagation tests.
            system.advanceUntil(timeout = 15.seconds) {
                joiner.executionContext.rootFilePaths().containsAll(setOf(folderPath, filePath))
            }

            // ---- Joiner now sees both entries in its root files array. ----
            val joinerPaths = joiner.executionContext.rootFilePaths()
            assertTrue(
                folderPath in joinerPaths,
                "Joiner should see host-created folder $folderPath; got $joinerPaths"
            )
            assertTrue(
                filePath in joinerPaths,
                "Joiner should see host-uploaded file $filePath; got $joinerPaths"
            )

            // The host's uploaded file is also addressable on the joiner
            // side via its `StringDataId(File.ID, path)` — the same DataId
            // shape both sides derive from the path. Reads back with
            // `asEntity = true` so the field-access path is exercised.
            val fileDataId = DataId.StringDataId.create(type = File.ID, keyValue = filePath)
            val joinerFile = joiner.executionContext.database.getData(fileDataId, asEntity = true)
            assertNotNull(joinerFile, "Joiner must have a File entity for $filePath")
            val readPath = when (joinerFile) {
                is Entity<*> -> joinerFile.getFieldValue<TString>(File.Field.PATH).get()
                else -> (joinerFile as org.tenshu.core.Composite)
                    .getField<TString>(File.Field.PATH).get()
            }
            assertEquals(filePath, readPath)
        } finally {
            system.shutdown()
        }
    }

    /**
     * Read the root folder's `FILES` list as paths. Uses the same direct
     * `getArrayData` shape as `CreateFolderOperationTest` /
     * `UploadFileOperationTest` — bypasses the SDK request chain so the
     * assertion is store-level (what the SyncProcessor actually wrote into
     * the joiner's local DB), not "what the SDK returned over the wire."
     */
    private fun ExecutionContext.rootFilePaths(): Set<String> =
        rootFiles().map { entry ->
            when (entry) {
                is Entity<*> -> entry.getFieldValue<TString>(File.Field.PATH).get()
                else -> (entry as org.tenshu.core.Composite)
                    .getField<TString>(File.Field.PATH).get()
            }
        }.toSet()

    private fun ExecutionContext.rootFiles(): List<org.tenshu.core.Data> {
        val rootId = DataId.StringDataId.create(type = File.ID, keyValue = "/")
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                type = TList.ID,
                parentEntity = rootId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )
        return getArrayData(ref)?.toList() ?: emptyList()
    }
}
