package org.tenshu.systemtest

import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.Bip39
import org.tenshu.core.entity.DataId
import org.tenshu.core.equalTo
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end regression for the "a file saved while the realm is locked never syncs" bug
 * (`fix/sync-reemit-locked-saves`). A host uploads a file **while its realm is locked** (no keys):
 * `SyncObserver` holds both the metadata save (`onDataSaved`) and the content emit
 * (`emitFileContent`) instead of silently dropping them. On **unlock**, the scheduler's
 * locked→enabled edge replays the held work, so a paired joiner must receive BOTH the File entity
 * (metadata blocks) AND its bytes (content manifest + chunks) over the real relay.
 *
 * Before the fix the host dropped both emits and the file was stranded local forever — this test
 * would then time out waiting for the joiner (which never received anything), so it is a true
 * regression: revert the fix and the `advanceUntil` below fails.
 *
 * `runBlocking` (not `runTest`) like the other SystemTests: `ProxyGateway.call` deadlocks against
 * the test dispatcher.
 */
class LockedUploadReemitSystemTest {

    @Test fun fileUploadedWhileLocked_reachesJoiner_afterUnlock() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val host = system.client("host", TestClientRole.RealmHost)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice)

            // Explicit phrase so the host can be re-derived (unlocked) later.
            val phrase = Bip39.generateMnemonic()
            host.bootstrapAsHost(deviceName = "Host Tablet", phrase = phrase)
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

            // ---- Lock the host, then upload a file WHILE LOCKED. ----
            host.syncManager.lock()
            assertFalse(host.syncManager.isEnabled(), "host realm must be locked before the upload")

            val fileName = "locked.txt"
            val filePath = "/$fileName"
            val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
            val uploadResult = host.tenshuClient.fileService.defaultFileSystem
                .uploadFile(parentPath = "/", fileName = fileName, data = payload)
            assertTrue(
                uploadResult is TResult.Success,
                "upload-while-locked still writes locally (bytes + entity); got $uploadResult"
            )
            // Sanity: the file is in the host's own DB (the write path works while locked) — the
            // emits are what were deferred, held for replay on unlock.
            assertNotNull(
                host.executionContext.database.getData(
                    DataId.StringDataId.create(type = File.ID, keyValue = filePath)
                ),
                "host must hold the file locally even though nothing was emitted yet"
            )

            // ---- Unlock the host: the enable edge replays the held metadata + content emits. ----
            host.syncManager.initialize(phrase)
            assertTrue(host.syncManager.isEnabled(), "host realm should be unlocked (keys re-derived)")

            // ---- The joiner receives the File entity (metadata reemit → drained → pulled). ----
            system.advanceUntil(timeout = 30.seconds) {
                joiner.executionContext.database.getData(
                    DataId.StringDataId.create(type = File.ID, keyValue = filePath)
                ) != null
            }

            // ---- ...and its bytes (content-manifest reemit → OnDemand fetch reassembles chunks). ----
            joiner.tenshuClient.fileService.defaultFileSystem.root
                .files
                .select {
                    fields(File.Field.PATH)
                    relations(File.Data)
                }
                .search<File> { this.path equalTo filePath }
                .resolve()
            val joinerBytes = assertNotNull(
                joiner.fileDatabase.readBytes(filePath),
                "joiner must be able to read the bytes of a file uploaded while the host was locked"
            )
            assertTrue(joinerBytes.get().contentEquals(payload), "joiner bytes must match the host's upload")
        } finally {
            system.shutdown()
        }
    }
}
