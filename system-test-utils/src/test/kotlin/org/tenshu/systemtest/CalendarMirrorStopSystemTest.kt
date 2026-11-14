package org.tenshu.systemtest

import app.tenshu.calendar.data.CalendarDeviceIdentity
import app.tenshu.calendar.data.TenshuCalendarRepository
import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalSourceKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.tenshu.sync.common.SyncGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A stopped *Keep in sync* mirror stays stopped** (`calendar-external-sources.md` §11 P2-a,
 * `tech-debt.md` *"Mirror stamp can resurrect a stopped mirror"*).
 *
 * The phone is the mirror's bound device: it alone can read the source, so it alone refreshes the
 * mirror and records when it last did. The tablet stops the sync while the phone is offline. The
 * phone, still holding the mirror as it last saw it, then records a refresh. When the two histories
 * merge, the stop must win — on both devices, in both orders the writes can reach the relay.
 *
 * ⚠⚠ The defect this pins: the freshness stamp lived on the shared `CalendarMirror` row, and the
 * stamp was a read-modify-write of the **whole row**. The phone's stamp was therefore a newer save
 * of the very entity the tablet had tombstoned, and a block newer than a tombstone is a recreate
 * (`docs/sync/design.md` §2.6). The calendar turned read-only again, and the phone's next refresh
 * could sweep the events the user had added to it meanwhile.
 *
 * ## Why two real devices
 *
 * The claim is about two independently-written histories merging, which one device cannot express:
 * a stop and a stamp on one device are sequential, and the stamp sees the stop. The partition is
 * Both repositories are the real `TenshuCalendarRepository`, because *which rows a stamp writes* is
 * exactly the repository's choice.
 *
 * ## Stepped: the two orders are written, not waited for
 *
 * The system is stepped (`TestSystem` KDoc, `docs/testing.md` §12), so neither device ticks unless
 * the test ticks it, and each order is a sequence of [TestSystem.sync] calls:
 *
 *  - **stamp first**: the phone ticks (its stamp goes up), then the tablet ticks with its push gate
 *    still shut (it pulls the stamp and pushes nothing), then the tablet's gate opens.
 *  - **stop first**: the mirror image. The tablet's stop goes up, the phone pulls it with its own
 *    gate shut, then the phone's gate opens.
 *
 * The production personal push gate (`storageGate`) is what makes the middle step a *pull-only*
 * tick, so "the tablet pulls the stamp before its stop goes up" is a fact of the sequence rather
 * than of a 3 s settle. A stepped tick cannot be in flight while a gate changes, so the
 * `drainNow()` boundary the polled version needed after each closure is gone with the loop. "And
 * it stays that way" is [TestSystem.syncUntilQuiet]: rounds on both devices until two in a row
 * push nothing, so whichever device ingests last has ingested everything.
 */
class CalendarMirrorStopSystemTest {

    private val source = ExternalCalendar(
        kind = ExternalSourceKind.ANDROID_PROVIDER,
        sourceKey = "com.google|me@gmail.com|work",
        handle = "4",
        name = "Work",
        accountName = "me@gmail.com",
        accountType = "com.google",
        colorArgb = null,
        holdsEvents = true,
    )

    @Test
    fun aStopOnAnotherDeviceSurvivesTheBoundPhonesStaleStamp_stampReachesTheRelayFirst() = runBlocking {
        stopVersusStamp(stampReachesTheRelayFirst = true)
    }

    @Test
    fun aStopOnAnotherDeviceSurvivesTheBoundPhonesStaleStamp_stopReachesTheRelayFirst() = runBlocking {
        stopVersusStamp(stampReachesTheRelayFirst = false)
    }

