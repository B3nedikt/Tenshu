package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.toList
import org.tenshu.core.equalTo
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Production complaint: "uploading files they don't synchronize. Like
 * basically what is supposed to happen with files is that the file itself
 * synchronizes, only the 'Data' relation should only be loaded from the
 * sync server when actually needed." So we assert here only on the entity
 * propagating — the OnDemand byte payload is out of scope.
 *
 * `File.Schema` carries `Syncable(SyncStrategy.Eager)` (file-service/.../File.kt);
 * uploading on one device should surface the File entity on every paired device.
 */
class FileEntitySyncSystemTest {

    @Test fun hostUploadFile_joinerSeesFileEntity() = runBlocking {
        val system = TestSystem.builder().build()
        try {
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

            // Upload two files in sequence — same "second one overwrites first"
            // shape as the folder regression report.
            val fileA = "first.txt"
            val pathA = "/$fileA"
            val fileB = "second.txt"
            val pathB = "/$fileB"

            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile("/", fileA, byteArrayOf(1, 2)) is TResult.Success
            )

            system.advanceUntil(timeout = 20.seconds) {
                val dataId = DataId.StringDataId.create(type = File.ID, keyValue = pathA)
                joiner.executionContext.database.getData(dataId) != null
            }

            assertTrue(
                host.tenshuClient.fileService.defaultFileSystem
                    .uploadFile("/", fileB, byteArrayOf(3, 4)) is TResult.Success
            )

            system.advanceUntil(timeout = 20.seconds) {
                val dataId = DataId.StringDataId.create(type = File.ID, keyValue = pathB)
                joiner.executionContext.database.getData(dataId) != null
            }

            // Both file entities must be addressable by their stable DataIds.
            for (path in setOf(pathA, pathB)) {
                val dataId = DataId.StringDataId.create(type = File.ID, keyValue = path)
                val resolved = joiner.executionContext.database.getData(dataId, asEntity = true)
                assertNotNull(resolved, "Joiner should have File entity for $path in DB")
                val readPath = when (resolved) {
                    is Entity<*> -> resolved.getFieldValue<TString>(File.Field.PATH).get()
                    else -> (resolved as org.tenshu.core.Composite)
                        .getField<TString>(File.Field.PATH).get()
                }
                assertEquals(path, readPath)
            }

            // Same assertion via the SDK chain, matching the Drive app's
            // `TenshuFilesRepository#getFiles("/")` shape.
            for (path in setOf(pathA, pathB)) {
                val resolved = joiner.tenshuClient.fileService.defaultFileSystem.root.files
                    .search<File> { this.path equalTo path }
                    .resolve()
                assertNotNull(resolved, "Joiner's SDK chain must surface $path")
            }
        } finally {
            system.shutdown()
        }
    }
}
