package org.tenshu.systemtest

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.toList
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.get
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
 * Pins down the cross-device version of the production read path used by the
 * Drive app's home screen: `root.files.flow()` — exactly the shape of
 * `TenshuFilesRepository#getFiles("/")` in `:drive-shared`. The companion
 * tests (`FolderSyncViaSdkSystemTest`, `FileEntitySyncSystemTest`,
 * `TwoFoldersSequentialSystemTest`) use `.get()` (one-shot), which doesn't
 * exercise the `ExecutionContext.setDatabaseListener` stream path. The
 * `.flow()` path is what registers the array listener that fires
 * `triggerUpdate` → `onUpdate` — i.e. the codepath that produced the
 * `ArrayStoreException` regression in the catch block at
 * `ExecutionContext.kt:293`.
 *
 * Asserts that the host's folder + file uploads surface on the joiner's
 * `.flow()` collector, and that no `TResult.Failure` is emitted along the
 * way (which would indicate an exception in `onUpdate` was caught and
 * turned into a stream-end error).
 */
class FileSyncFlowSystemTest {

    @Test fun joinerFlow_receivesHostUploads() = runBlocking {
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

            // Host: create a folder and upload a file in sequence — the exact
            // shape from the production complaint that originally surfaced the
            // ArrayStoreException.
            host.tenshuClient.fileService.defaultFileSystem.createFolder("/A")
            host.tenshuClient.fileService.defaultFileSystem
                .uploadFile(parentPath = "/", fileName = "shared.txt", data = byteArrayOf(1, 2, 3))

            // Drain the production-shape stream on the joiner. Each `advanceUntil`
            // iteration re-subscribes — that's fine, every subscription drives
            // the same `setDatabaseListener` path that crashed in production.
            // A `TResult.Failure` here would indicate the stream's catch handler
            // turned an exception into an End/error — the regression we guard
            // against.
            system.advanceUntil(timeout = 30.seconds) {
                val result = joiner.tenshuClient.fileService.defaultFileSystem
                    .root.files.flow().first()
                if (result is TResult.Failure<*>) {
                    error("Joiner's root.files.flow() emitted a Failure: $result")
                }
                val paths = (result as TResult.Success).get().toList()
                    .map { (it as File).path.get() }.toSet()
                paths.containsAll(setOf("/A", "/shared.txt"))
            }

            val finalResult = joiner.tenshuClient.fileService.defaultFileSystem
                .root.files.flow().first()
            assertTrue(
                finalResult is TResult.Success<*>,
                "Final emission must be Success, was $finalResult"
            )
            val finalPaths = finalResult.get().toList()
                .map { (it as File).path.get() }.toSet()
            assertTrue(
                "/A" in finalPaths,
                "Joiner flow should surface /A; got $finalPaths"
            )
            assertTrue(
                "/shared.txt" in finalPaths,
                "Joiner flow should surface /shared.txt; got $finalPaths"
            )
        } finally {
            system.shutdown()
        }
    }
}
