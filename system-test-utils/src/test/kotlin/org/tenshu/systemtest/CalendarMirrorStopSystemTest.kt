package org.tenshu.systemtest

import app.tenshu.calendar.data.CalendarDeviceIdentity
import app.tenshu.calendar.data.TenshuCalendarRepository
import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalSourceKind
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
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
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

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
 * the production personal push gate (`storageGate`), as in `ConcurrentMoveVsDeleteSystemTest` —
 * which explains why each closure is followed by `drainNow()`: a gate is sampled once per tick,
 * and only the scheduler's own `tickMutex` proves no push that sampled `true` is still running.
 * Both repositories are the real `TenshuCalendarRepository`, because *which rows a stamp writes* is
 * exactly the repository's choice.
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
        val system = TestSystem.builder().build()
        try {
            val phonePushes = AtomicBoolean(true)
            val tabletPushes = AtomicBoolean(true)
            val phone = system.client(
                deviceId = "phone",
                role = TestClientRole.RealmHost,
                pollingIntervalMs = 200,
                storageGate = SyncGate { phonePushes.get() },
            )
            val tablet = system.client(
                deviceId = "tablet",
                role = TestClientRole.JoiningDevice,
                pollingIntervalMs = 200,
                storageGate = SyncGate { tabletPushes.get() },
            )
            pair(system, host = phone, joiner = tablet)
            val ownerId = phone.syncManager.currentRealmContext()!!.ownerId
            val phoneRepo = repositoryOf(phone)
            val tabletRepo = repositoryOf(tablet)

            // ---- Shared starting state: the phone mirrors its Work calendar and has refreshed it
            // once; the tablet sees the mirror, so its calendar is read-only there too.
            val mirror = phoneRepo.startMirror(source, remindersEnabled = false)
            phoneRepo.stampMirror(mirror.mirrorId, FIRST_STAMP)
            system.advanceUntil(timeout = 60.seconds) {
                tabletRepo.mirrors().singleOrNull()?.lastRefreshedEpochMs == FIRST_STAMP &&
                    tabletRepo.calendars().any { it.calendarId == mirror.calendarId && it.readOnly }
            }
            assertTrue(phoneRepo.mirrors().single().boundHere, "precondition: the phone is the bound device")

            // ---- The partition: nothing either device writes from here reaches the relay.
            val blocksBefore = blockCount(system, ownerId)
            val tombstonesBefore = tombstoneCount(system, ownerId)
            phonePushes.set(false)
            tabletPushes.set(false)
            phone.syncScheduler.drainNow()
            tablet.syncScheduler.drainNow()

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

            delay(SETTLE)
            assertEquals(blocksBefore, blockCount(system, ownerId), "the partition held: no block crossed")
            assertEquals(tombstonesBefore, tombstoneCount(system, ownerId), "the partition held: no tombstone crossed")
            assertEquals(1, phoneRepo.mirrors().size, "the phone has not heard of the stop")

            // ---- Release, one device at a time, in the order under test.
            if (stampReachesTheRelayFirst) {
                phonePushes.set(true)
                system.awaitOrFail("the phone's stamp reached the relay") { blockCount(system, ownerId) > blocksBefore }
                delay(SETTLE) // the tablet pulls the stamp before its stop goes up
                tabletPushes.set(true)
            } else {
                tabletPushes.set(true)
                system.awaitOrFail(STOP_LEFT_THE_TABLET) { tombstoneCount(system, ownerId) > tombstonesBefore }
                delay(SETTLE) // the phone pulls the stop before its stamp goes up
                phonePushes.set(true)
            }
            system.awaitOrFail("the phone's stamp reached the relay") { blockCount(system, ownerId) > blocksBefore }
            system.awaitOrFail(STOP_LEFT_THE_TABLET) { tombstoneCount(system, ownerId) > tombstonesBefore }
            // ⚠ A resurrection is a merge-time event on whichever device ingests last, so the state
            // must hold through several more ticks before it is believed.
            delay(SETTLE)

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

    /** [TestSystem.advanceUntil], failing with what did not happen rather than a bare timeout. */
    private suspend fun TestSystem.awaitOrFail(what: String, condition: suspend () -> Boolean) {
        try {
            advanceUntil(timeout = 60.seconds) { condition() }
        } catch (_: TimeoutCancellationException) {
            fail("Timed out waiting until $what")
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

    private suspend fun pair(system: TestSystem, host: TestClient, joiner: TestClient) {
        host.bootstrapAsHost(deviceName = "Phone")
        host.tenshuClient.syncService.startPairing()
        val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
        joiner.tenshuClient.syncService.initiatePairing(
            ownerId = parsed.ownerId,
            pairingSecret = parsed.secret,
            deviceName = "Tablet",
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

    private companion object {
        const val FIRST_STAMP = 1_000_000L
        const val SECOND_STAMP = 2_000_000L

        /**
         * ⚠⚠ A precondition that was the defect's other half: `SyncObserver.onDataDeleted` is a
         * no-op, so a stop whose op emits no tombstone never leaves the tablet, and can never win.
         */
        const val STOP_LEFT_THE_TABLET = "the tablet's stop (its tombstone) reached the relay"

        /** How long a state must hold before it is believed. ~15 ticks at 200 ms. */
        val SETTLE = 3.seconds
    }
}