    private suspend fun stopVersusStamp(stampReachesTheRelayFirst: Boolean) {
        val system = TestSystem.builder().stepped().build()
        try {
            val phonePushes = AtomicBoolean(true)
            val tabletPushes = AtomicBoolean(true)
            val phone = system.client(
                deviceId = "phone",
                role = TestClientRole.RealmHost,
                storageGate = SyncGate { phonePushes.get() },
            )
            val tablet = system.client(
                deviceId = "tablet",
                role = TestClientRole.JoiningDevice,
                storageGate = SyncGate { tabletPushes.get() },
            )
            system.pair(host = phone, joiner = tablet, hostName = "Phone", joinerName = "Tablet")
            val ownerId = phone.syncManager.currentRealmContext()!!.ownerId
            val phoneRepo = repositoryOf(phone)
            val tabletRepo = repositoryOf(tablet)

            // ---- Shared starting state: the phone mirrors its Work calendar and has refreshed it
            // once; the tablet sees the mirror, so its calendar is read-only there too.
            val mirror = phoneRepo.startMirror(source, remindersEnabled = false)
            phoneRepo.stampMirror(mirror.mirrorId, FIRST_STAMP)
            system.syncUntil("the tablet sees the stamped, read-only mirror") {
                tabletRepo.mirrors().singleOrNull()?.lastRefreshedEpochMs == FIRST_STAMP &&
                    tabletRepo.calendars().any { it.calendarId == mirror.calendarId && it.readOnly }
            }
            assertTrue(phoneRepo.mirrors().single().boundHere, "precondition: the phone is the bound device")

            // ⚠⚠ Precondition for a pull-only tick: a device whose push gate is shut still pulls
            // only once `realmRelayRegistered` has latched (`SyncScheduler.drainAndPull`'s
            // `pullAllowed`), and a joiner latches it in `RealmRegistrar.ensureRegistered` on a
            // push-allowed tick AFTER its first pull. The polled version got that second open tick
            // from its cadence; stepped, the joiner can have pulled exactly once, and its gated
            // ticks below would then do nothing at all — so the ordering would degrade silently.
            system.syncUntil("both devices have latched their relay registration") {
                phone.syncManager.realmRelayRegistered && tablet.syncManager.realmRelayRegistered
            }

            // ---- The partition: neither device pushes from here until the release below. Both
            // gates shut, so the release can give each device a pull-only tick.
            val blocksBefore = blockCount(system, ownerId)
            val tombstonesBefore = tombstoneCount(system, ownerId)
            phonePushes.set(false)
            tabletPushes.set(false)

            // The tablet stops the sync (keep events)…
            tabletRepo.stopMirror(mirror.mirrorId)
            assertEquals(emptyList(), tabletRepo.mirrors(), "precondition: the tablet stopped it")
            // …and only then, by the clock, the phone records a refresh of the mirror it still holds.
            // ⚠ Later on purpose: the defect needs the stamp's HLC to be newer than the stop's.
            delay(50)
            phoneRepo.stampMirror(mirror.mirrorId, SECOND_STAMP)
            assertEquals(
                SECOND_STAMP,
                phoneRepo.mirrors().single().lastRefreshedEpochMs,
                "precondition: the stamp was really written — a stamp that wrote nothing would pass " +
                    "this test for the wrong reason",
            )

            // ⚠ A tick on each with both gates shut: the partition must hold through real ticks,
            // not merely through the absence of them.
            system.sync(phone, tablet)
            assertEquals(blocksBefore, blockCount(system, ownerId), "the partition held: no block crossed")
            assertEquals(tombstonesBefore, tombstoneCount(system, ownerId), "the partition held: no tombstone crossed")
            assertEquals(1, phoneRepo.mirrors().size, "the phone has not heard of the stop")

            // ---- Release, one device at a time, in the order under test.
            if (stampReachesTheRelayFirst) {
                phonePushes.set(true)
                system.sync(phone)
                assertTrue(blockCount(system, ownerId) > blocksBefore, "the phone's stamp reached the relay")
                // A pull-only tick: the tablet ingests the stamp before its stop goes up.
                system.sync(tablet)
                assertEquals(tombstonesBefore, tombstoneCount(system, ownerId), "the stop is still only on the tablet")
                tabletPushes.set(true)
                system.sync(tablet)
            } else {
                tabletPushes.set(true)
                system.sync(tablet)
                assertTrue(tombstoneCount(system, ownerId) > tombstonesBefore, STOP_LEFT_THE_TABLET)
                // A pull-only tick: the phone ingests the stop before its stamp goes up. (Counted
                // from here: the stop may carry blocks of its own besides its tombstone.)
                val blocksWithTheStop = blockCount(system, ownerId)
                system.sync(phone)
                assertEquals(blocksWithTheStop, blockCount(system, ownerId), "the stamp is still only on the phone")
                phonePushes.set(true)
                system.sync(phone)
                assertTrue(blockCount(system, ownerId) > blocksWithTheStop, "the phone's stamp reached the relay")
            }
            assertTrue(blockCount(system, ownerId) > blocksBefore, "the phone's stamp reached the relay")
            assertTrue(tombstoneCount(system, ownerId) > tombstonesBefore, STOP_LEFT_THE_TABLET)
            // ⚠ A resurrection is a merge-time event on whichever device ingests last, so both must
            // have ingested everything, with nothing left to push, before the state is believed.
            system.syncUntilQuiet()

            for ((who, repo) in listOf("phone" to phoneRepo, "tablet" to tabletRepo)) {
                assertEquals(
                    emptyList(),
                    repo.mirrors().map { it.mirrorId },
                    "[$who] the stop wins: the bound phone's stale stamp must not bring the mirror back",
                )
                val calendar = repo.calendars().single { it.calendarId == mirror.calendarId }
                assertEquals(
                    false,
                    calendar.readOnly,
                    "[$who] the kept calendar stays an ordinary, editable one",
                )
            }
        } finally {
            system.shutdown()
        }
    }

    private fun repositoryOf(client: TestClient) = TenshuCalendarRepository(
        client = client.tenshuClient,
        deviceIdentity = CalendarDeviceIdentity { client.syncManager.currentDeviceId },
        reachability = null /* test: no reachability engine */,
    )

    private fun blockCount(system: TestSystem, ownerId: String): Int =
        system.syncMailbox.getChanges(ownerId, sinceTimestamp = 0L, limit = 100_000).blocks.size

    private fun tombstoneCount(system: TestSystem, ownerId: String): Int =
        system.syncMailbox.getTombstones(ownerId, sinceTimestamp = 0L, limit = 10_000).tombstones.size

    private companion object {
        const val FIRST_STAMP = 1_000_000L
        const val SECOND_STAMP = 2_000_000L

        /**
         * ⚠⚠ A precondition that was the defect's other half: `SyncObserver.onDataDeleted` is a
         * no-op, so a stop whose op emits no tombstone never leaves the tablet, and can never win.
         */
        const val STOP_LEFT_THE_TABLET = "the tablet's stop (its tombstone) reached the relay"
    }
}
