package org.tenshu.systemtest

import app.tenshu.calendar.data.CalendarDeviceIdentity
import app.tenshu.calendar.data.TenshuCalendarRepository
import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalCalendarReader
import app.tenshu.calendar.data.external.ExternalSnapshot
import app.tenshu.calendar.data.external.ExternalSourceKind
import app.tenshu.calendar.data.external.InMemoryMirrorDigestStore
import app.tenshu.calendar.data.external.MirrorRefresher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.CalendarSubscription
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.EventSource
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.ReminderState
import org.tenshu.calendar.client.calendarService
import org.tenshu.calendar.common.ics.ImportedEvent
import org.tenshu.calendar.deleteCalendar
import org.tenshu.calendar.deleteEvent
import org.tenshu.calendar.deleteOccurrence
import org.tenshu.calendar.deleteReminder
import org.tenshu.calendar.deleteSubscription
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.saveEventOverride
import org.tenshu.calendar.saveEventSource
import org.tenshu.calendar.saveReminder
import org.tenshu.calendar.saveSubscription
import org.tenshu.calendar.setReminderState
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.DataId
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * **A Calendar delete reaches every device** (`tech-debt.md` *"Calendar deletes emit no
 * tombstones"*).
 *
 * ⚠⚠ `SyncObserver.onDataDeleted` is a deliberate no-op: a delete leaves the device it was made on
 * only if its op emits a tombstone. Until this file only *Stop syncing* did (P2-a), so deleting an
 * event, a calendar, a reminder or a subscription — and every row a mirror refresh removed — stayed
 * on the device that did it, and the next write of the row from anywhere else brought it back.
 *
 * Each test builds the rows on the phone, waits until the tablet holds **every one of them** (the
 * precondition that makes the negative assertion mean something), deletes on the phone, and asserts
 * each row is gone on the tablet and **stays** gone through a settle period. Cascades are asserted
 * row by row: a delete that tombstones the parent but not its overrides, sidecar or reminder states
 * leaves live rows describing something that no longer exists.
 *
 * Rows are read straight off the tablet's store by key, so the assertion is about the row and not
 * about any read path that might filter it.
 */
class CalendarDeleteSyncSystemTest {

    @Test
    fun aDeletedEventLeavesThePeerWithItsOverrideAndSidecar() = twoDevices { phone, tablet ->
        val service = phone.tenshuClient.calendarService
        service.saveCalendar(CAL, "Work", 0x4285F4).orFail("saveCalendar")
        service.saveEvent(EVENT, "Standup", "2026-07-01T09:00", calendarId = CAL, recurrenceRule = WEEKLY)
            .orFail("saveEvent")
        service.saveEventOverride(EVENT, MOVED, startLocal = "2026-07-08T11:00").orFail("saveEventOverride")
        service.saveEventSource(EVENT, raw = "BEGIN:VEVENT\r\nUID:standup\r\nEND:VEVENT").orFail("saveEventSource")
        val rows = listOf(
            Event.ID to EVENT,
            EventOverride.ID to EventOverride.keyOf(EVENT, MOVED),
            EventSource.ID to EVENT,
        )
        awaitPresent(tablet, rows)

        service.deleteEvent(EVENT).orFail("deleteEvent")

        assertGoneAndStaysGone(tablet, rows)
    }

    @Test
    fun aSkippedOccurrencesOverrideLeavesThePeer() = twoDevices { phone, tablet ->
        val service = phone.tenshuClient.calendarService
        service.saveCalendar(CAL, "Work", 0x4285F4).orFail("saveCalendar")
        service.saveEvent(EVENT, "Standup", "2026-07-01T09:00", calendarId = CAL, recurrenceRule = WEEKLY)
            .orFail("saveEvent")
        service.saveEventOverride(EVENT, MOVED, startLocal = "2026-07-08T11:00").orFail("saveEventOverride")
        val override = EventOverride.ID to EventOverride.keyOf(EVENT, MOVED)
        awaitPresent(tablet, listOf(Event.ID to EVENT, override))

        service.deleteOccurrence(EVENT, MOVED).orFail("deleteOccurrence")

        assertGoneAndStaysGone(tablet, listOf(override))
        assertNotNull(rowOn(tablet, Event.ID, EVENT), "skipping one occurrence keeps the series")
    }

    @Test
    fun aDeletedCalendarLeavesThePeerWithItsEvents() = twoDevices { phone, tablet ->
        val service = phone.tenshuClient.calendarService
        service.saveCalendar(CAL, "Work", 0x4285F4).orFail("saveCalendar")
        service.saveEvent(EVENT, "Standup", "2026-07-01T09:00", calendarId = CAL, recurrenceRule = WEEKLY)
            .orFail("saveEvent")
        service.saveEventOverride(EVENT, MOVED, startLocal = "2026-07-08T11:00").orFail("saveEventOverride")
        val rows = listOf(
            Calendar.ID to CAL,
            Event.ID to EVENT,
            EventOverride.ID to EventOverride.keyOf(EVENT, MOVED),
        )
        awaitPresent(tablet, rows)

        service.deleteCalendar(CAL).orFail("deleteCalendar")

        assertGoneAndStaysGone(tablet, rows)
    }

    @Test
    fun aDeletedReminderLeavesThePeerWithItsState() = twoDevices { phone, tablet ->
        val service = phone.tenshuClient.calendarService
        service.saveCalendar(CAL, "Work", 0x4285F4).orFail("saveCalendar")
        service.saveEvent(EVENT, "Standup", "2026-07-01T09:00", calendarId = CAL).orFail("saveEvent")
        service.saveReminder(EVENT, 10).orFail("saveReminder")
        val reminderId = Reminder.idFor(EVENT, 10)
        service.setReminderState(reminderId, "2026-07-01T09:00", dismissed = true).orFail("setReminderState")
        val rows = listOf(
            Reminder.ID to reminderId,
            ReminderState.ID to ReminderState.idFor(reminderId, "2026-07-01T09:00"),
        )
        awaitPresent(tablet, rows)

        service.deleteReminder(reminderId).orFail("deleteReminder")

        assertGoneAndStaysGone(tablet, rows)
        assertNotNull(rowOn(tablet, Event.ID, EVENT), "deleting a reminder keeps its event")
    }

    @Test
    fun aDeletedSubscriptionLeavesThePeerWithItsCalendar() = twoDevices { phone, tablet ->
        val service = phone.tenshuClient.calendarService
        val subscriptionId = CalendarSubscription.idFor(FEED)
        service.saveCalendar(CAL, "Holidays", 0x4285F4).orFail("saveCalendar")
        service.saveSubscription(subscriptionId, CAL, FEED).orFail("saveSubscription")
        service.saveEvent(EVENT, "New Year", "2027-01-01T00:00", calendarId = CAL).orFail("saveEvent")
        val rows = listOf(
            CalendarSubscription.ID to subscriptionId,
            Calendar.ID to CAL,
            Event.ID to EVENT,
        )
        awaitPresent(tablet, rows)

        service.deleteSubscription(subscriptionId).orFail("deleteSubscription")

        assertGoneAndStaysGone(tablet, rows)
    }

    /**
     * ⚠⚠ A mirror refresh deletes in three places, and each must reach the peer: the sweep (an event
     * the source no longer holds, with its sidecar), the override *set* (an occurrence the source
     * moved back), and the reminder switch (Tenshu reminders turned off). The phone is the bound
     * device; the tablet only ever sees what arrives by sync.
     */
    @Test
    fun whatAMirrorRefreshRemovesLeavesThePeer() = twoDevices { phone, tablet ->
        val repo = TenshuCalendarRepository(
            client = phone.tenshuClient,
            deviceIdentity = CalendarDeviceIdentity { phone.syncManager.currentDeviceId },
            reachability = null /* test: no reachability engine */,
        )
        val standup = imported("standup", rule = WEEKLY)
        val moved = imported("standup", start = "2026-07-08T11:00", recurrenceId = MOVED)
        val reader = FakeReader(
            ExternalSnapshot(listOf(standup, moved, imported("lunch", reminders = listOf(10)), imported("gone")), skipped = 0)
        )
        val refresher = MirrorRefresher(repo, reader, InMemoryMirrorDigestStore()) { System.currentTimeMillis() }

        val mirror = repo.startMirror(SOURCE, remindersEnabled = true)
        refresher.refresh(mirror.mirrorId)
        val ids = repo.occurrences(LocalDate(2026, 7, 1), LocalDate(2026, 7, 31))
            .filter { it.calendarId == mirror.calendarId }
            .associate { it.title to it.eventId }
        val gone = assertNotNull(ids["gone"], "the first refresh wrote 'gone'")
        val standupId = assertNotNull(ids["standup"], "the first refresh wrote 'standup'")
        val lunchId = assertNotNull(ids["lunch"], "the first refresh wrote 'lunch'")
        val swept = listOf(Event.ID to gone, EventSource.ID to gone)
        val movedBack = EventOverride.ID to EventOverride.keyOf(standupId, MOVED)
        val reminder = Reminder.ID to Reminder.idFor(lunchId, 10)
        awaitPresent(tablet, swept + movedBack + reminder)

        // At the source: "gone" deleted, the 8th moved back.
        reader.snapshot = ExternalSnapshot(listOf(standup, imported("lunch", reminders = listOf(10))), skipped = 0)
        refresher.refresh(mirror.mirrorId)
        repo.setMirrorReminders(mirror.mirrorId, enabled = false)

        assertGoneAndStaysGone(tablet, swept + movedBack + reminder)
        assertNotNull(rowOn(tablet, Event.ID, standupId), "the series the source still holds stays")
        assertNotNull(rowOn(tablet, Event.ID, lunchId), "an event whose reminder went stays")
    }

    // ── harness ─────────────────────────────────────────────────────────────

    private class FakeReader(var snapshot: ExternalSnapshot) : ExternalCalendarReader {
        override suspend fun calendars() = listOf(SOURCE)
        override suspend fun read(calendar: ExternalCalendar) = snapshot
    }

    private fun imported(
        uid: String,
        start: String = "2026-07-01T09:00",
        rule: String = "",
        reminders: List<Int> = emptyList(),
        recurrenceId: String? = null,
    ) = ImportedEvent(
        uid = uid,
        title = uid,
        startLocal = start,
        endLocal = start.replace("T09:", "T10:").replace("T11:", "T12:"),
        timeZoneId = "Europe/Berlin",
        allDay = false,
        location = "",
        notes = "",
        recurrenceRule = rule,
        exceptionDates = "",
        recurrenceId = recurrenceId,
        reminderMinutes = reminders,
        raw = "BEGIN:VEVENT\r\nUID:$uid\r\nEND:VEVENT",
    )

    private fun twoDevices(block: suspend (phone: TestClient, tablet: TestClient) -> Unit) = runBlocking {
        val system = TestSystem.builder().build()
        try {
            val phone = system.client("phone", TestClientRole.RealmHost, pollingIntervalMs = 200)
            val tablet = system.client("tablet", TestClientRole.JoiningDevice, pollingIntervalMs = 200)
            pair(system, host = phone, joiner = tablet)
            this@CalendarDeleteSyncSystemTest.system = system
            block(phone, tablet)
        } finally {
            system.shutdown()
        }
    }

    private lateinit var system: TestSystem

    private fun rowOn(client: TestClient, type: String, key: String) =
        client.executionContext.database.getData(
            DataId.StringDataId.create(context = client.executionContext.context, type = type, keyValue = key)
        )

    /** The precondition: the peer holds every row, so its absence later is the delete's doing. */
    private suspend fun awaitPresent(peer: TestClient, rows: List<Pair<String, String>>) {
        awaitOrFail("the tablet holds every row before the delete: $rows") {
            rows.all { (type, key) -> rowOn(peer, type, key) != null }
        }
    }

    private suspend fun assertGoneAndStaysGone(peer: TestClient, rows: List<Pair<String, String>>) {
        awaitOrFail(
            "the delete reached the tablet; still there: " +
                rows.filter { (type, key) -> rowOn(peer, type, key) != null }
        ) { rows.all { (type, key) -> rowOn(peer, type, key) == null } }
        // ⚠ And stays gone: a resurrection is a later tick's event.
        delay(SETTLE)
        rows.forEach { (type, key) -> assertNull(rowOn(peer, type, key), "[tablet] $type $key is still gone") }
    }

    private suspend fun awaitOrFail(what: String, condition: suspend () -> Boolean) {
        try {
            system.advanceUntil(timeout = 30.seconds) { condition() }
        } catch (_: TimeoutCancellationException) {
            fail("Timed out waiting until $what")
        }
    }

    private fun TResult<*>.orFail(what: String) {
        assertTrue(this is TResult.Success<*>, "$what failed: $this")
    }

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
        const val CAL = "work"
        const val EVENT = "standup"
        const val WEEKLY = "FREQ=WEEKLY;BYDAY=WE"
        const val MOVED = "2026-07-08T09:00"
        const val FEED = "https://example.invalid/holidays.ics"

        val SOURCE = ExternalCalendar(
            kind = ExternalSourceKind.ANDROID_PROVIDER,
            sourceKey = "com.google|me@gmail.com|work",
            handle = "4",
            name = "Work",
            accountName = "me@gmail.com",
            accountType = "com.google",
            colorArgb = null,
            holdsEvents = true,
        )

        /** How long a state must hold before it is believed. ~15 ticks at 200 ms. */
        val SETTLE = 3.seconds
    }
}
