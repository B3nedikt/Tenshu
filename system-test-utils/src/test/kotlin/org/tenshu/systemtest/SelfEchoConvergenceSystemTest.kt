package org.tenshu.systemtest

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.file.client.fileService
import org.tenshu.sync.common.SyncGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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
 * Dropping a device's own blocks is an obvious "optimisation" to make, on either side. With it, the
 * devices would diverge silently: one on each write, and the relay's register on neither.
 *
 * ⚠⚠ **This test no longer catches that.** The LWW now also compares against the device's own last
 * local write (`LocalWriteMetadata`), so the older block no longer overwrites `newer`'s unpushed
 * write: caveat 1 does not happen here and the echo has nothing to restore. Checked 2026-11-15 by
 * dropping every own-device block in `SyncProcessor.processSyncBlock`/`processSyncBlockAsync`: this
 * test stayed green, polled and stepped alike. The echo is pinned instead by
 * `SyncProcessorOwnEchoTest.aQueuedWriteOverwrittenByAnIngest_stillConvergesFromItsEcho` (the
 * caveat-2 claim, forced) and `ListingNotificationSystemTest.aWipedDeviceRefillingItsOwnHistory_…`
 * (rehydrating from its own blocks), both red under that break. What this test still pins is the
 * end state: two concurrent overwrites converge on the higher-HLC write on both devices, and stay.
 *
 * ## Shape
 *
 * The partition is `ConcurrentMoveVsDeleteSystemTest`'s: both devices' production push gate
 * (`storageGate`) is closed. The system is stepped (`docs/testing.md` §12), so no tick runs while
 * the gates change and every tick after the closure samples them shut. `older` overwrites first and
 * `newer` second, so `newer`'s write carries the higher HLC. `older` is released first: one tick of
 * each, in that order, carries its older block to `newer` while `newer`'s own write is still unpushed
 * — exactly caveat 1's precondition. Then `newer` is released, and both must settle on `newer`'s
 * content and **stay** there: [TestSystem.syncUntilQuiet] ticks both until two rounds push nothing,
 * where a late re-application of the older block would be a push.
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
    }

    @Test
    fun concurrentOverwritesConvergeOnTheHigherHlcWrite_onBothDevices() = runBlocking {
        val system = TestSystem.builder().stepped().build()
        try {
            val olderPushes = AtomicBoolean(true)
            val newerPushes = AtomicBoolean(true)
            val older = system.client(
                deviceId = "older",
                role = TestClientRole.RealmHost,
                storageGate = SyncGate { olderPushes.get() },
            )
            val newer = system.client(
                deviceId = "newer",
                role = TestClientRole.JoiningDevice,
                storageGate = SyncGate { newerPushes.get() },
            )
            system.pair(host = older, joiner = newer, hostName = "Older Tablet", joinerName = "Newer Phone")

            // ---- Shared starting state: the file exists on both devices with one content.
            val olderFs = older.tenshuClient.fileService.defaultFileSystem
            assertTrue(olderFs.createFolder(FOLDER) is TResult.Success<*>, "createFolder $FOLDER")
            assertTrue(
                olderFs.uploadFile(parentPath = FOLDER, fileName = NAME, data = payload(0)) is TResult.Success<*>,
                "uploadFile $PATH",
            )
            val initial = assertNotNull(contentIdOf(older, PATH), "the upload has a contentId")
            system.syncUntil("`newer` holds the shared starting content") { contentIdOf(newer, PATH) == initial }

            // ⚠⚠ Precondition for `newer`'s pull-only tick below: a device whose push gate is shut
            // pulls only once `realmRelayRegistered` has latched, and a joiner latches it on a
            // push-allowed tick after its first pull (`ConcurrentMoveVsDeleteSystemTest` says why).
            // Without it, the tick that should carry the older block to `newer` would do nothing.
            system.syncUntil("both devices have latched their relay registration") {
                older.syncManager.realmRelayRegistered && newer.syncManager.realmRelayRegistered
            }

            // ---- The partition: nothing either device writes from here reaches the relay.
            olderPushes.set(false)
            newerPushes.set(false)

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

            // ⚠ The partition is checked after each device has really ticked with its gate shut (a
            // push attempt and a pull), and from the relay's own count, not after a wait.
            val arrivalsBefore = system.syncMailbox.lastArrivalSeq
            system.sync(older, newer)
            assertEquals(arrivalsBefore, system.syncMailbox.lastArrivalSeq, "partition held: the relay accepted nothing")
            assertEquals(olderContent, contentIdOf(older, PATH), "partition held: `newer`'s write did not reach `older`")
            assertEquals(newerContent, contentIdOf(newer, PATH), "partition held: `older`'s write did not reach `newer`")

            // ---- Release `older` first: its OLDER block reaches `newer`, whose newer write is unpushed.
            olderPushes.set(true)
            system.sync(older, newer) // `older` pushes its block; `newer` pulls it and still cannot push
            assertTrue(system.syncMailbox.lastArrivalSeq > arrivalsBefore, "precondition: `older`'s block reached the relay")
            println(
                "SelfEchoConvergenceSystemTest -> after the older block landed, `newer` holds " +
                    (if (contentIdOf(newer, PATH) == olderContent) "the OLDER write (caveat 1 overwrote it)" else "its own newer write"),
            )

            // ---- Release `newer`: its block (and its echo) must win on both devices.
            newerPushes.set(true)
            system.syncUntil("both devices converged on `newer`'s write") {
                contentIdOf(older, PATH) == newerContent && contentIdOf(newer, PATH) == newerContent
            }
            // ⚠ Once sync is quiet, not on first sighting: a late re-application of the older block
            // would flip a device back, and a first-sighting assertion cannot see that.
            system.syncUntilQuiet()
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
}
