package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
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
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins "folders show up on the other side" with the production-equivalent
 * polling cadence (1s — a compromise between
 * the 100ms of `FolderSyncSystemTest` and the 30s prod default, fast enough
 * to keep the test short but slow enough to actually exercise the scheduler).
 *
 * Two scenarios:
 *  - **Pre-pairing**: host has folders + files BEFORE the joiner pairs.
 *    These blocks are queued from the host's CreateRealm-time bootstrap
 *    onward; the joiner's first pull must pick them up.
 *  - **Post-pairing**: host creates a folder + uploads a file AFTER the
 *    joiner has paired and converged. The joiner's scheduler must pull
 *    the new block and surface the entry.
 *
 * A pass means the system propagates folders/files end-to-end under realistic
 * timing.
 */
class PostPairingFolderSyncSystemTest {

    @Test fun postPairing_hostCreatesFolderAndFile_joinerSeesThem() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            // Slower than 100ms (the existing FolderSyncSystemTest) so the
            // scheduler timing is closer to production. Fast enough that a
            // healthy system still converges inside the test budget.
            val host = system.client(
                deviceId = "host",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 1_000
            )
            val joiner = system.client(
                deviceId = "joiner",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 1_000
            )

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
            system.advanceUntil(timeout = 30.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            // Host creates AFTER pairing — both schedulers running steady state.
            val folderPath = "/SharedFolder"
            val fileName = "shared.txt"
            val filePath = "/$fileName"

            val createResult = host.tenshuClient.fileService.defaultFileSystem
                .createFolder(folderPath)
            assertTrue(
                createResult is TResult.Success,
                "Host createFolder should succeed; got $createResult"
            )

            val uploadResult = host.tenshuClient.fileService.defaultFileSystem
                .uploadFile(parentPath = "/", fileName = fileName, data = byteArrayOf(7, 8, 9))
            assertTrue(
                uploadResult is TResult.Success,
                "Host uploadFile should succeed; got $uploadResult"
            )

            // Wait up to 30s for the joiner to observe both entries. With 1s
            // polling on both sides this is plenty of time for the host to
            // drain + joiner to pull.
            system.advanceUntil(timeout = 30.seconds) {
                joiner.executionContext.rootFilePaths().containsAll(setOf(folderPath, filePath))
            }

            val joinerPaths = joiner.executionContext.rootFilePaths()
            assertTrue(
                folderPath in joinerPaths,
                "Joiner should see host-created folder $folderPath; got $joinerPaths"
            )
            assertTrue(
                filePath in joinerPaths,
                "Joiner should see host-uploaded file $filePath; got $joinerPaths"
            )
        } finally {
            system.shutdown()
        }
    }

    @Test fun prePairing_hostHasFolder_joinerSeesItAfterPairing() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client(
                deviceId = "host",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 1_000
            )

            host.bootstrapAsHost(deviceName = "Host Tablet")

            // Host creates content BEFORE any joiner pairs — this is the
            // "Desktop has been running with files for a while" scenario.
            val folderPath = "/Documents"
            val fileName = "old.txt"
            val filePath = "/$fileName"
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .createFolder(folderPath) is TResult.Success
            )
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = fileName, data = byteArrayOf(1, 2)) is TResult.Success
            )

            // Now pair the joiner. The host's pre-pairing blocks (its own
            // Device + the folder + the file) all sit in the pending queue.
            // ApprovePairingRequest flushes them before the joiner can pull.
            val joiner = system.client(
                deviceId = "joiner",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 1_000
            )

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
            system.advanceUntil(timeout = 30.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey
                )
                status is TResult.Success && status.value?.get() == true
            }

            system.advanceUntil(timeout = 30.seconds) {
                joiner.executionContext.rootFilePaths().containsAll(setOf(folderPath, filePath))
            }

            val joinerPaths = joiner.executionContext.rootFilePaths()
            assertTrue(folderPath in joinerPaths, "Joiner missing $folderPath; got $joinerPaths")
            assertTrue(filePath in joinerPaths, "Joiner missing $filePath; got $joinerPaths")
        } finally {
            system.shutdown()
        }
    }

    private fun ExecutionContext.rootFilePaths(): Set<String> {
        val rootId = DataId.StringDataId.create(type = File.ID, keyValue = "/")
        val ref = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                type = TList.ID,
                parentEntity = rootId,
                fieldRef = Folder.Fields.FILES
            ),
            isEntity = false
        )
        return (getArrayData(ref)?.toList() ?: emptyList()).map { entry ->
            when (entry) {
                is Entity<*> -> entry.getFieldValue<TString>(File.Field.PATH).get()
                else -> (entry as org.tenshu.core.Composite)
                    .getField<TString>(File.Field.PATH).get()
            }
        }.toSet()
    }
}
