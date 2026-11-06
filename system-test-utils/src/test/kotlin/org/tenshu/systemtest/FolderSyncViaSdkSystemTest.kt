package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.toList
import org.tenshu.core.equalTo
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
import org.tenshu.core.resolve
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
 * Validates that the SDK read chain — `fileService.defaultFileSystem.root
 * .files.search<File> { path equalTo X }.get()` — surfaces sync'd folders
 * and files on the joiner side. Existing `FolderSyncSystemTest` reads
 * directly via `executionContext.getArrayData(rootRef)`, which proves the
 * underlying DB state but bypasses the request-chain materialiser the
 * Drive app actually uses (see `:drive-shared`/`TenshuFilesRepository.getFiles`).
 *
 * This test pins down whether the SDK chain itself surfaces the propagated
 * entries, in case the production "folders don't show up" symptom is a
 * read-path issue (request-chain materialiser, ProxyGateway, search/select
 * resolution) rather than a sync-layer issue.
 */
class FolderSyncViaSdkSystemTest {

    @Test fun joinerSdkChain_findsHostCreatedFolder() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)

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

            val folderPath = "/SharedFolder"
            val fileName = "shared.txt"
            val filePath = "/$fileName"

            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .createFolder(folderPath) is TResult.Success
            )
            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile(parentPath = "/", fileName = fileName, data = byteArrayOf(1, 2, 3))
                        is TResult.Success
            )

            // SDK read chain — exactly the shape the Drive app uses
            // (TenshuFilesRepository#getFiles for non-root + #downloadFile for files).
            // Wait for BOTH the folder AND the file separately — they sync as
            // independent SyncBlocks and the file's block may arrive after the
            // folder's, especially under realistic scheduler cadences.
            for (path in listOf(folderPath, filePath)) {
                system.advanceUntil(timeout = 15.seconds) {
                    val result = runCatching {
                        joiner.tenshuClient.fileService.defaultFileSystem.root.files
                            .search<File> { this.path equalTo path }
                            .resolve()
                    }.getOrNull()
                    result != null
                }
            }

            val joinerFolder = joiner.tenshuClient.fileService.defaultFileSystem.root.files
                .search<File> { path equalTo folderPath }
                .resolve()
            assertTrue(
                joinerFolder != null,
                "Joiner's SDK chain should surface $folderPath"
            )

            val joinerFile = joiner.tenshuClient.fileService.defaultFileSystem.root.files
                .search<File> { path equalTo filePath }
                .resolve()
            assertTrue(
                joinerFile != null,
                "Joiner's SDK chain should surface $filePath"
            )
        } finally {
            system.shutdown()
        }
    }
}
