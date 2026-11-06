package org.tenshu.systemtest

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.file.client.fileService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * ⭐ CR-2 B3b, B2 audit Q8 (`content-plane-cr2-quorumless.md` §10.12): **two devices that overwrite
 * one row concurrently end on the higher-HLC write — because each device re-ingests its own block.**
 *
 * ## ⚠⚠ Why this needs pinning
 *
 * Candidate B's relay registers assume every client ends on the row with the **highest pushed HLC**.
 * Clients do, but only by an indirect route (audit §3.2):
 *  1. `SyncProcessor`'s LWW compares an incoming block against the last **ingested** HLC only, so a
 *     device's own newer, not-yet-echoed write is invisible to it, and an older peer block overwrites
 *     that write locally (caveat 1);
 *  2. the device then pulls its **own** block back — the relay serves every block past the cursor with
 *     no device filter — and that echo restores the newer row (caveat 2).
 *
 * Nothing tested step 2, and dropping a device's own blocks is an obvious "optimisation" to make, on
 * either side. With it, the devices would diverge silently: one on each write, and the relay's
 * register on neither. This test fails the day that happens.
 *
 * ## Shape
 *
 * The partition is `ConcurrentMoveVsDeleteSystemTest`'s: both devices' production push gate
 * (`storageGate`) is closed, and [org.tenshu.sync.proxy.SyncScheduler.drainNow] is the boundary that
 * makes the closure real. `older` overwrites first and `newer` second, so `newer`'s write carries the
 * higher HLC. `older` is released first: its older block reaches `newer` while `newer`'s own write is
 * still unpushed — exactly caveat 1's precondition. Then `newer` is released, and both must settle on
 * `newer`'s content and stay there.
 *
 * ⚠ The intermediate state on `newer` (did caveat 1 actually overwrite it?) is reported, not asserted:
 * the claim under test is the end state. Asserting the intermediate would pin the very caveat §10.13
 * proposes to remove.
 */
class SelfEchoConvergenceSystemTest {

    private companion object {
        const val FOLDER = "/echo"
        const val NAME = "x.bin"
        const val PATH = "$FOLDER/$NAME"
        val SETTLE = 3.seconds
    }

    @Test
    fun concurrentOverwritesConvergeOnTheHigherHlcWrite_onBothDevices() = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val olderPushes = AtomicBoolean(true)
            val newerPushes = AtomicBoolean(true)
            val older = system.client(
                deviceId = "older",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 200,
                storageGate = SyncGate { olderPushes.get() },
            )
            val newer = system.client(
                deviceId = "newer",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 200,
                storageGate = SyncGate { newerPushes.get() },
            )
            pair(system, host = older, joiner = newer)

            // ---- Shared starting state: the file exists on both devices with one content.
            val olderFs = older.tenshuClient.fileService.defaultFileSystem
            assertTrue(olderFs.createFolder(FOLDER) is TResult.Success<*>, "createFolder $FOLDER")
            assertTrue(
                olderFs.uploadFile(parentPath = FOLDER, fileName = NAME, data = payload(0)) is TResult.Success<*>,
                "uploadFile $PATH",
            )
            val initial = assertNotNull(contentIdOf(older, PATH), "the upload has a contentId")
            system.advanceUntil(timeout = 60.seconds) { contentIdOf(newer, PATH) == initial }

            // ---- The partition: nothing either device writes from here reaches the relay.
            olderPushes.set(false)
            newerPushes.set(false)
            older.syncScheduler.drainNow()
            newer.syncScheduler.drainNow()

            // ---- Two concurrent overwrites; `newer` writes second, so its HLC is the higher one.
            assertTrue(
                olderFs.replaceFile(parentPath = FOLDER, fileName = NAME, data = payload(1)) is TResult.Success<*>,
                "the older overwrite",
            )
            val olderContent = assertNotNull(contentIdOf(older, PATH))
            delay(50.milliseconds)
            assertTrue(
                newer.tenshuClient.fileService.defaultFileSystem
                    .replaceFile(parentPath = FOLDER, fileName = NAME, data = payload(2)) is TResult.Success<*>,
                "the newer overwrite",
            )
            val newerContent = assertNotNull(contentIdOf(newer, PATH))
            assertNotEquals(initial, olderContent, "precondition: an overwrite mints a fresh contentId")
            assertNotEquals(olderContent, newerContent, "precondition: the two writes differ")

            delay(SETTLE)
            assertEquals(olderContent, contentIdOf(older, PATH), "partition held: `newer`'s write did not reach `older`")
            assertEquals(newerContent, contentIdOf(newer, PATH), "partition held: `older`'s write did not reach `newer`")

            // ---- Release `older` first: its OLDER block reaches `newer`, whose newer write is unpushed.
            olderPushes.set(true)
            older.syncScheduler.drainNow() // pushes `older`'s block
            newer.syncScheduler.drainNow() // pulls it; `newer` still cannot push
            println(
                "SelfEchoConvergenceSystemTest -> after the older block landed, `newer` holds " +
                    (if (contentIdOf(newer, PATH) == olderContent) "the OLDER write (caveat 1 overwrote it)" else "its own newer write"),
            )

            // ---- Release `newer`: its block (and its echo) must win on both devices.
            newerPushes.set(true)
            system.advanceUntil(timeout = 60.seconds) {
                contentIdOf(older, PATH) == newerContent && contentIdOf(newer, PATH) == newerContent
            }
            // ⚠ After a quiet period, not on first sighting: a late re-application of the older block
            // would flip a device back, and a first-sighting assertion cannot see that.
            delay(SETTLE)
            assertEquals(newerContent, contentIdOf(older, PATH), "`older` ends on the higher-HLC write")
            assertEquals(
                newerContent,
                contentIdOf(newer, PATH),
                "`newer` ends on its own higher-HLC write — restored by its own echo if the older block overwrote it",
            )
        } finally {
            system.shutdown()
        }
    }

    private fun payload(seed: Int) = ByteArray(4_000) { ((it + seed * 7) % 251).toByte() }

    private fun rowAt(client: TestClient, path: String): Data? =
        client.executionContext.database.getData(
            DataId.StringDataId.create(context = client.executionContext.context, type = File.ID, keyValue = path),
        )

    private fun contentIdOf(client: TestClient, path: String): String? =
        rowAt(client, path)?.let { row ->
            (client.executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)?.get()
        }

    private suspend fun pair(system: TestSystem, host: TestClient, joiner: TestClient) {
        host.bootstrapAsHost(deviceName = "Older Tablet")
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Newer Phone",
            otherDevicePublicKey = parsed.hostPairingPublicKey,
        )
        val request = host.tenshuClient.syncService.getPendingPairingRequests()
            .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
        host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
        system.advanceUntil(timeout = 60.seconds) {
            val status = joiner.tenshuClient.syncService.getPairingStatus(
                pairingSecret = parsed.secret,
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            status is TResult.Success && status.value?.get() == true
        }
    }
}
