package app.tenshu.calendar.data

import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalCalendarReader
import app.tenshu.calendar.data.external.ExternalSnapshot
import app.tenshu.calendar.data.external.ExternalSourceKind
import app.tenshu.calendar.data.external.InMemoryMirrorDigestStore
import app.tenshu.calendar.data.external.MirrorRefreshOutcome
import app.tenshu.calendar.data.external.MirrorRefresher
import app.tenshu.calendar.data.external.SnapshotWindow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.assertNotEquals
import kotlinx.coroutines.flow.first
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.common.ics.ImportedEvent
import org.tenshu.calendar.saveCalendarMirror
import org.tenshu.calendar.saveCalendarMirrorStamp
import org.tenshu.calendar.test.asTenshuClient
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.calendar.test.environmentFor
import org.tenshu.core.common.TestProxy
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.calendar.CalendarMirrorStamp

/**
 * *Keep in sync*, end to end: a fake OS reader → [MirrorRefresher] → the real repository → the
 * batched op → storage → the read path (`calendar-external-sources.md` CX-1b).
 *
 * ⚠ The assertions that matter are about what a refresh **does not** do: rewrite an unchanged
 * calendar, sweep on an empty read, touch a mirror bound elsewhere.
 */
class MirrorSyncTest {

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

    private class FakeReader(var calendars: List<ExternalCalendar>, var snapshot: ExternalSnapshot) :
        ExternalCalendarReader {
        var reads = 0
        override suspend fun calendars() = calendars
        /** ⚠ Set to hold a read open: [entered] completes once a read is waiting on it. */
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override suspend fun read(calendar: ExternalCalendar): ExternalSnapshot {
            reads++
            // Captured before the wait, like a real read: it returns what it read, not what is
            // there by the time it finishes.
            val read = snapshot
            gate?.let { entered.complete(Unit); it.await() }
            return read
        }
    }

    private class Clock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private class World(
        val proxy: TestProxy,
        val repo: TenshuCalendarRepository,
        val reader: FakeReader,
        val clock: Clock,
        val refresher: MirrorRefresher,
    )

    private fun world(proxy: TestProxy, snapshot: ExternalSnapshot): World {
        val repo = TenshuCalendarRepository(
            client = proxy.asTenshuClient(),
            deviceIdentity = CalendarDeviceIdentity { "device-a" },
            reachability = null /* test: no reachability engine */,
        )
        val reader = FakeReader(listOf(source), snapshot)
        val clock = Clock()
        return World(proxy, repo, reader, clock, MirrorRefresher(repo, reader, InMemoryMirrorDigestStore()) { clock.now })
    }

    private fun event(
        uid: String,
        title: String = uid,
        start: String = "2026-07-01T09:00",
        rule: String = "",
        reminders: List<Int> = emptyList(),
        recurrenceId: String? = null,
    ) = ImportedEvent(
        uid = uid,
        title = title,
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
        raw = "",
    )

    private fun snapshot(vararg events: ImportedEvent) = ExternalSnapshot(events.toList(), skipped = 0)

    private suspend fun World.start(reminders: Boolean = false): CalendarMirrorSummary =
        repo.startMirror(source, remindersEnabled = reminders)

    private suspend fun World.july(calendarId: String) =
        repo.occurrences(LocalDate(2026, 7, 1), LocalDate(2026, 7, 31)).filter { it.calendarId == calendarId }

    @Test
    fun a_mirror_follows_its_source_edits_deletions_and_moved_occurrences() = runTest {
        val standup = event("standup", rule = "FREQ=WEEKLY;BYDAY=WE")
        val moved = event("standup", start = "2026-07-08T11:00", recurrenceId = "2026-07-08T09:00")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup, moved, event("lunch"), event("gone")))
        val mirror = w.start()

        val first = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refresh(mirror.mirrorId)).result
        assertEquals(3, first.written)
        assertTrue(w.july(mirror.calendarId).any { it.startLocal.toString() == "2026-07-08T11:00" })

        // At the source: lunch renamed, "gone" deleted, the 8th moved back.
        w.reader.snapshot = snapshot(standup, event("lunch", title = "Team lunch"))
        val second = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refreshAll().single()).result

        assertEquals(2, second.written, "lunch, and standup whose override went")
        assertEquals(1, second.deleted)
        assertEquals(0, second.unchanged)
        val july = w.july(mirror.calendarId)
        assertTrue(july.none { it.title == "gone" })
        assertTrue(july.any { it.title == "Team lunch" })
        assertTrue(july.none { it.startLocal.toString() == "2026-07-08T11:00" }, "moved back at the source")
    }

    /** An EventKit-style read: complete only for July 2026. */
    private fun july(vararg events: ImportedEvent) = ExternalSnapshot(
        events.toList(),
        skipped = 0,
        window = SnapshotWindow(
            LocalDate(2026, 7, 1).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            LocalDate(2026, 8, 1).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        ),
    )

    private suspend fun World.june(calendarId: String) =
        repo.occurrences(LocalDate(2026, 6, 1), LocalDate(2026, 6, 30)).filter { it.calendarId == calendarId }

    /**
     * ⚠⚠ A windowed read says nothing about what lies outside it: an event that aged out of the
     * window is still at the source and must not be swept. One absent from inside it is deleted.
     */
    @Test
    fun a_windowed_read_sweeps_only_inside_its_window() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("old", start = "2026-06-10T09:00"), event("inside", start = "2026-07-10T09:00"), event("kept", start = "2026-07-20T09:00")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = july(event("kept", start = "2026-07-20T09:00"))
        val result = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refresh(mirror.mirrorId)).result

        assertEquals(1, result.deleted, "only the one the window would have returned")
        assertEquals(listOf("old"), w.june(mirror.calendarId).map { it.title }, "aged out, not deleted")
        assertEquals(listOf("kept"), w.july(mirror.calendarId).map { it.title })
    }

    /** ⚠⚠ Cancellations outside the window were not re-inferred, so they are kept — not undone. */
    @Test
    fun a_windowed_read_keeps_cancellations_outside_its_window() = runTest {
        val standup = event("standup", start = "2026-06-03T09:00", rule = "FREQ=WEEKLY;BYDAY=WE")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup.copy(exceptionDates = "2026-06-17")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = july(standup.copy(exceptionDates = "2026-07-15"))
        w.refresher.refresh(mirror.mirrorId)

        assertTrue(w.june(mirror.calendarId).none { it.startLocal.date == LocalDate(2026, 6, 17) }, "still cancelled")
        assertTrue(w.july(mirror.calendarId).none { it.startLocal.date == LocalDate(2026, 7, 15) })
    }

    /** ⚠⚠ Likewise a moved occurrence outside the window stays moved — the op *sets* overrides. */
    @Test
    fun a_windowed_read_keeps_moved_occurrences_outside_its_window() = runTest {
        val standup = event("standup", start = "2026-06-03T09:00", rule = "FREQ=WEEKLY;BYDAY=WE")
        val movedInJune = event("standup", start = "2026-06-10T11:00", recurrenceId = "2026-06-10T09:00")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup, movedInJune))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = july(standup)
        w.refresher.refresh(mirror.mirrorId)

        assertTrue(w.june(mirror.calendarId).any { it.startLocal.toString() == "2026-06-10T11:00" }, "still moved")
    }

    /**
     * ⚠⚠ An occurrence moved OUT of the window is absent from the read (EventKit queries by where
     * it is now) and its original slot reads as cancelled. The move is kept and wins.
     */
    @Test
    fun a_windowed_read_keeps_an_occurrence_moved_out_of_its_window() = runTest {
        val standup = event("standup", start = "2026-06-03T09:00", rule = "FREQ=WEEKLY;BYDAY=WE")
        val movedToJune = event("standup", start = "2026-06-25T09:00", recurrenceId = "2026-07-15T09:00")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup, movedToJune))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        // What the July read infers: the 15th is missing, so it looks cancelled.
        w.reader.snapshot = july(standup.copy(exceptionDates = "2026-07-15"))
        w.refresher.refresh(mirror.mirrorId)

        // ⚠ Read over both months: an occurrence is found from its original slot (15 July).
        val summer = w.repo.occurrences(LocalDate(2026, 6, 1), LocalDate(2026, 7, 31))
            .filter { it.calendarId == mirror.calendarId }
        assertTrue(summer.any { it.startLocal.toString() == "2026-06-25T09:00" }, "still moved to June")
        assertTrue(summer.none { it.startLocal.date == LocalDate(2026, 7, 15) }, "and not back in July")
    }

    /** ⚠ A move out of the window is undone once its original slot is seen again, unmoved. */
    @Test
    fun a_move_out_of_the_window_is_undone_when_its_slot_is_seen_again() = runTest {
        val standup = event("standup", start = "2026-06-03T09:00", rule = "FREQ=WEEKLY;BYDAY=WE")
        val movedToJune = event("standup", start = "2026-06-25T09:00", recurrenceId = "2026-07-15T09:00")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup, movedToJune))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = july(standup).copy(observedOccurrenceDates = mapOf("standup" to setOf("2026-07-15")))
        w.refresher.refresh(mirror.mirrorId)

        val summer = w.repo.occurrences(LocalDate(2026, 6, 1), LocalDate(2026, 7, 31)).filter { it.calendarId == mirror.calendarId }
        assertTrue(summer.none { it.startLocal.toString() == "2026-06-25T09:00" }, "the move is gone")
        assertTrue(summer.any { it.startLocal.toString() == "2026-07-15T09:00" }, "back in its slot")
    }

    /** ⚠ A cancelled occurrence restored at the source and moved into the window shows up. */
    @Test
    fun a_read_move_beats_a_kept_cancellation_of_its_slot() = runTest {
        val standup = event("standup", start = "2026-06-03T09:00", rule = "FREQ=WEEKLY;BYDAY=WE")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup.copy(exceptionDates = "2026-06-24")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        val restoredIntoJuly = event("standup", start = "2026-07-02T09:00", recurrenceId = "2026-06-24T09:00")
        w.reader.snapshot = july(standup, restoredIntoJuly)
        w.refresher.refresh(mirror.mirrorId)

        val summer = w.repo.occurrences(LocalDate(2026, 6, 1), LocalDate(2026, 7, 31)).filter { it.calendarId == mirror.calendarId }
        assertTrue(summer.any { it.startLocal.toString() == "2026-07-02T09:00" }, "restored and moved")
    }

    /**
     * ⚠⚠ A series whose only in-window occurrences were moved out of it is not returned — and is
     * not deleted, moves included.
     */
    @Test
    fun a_series_whose_window_occurrences_all_moved_out_is_not_swept() = runTest {
        val pair = event("pair", start = "2026-07-08T09:00", rule = "FREQ=WEEKLY;COUNT=2")
        val first = event("pair", start = "2026-06-10T09:00", recurrenceId = "2026-07-08T09:00")
        val second = event("pair", start = "2026-06-17T09:00", recurrenceId = "2026-07-15T09:00")
        val w = world(createCalendarProxy(backgroundScope), snapshot(pair, first, second, event("other", start = "2026-07-20T09:00")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = july(event("other", start = "2026-07-20T09:00"))
        val result = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refresh(mirror.mirrorId)).result

        assertEquals(0, result.deleted)
        val summer = w.repo.occurrences(LocalDate(2026, 6, 1), LocalDate(2026, 7, 31)).filter { it.calendarId == mirror.calendarId }
        assertTrue(summer.any { it.startLocal.toString() == "2026-06-10T09:00" }, "the moves survive")
    }

    /** ⚠ A slot the reader saw is not cancelled — even on a margin day, which coverage leaves out. */
    @Test
    fun an_observed_slot_clears_a_kept_cancellation_on_a_margin_day() = runTest {
        val standup = event("standup", start = "2026-06-03T09:00", rule = "FREQ=WEEKLY;BYDAY=WE")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup.copy(exceptionDates = "2026-07-01")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = july(standup).copy(observedOccurrenceDates = mapOf("standup" to setOf("2026-07-01", "2026-07-08")))
        w.refresher.refresh(mirror.mirrorId)

        assertTrue(w.july(mirror.calendarId).any { it.startLocal.date == LocalDate(2026, 7, 1) }, "restored on the margin")
    }

    /**
     * ⚠⚠ The window moving on is a change even when the events are not: dates enter the safe sweep
     * span and must be reconciled, not skipped on a matching digest.
     */
    @Test
    fun an_advancing_window_is_reconciled_even_with_unchanged_events() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("kept", start = "2026-07-20T09:00"), event("edge", start = "2026-07-31T09:00")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        // Deleted at the source, but on the margin: kept for now.
        w.reader.snapshot = july(event("kept", start = "2026-07-20T09:00"))
        w.refresher.refreshAll()
        w.clock.advance(MirrorRefresher.STAMP_INTERVAL_MS * 30)
        assertEquals(setOf("kept", "edge"), w.july(mirror.calendarId).map { it.title }.toSet())

        // A day later the same events, a window one day on: now "edge" is certainly inside.
        val day = 24L * 60 * 60 * 1000
        val moved = w.reader.snapshot.window!!.let { SnapshotWindow(it.startMs + day, it.endMs + day) }
        w.reader.snapshot = w.reader.snapshot.copy(window = moved)
        w.refresher.refreshAll()

        assertEquals(listOf("kept"), w.july(mirror.calendarId).map { it.title }, "swept once covered")
    }

    /**
     * ⚠⚠ A move INTO the window that the read no longer returns was cancelled: the move goes, and
     * its original slot (outside the window) does not come back.
     */
    @Test
    fun a_move_into_the_window_that_vanished_is_a_cancellation() = runTest {
        val standup = event("standup", start = "2026-06-03T09:00", rule = "FREQ=WEEKLY;BYDAY=WE")
        val movedIn = event("standup", start = "2026-07-10T09:00", recurrenceId = "2026-06-24T09:00")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup, movedIn))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = july(standup)
        w.refresher.refresh(mirror.mirrorId)

        val summer = w.repo.occurrences(LocalDate(2026, 6, 1), LocalDate(2026, 7, 31)).filter { it.calendarId == mirror.calendarId }
        assertTrue(summer.none { it.startLocal.toString() == "2026-07-10T09:00" }, "the cancelled move is gone")
        assertTrue(summer.none { it.startLocal.date == LocalDate(2026, 6, 24) }, "and its slot stays empty")
    }

    /** ⚠ A move within the window, restored to its slot, is shown there — not taken as cancelled. */
    @Test
    fun a_move_within_the_window_restored_to_its_slot_is_shown() = runTest {
        val standup = event("standup", start = "2026-06-03T09:00", rule = "FREQ=WEEKLY;BYDAY=WE")
        val moved = event("standup", start = "2026-07-16T09:00", recurrenceId = "2026-07-15T09:00")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup, moved))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = july(standup).copy(
            observedOccurrenceDates = mapOf("standup" to setOf("2026-07-01", "2026-07-08", "2026-07-15", "2026-07-22", "2026-07-29")),
        )
        w.refresher.refresh(mirror.mirrorId)

        val july = w.july(mirror.calendarId)
        assertTrue(july.any { it.startLocal.toString() == "2026-07-15T09:00" }, "back in its slot")
        assertTrue(july.none { it.startLocal.toString() == "2026-07-16T09:00" }, "the move is gone")
    }

    /**
     * ⚠⚠ A recurring series absent from a windowed read, with occurrences outside the window: its
     * covered occurrences are cancelled, and its history survives — never the whole series swept.
     */
    @Test
    fun an_absent_series_reaching_beyond_the_window_is_cancelled_inside_it_not_swept() = runTest {
        // ⚠ 8 July already cancelled: it must stay cancelled through the partial cancel.
        val standup = event("standup", start = "2026-06-03T09:00", rule = "FREQ=WEEKLY;BYDAY=WE")
            .copy(exceptionDates = "2026-07-08")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup, event("other", start = "2026-07-20T09:00")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = july(event("other", start = "2026-07-20T09:00"))
        val result = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refresh(mirror.mirrorId)).result

        assertEquals(0, result.deleted)
        assertTrue(w.june(mirror.calendarId).any { it.title == "standup" }, "June survives")
        val july = w.july(mirror.calendarId).filter { it.title == "standup" }.map { it.startLocal.date.toString() }
        assertEquals(listOf("2026-07-01"), july, "covered slots cancelled; the margin day kept")

        // Stable: a second refresh changes nothing.
        val again = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refresh(mirror.mirrorId)).result
        assertEquals(0, again.written)
    }

    /**
     * ⚠⚠ A series the reader could not convert this time is still at the source: the sweep must
     * spare it (and its overrides, sidecar and reminders). Once it is really gone, it goes.
     */
    @Test
    fun a_series_the_reader_could_not_convert_is_not_swept() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a"), event("b")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = ExternalSnapshot(listOf(event("a", title = "A renamed")), skipped = 1, unreadableUids = setOf("b"))
        val partial = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refresh(mirror.mirrorId)).result
        assertEquals(0, partial.deleted)
        assertEquals(setOf("A renamed", "b"), w.july(mirror.calendarId).map { it.title }.toSet())

        w.reader.snapshot = snapshot(event("a", title = "A renamed"))
        val later = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refresh(mirror.mirrorId)).result
        assertEquals(1, later.deleted, "deleted at the source for real")
    }

    /** ⚠⚠ The flag every writable-calendar list filters on (`CalendarSummary.readOnly`). */
    @Test
    fun a_mirrored_calendar_reads_as_read_only() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        w.repo.ensureDefaultCalendar()
        val mirror = w.start()

        val byId = w.repo.calendars().associate { it.calendarId to it.readOnly }
        assertEquals(true, byId[mirror.calendarId])
        assertTrue(byId.filterKeys { it != mirror.calendarId }.values.none { it }, "the user's own stay writable")
        assertEquals(true, w.repo.observeCalendars().first { list -> list.any { it.readOnly } }
            .single { it.calendarId == mirror.calendarId }.readOnly)
    }

    /**
     * ⚠⚠ *Stop syncing (keep events)* leaves an editable calendar under the derived id. Starting
     * the mirror again must not fill — and sweep — that calendar: it gets one of its own.
     */
    @Test
    fun restarting_a_stopped_mirror_never_sweeps_the_calendar_it_left_behind() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a"), event("b")))
        val first = w.start()
        w.refresher.refresh(first.mirrorId)
        w.repo.stopMirror(first.mirrorId)

        w.reader.snapshot = snapshot(event("a"))
        val second = w.start()
        w.refresher.refresh(second.mirrorId)

        assertNotEquals(first.calendarId, second.calendarId)
        assertEquals(setOf("a", "b"), w.july(first.calendarId).map { it.title }.toSet(), "the kept calendar is untouched")
        assertEquals(listOf("a"), w.july(second.calendarId).map { it.title })
        assertEquals(false, w.repo.calendars().single { it.calendarId == first.calendarId }.readOnly)
    }

    /**
     * ⚠⚠ *Stop syncing* while a refresh sits in the provider read: the calendar is editable from
     * that moment, so the refresh must not write or sweep it when the read comes back.
     */
    @Test
    fun a_refresh_in_flight_when_syncing_stops_writes_nothing() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a"), event("b")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = snapshot(event("a", title = "A at the source"))
        val gate = CompletableDeferred<Unit>().also { w.reader.gate = it }
        val inFlight = async { w.refresher.refresh(mirror.mirrorId) }
        w.reader.entered.await()
        w.repo.stopMirror(mirror.mirrorId)
        gate.complete(Unit)
        inFlight.await()

        assertEquals(setOf("a", "b"), w.july(mirror.calendarId).map { it.title }.toSet(), "no write, no sweep")
    }

    /**
     * ⚠⚠ A moved occurrence whose row is unreadable this time stays moved: the series is resent
     * with its stored override, and only a complete read can move it back.
     */
    @Test
    fun an_unreadable_moved_occurrence_stays_moved_until_a_complete_read() = runTest {
        val standup = event("standup", rule = "FREQ=WEEKLY;BYDAY=WE")
        val moved = event("standup", start = "2026-07-08T11:00", recurrenceId = "2026-07-08T09:00")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup, moved))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        // The series changes at the source, and the moved occurrence cannot be read this time.
        w.reader.snapshot = ExternalSnapshot(
            listOf(standup.copy(title = "Daily sync")),
            skipped = 1,
            incompleteOverridesFor = setOf("standup"),
        )
        w.refresher.refresh(mirror.mirrorId)
        val july = w.july(mirror.calendarId)
        assertTrue(july.any { it.startLocal.toString() == "2026-07-08T11:00" }, "still moved")
        assertTrue(july.any { it.title == "Daily sync" }, "and the series change landed")

        w.reader.snapshot = snapshot(standup.copy(title = "Daily sync"))
        w.refresher.refresh(mirror.mirrorId)
        assertTrue(w.july(mirror.calendarId).none { it.startLocal.toString() == "2026-07-08T11:00" }, "moved back once read in full")
    }

    /** ⚠⚠ The same for a *cancelled* occurrence, which lives in the series' `exceptionDates`. */
    @Test
    fun an_unreadable_cancellation_stays_cancelled_until_a_complete_read() = runTest {
        val standup = event("standup", rule = "FREQ=WEEKLY;BYDAY=WE")
        val w = world(createCalendarProxy(backgroundScope), snapshot(standup.copy(exceptionDates = "2026-07-15")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)
        suspend fun on15th() = w.july(mirror.calendarId).any { it.startLocal.date.toString() == "2026-07-15" }
        assertFalse(on15th())

        w.reader.snapshot = ExternalSnapshot(
            listOf(standup.copy(title = "Daily sync")),
            skipped = 1,
            incompleteOverridesFor = setOf("standup"),
        )
        w.refresher.refresh(mirror.mirrorId)
        assertFalse(on15th(), "still cancelled")
        assertTrue(w.july(mirror.calendarId).any { it.title == "Daily sync" })

        w.reader.snapshot = snapshot(standup.copy(title = "Daily sync"))
        w.refresher.refresh(mirror.mirrorId)
        assertTrue(on15th(), "back once the source is read in full without it")
    }

    /**
     * ⚠⚠ *Stop Tenshu reminders* from a device the mirror is NOT bound to takes effect there and
     * then — the bound phone may never refresh again, and only it can.
     */
    @Test
    fun turning_reminders_off_from_another_device_removes_them_at_once() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a", reminders = listOf(10, 60))))
        val mirror = w.start(reminders = true)
        w.refresher.refresh(mirror.mirrorId)
        val eventId = w.july(mirror.calendarId).single().eventId
        assertEquals(listOf(10, 60), w.repo.remindersFor(eventId).sorted())

        val laptop = TenshuCalendarRepository(
            client = w.proxy.asTenshuClient(),
            deviceIdentity = CalendarDeviceIdentity { "device-b" },
            reachability = null /* test: no reachability engine */,
        )
        assertFalse(laptop.mirrors().single().boundHere)
        laptop.setMirrorReminders(mirror.mirrorId, enabled = false)

        assertTrue(w.repo.remindersFor(eventId).isEmpty(), "no refresh needed")
    }

    /** ⚠⚠ Reminders turned off while a refresh sits in the read stay off when it resumes. */
    @Test
    fun reminders_turned_off_mid_refresh_are_not_written_back() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a", reminders = listOf(10))))
        val mirror = w.start(reminders = true)
        w.refresher.refresh(mirror.mirrorId)
        val eventId = w.july(mirror.calendarId).single().eventId

        w.reader.snapshot = snapshot(event("a", title = "A changed", reminders = listOf(10)))
        val gate = CompletableDeferred<Unit>().also { w.reader.gate = it }
        val inFlight = async { w.refresher.refresh(mirror.mirrorId) }
        w.reader.entered.await()
        w.repo.setMirrorReminders(mirror.mirrorId, enabled = false)
        gate.complete(Unit)
        inFlight.await()

        assertTrue(w.july(mirror.calendarId).single().title == "A changed", "the refresh did land")
        assertTrue(w.repo.remindersFor(eventId).isEmpty(), "but not the stale reminder switch")
    }

    /** ⚠ A mirrored calendar deleted while a refresh sits in the read gets nothing written into it. */
    @Test
    fun a_calendar_deleted_mid_refresh_gets_no_events_back() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = snapshot(event("a"), event("b"))
        val gate = CompletableDeferred<Unit>().also { w.reader.gate = it }
        val inFlight = async { w.refresher.refresh(mirror.mirrorId) }
        w.reader.entered.await()
        w.repo.deleteCalendar(mirror.calendarId)
        gate.complete(Unit)
        inFlight.await()

        assertTrue(w.july(mirror.calendarId).isEmpty())
    }

    /** ⚠ Two partial reads with the same events but different gaps are different snapshots. */
    @Test
    fun the_digest_tells_partial_reads_apart_by_what_was_unreadable() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        val base = ExternalSnapshot(listOf(event("a")), skipped = 1)
        val digests = listOf(
            base.copy(unreadableUids = setOf("b")),
            base.copy(unreadableUids = setOf("c")),
            base.copy(incompleteOverridesFor = setOf("a")),
            base,
        ).map { MirrorRefresher.digestOf(mirror, it) }
        assertEquals(digests.size, digests.toSet().size)
        assertEquals(
            MirrorRefresher.digestOf(mirror, base.copy(unreadableUids = setOf("b", "c"))),
            MirrorRefresher.digestOf(mirror, base.copy(unreadableUids = setOf("c", "b"))),
            "and the order of a set does not matter",
        )
    }

    /**
     * ⚠⚠ Two refreshes overlap: an older read must not land after a newer one and roll it back.
     * The second waits for the first, then applies what it read.
     */
    @Test
    fun an_older_read_never_lands_after_a_newer_one() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = snapshot(event("a", title = "older"))
        val gate = CompletableDeferred<Unit>().also { w.reader.gate = it }
        val older = async { w.refresher.refresh(mirror.mirrorId) }
        w.reader.entered.await()

        w.reader.gate = null
        w.reader.snapshot = snapshot(event("a", title = "newer"))
        val newer = async { w.refresher.refresh(mirror.mirrorId) }
        gate.complete(Unit)
        older.await()
        newer.await()

        assertEquals(listOf("newer"), w.july(mirror.calendarId).map { it.title })
    }

    /** ⚠ Deleting the default leaves no read-only calendar as the new one. */
    @Test
    fun deleting_the_default_never_makes_a_mirror_the_default() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        w.repo.ensureDefaultCalendar()
        val personal = w.repo.defaultCalendar()
        w.start()

        w.repo.deleteCalendar(personal)

        assertEquals("", w.repo.defaultCalendar(), "blank, not the mirror")
    }

    /** ⚠⚠ Off then on from another device: the bound phone's next background refresh restores them. */
    @Test
    fun reminders_switched_off_and_on_elsewhere_come_back_on_the_next_background_refresh() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a", reminders = listOf(10))))
        val mirror = w.start(reminders = true)
        w.refresher.refresh(mirror.mirrorId)
        val eventId = w.july(mirror.calendarId).single().eventId
        assertIs<MirrorRefreshOutcome.Unchanged>(w.refresher.refreshAll().single(), "the digest holds")

        val laptop = TenshuCalendarRepository(
            client = w.proxy.asTenshuClient(),
            deviceIdentity = CalendarDeviceIdentity { "device-b" },
            reachability = null /* test: no reachability engine */,
        )
        laptop.setMirrorReminders(mirror.mirrorId, enabled = false)
        laptop.setMirrorReminders(mirror.mirrorId, enabled = true)
        assertTrue(w.repo.remindersFor(eventId).isEmpty())

        assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refreshAll().single(), "not skipped by the digest")
        assertEquals(listOf(10), w.repo.remindersFor(eventId))
    }

    /** ⚠ *Keep in sync* again with reminders unchecked removes them now, not at a refresh. */
    @Test
    fun rebinding_with_reminders_off_removes_them_at_once() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a", reminders = listOf(10))))
        val mirror = w.start(reminders = true)
        w.refresher.refresh(mirror.mirrorId)
        val eventId = w.july(mirror.calendarId).single().eventId
        assertEquals(listOf(10), w.repo.remindersFor(eventId))

        w.start(reminders = false)

        assertTrue(w.repo.remindersFor(eventId).isEmpty())
    }

    /**
     * ⚠⚠ A device whose sync id never arrives: the start fails after a bound wait, and it never
     * held the mirror lock — so a stop or a calendar delete behind it still completes.
     */
    @Test
    fun a_start_without_a_device_id_fails_without_holding_the_mirror_lock() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val stuck = TenshuCalendarRepository(
            client = proxy.asTenshuClient(),
            deviceIdentity = CalendarDeviceIdentity { null },   // "not yet", forever
            reachability = null /* test: no reachability engine */,
        )
        val start = async { runCatching { stuck.startMirror(source, remindersEnabled = false) } }
        stuck.ensureDefaultCalendar()
        stuck.deleteCalendar(stuck.defaultCalendar())   // takes the mirror lock

        assertTrue(start.await().isFailure, "fails rather than hanging")
    }

    /** ⚠ A change only the sidecar carries (`RDATE`, `EXRULE`) is still a change. */
    @Test
    fun a_change_only_in_the_source_sidecar_is_written() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a").copy(raw = "RDATE:20260710T090000")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = snapshot(event("a").copy(raw = "RDATE:20260717T090000"))
        val changed = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refresh(mirror.mirrorId)).result
        assertEquals(1, changed.written)

        val again = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refresh(mirror.mirrorId)).result
        assertEquals(0, again.written, "and then it is in step")
    }

    /** ⚠⚠ The point of the digest and the diff: no change at the source, no writes here. */
    @Test
    fun an_unchanged_source_writes_nothing() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a"), event("b")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        assertIs<MirrorRefreshOutcome.Unchanged>(w.refresher.refreshAll().single(), "the digest skips it")
        val forced = assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refresh(mirror.mirrorId)).result
        assertEquals(0, forced.written, "and a forced refresh diffs to nothing")
        assertEquals(2, forced.unchanged)
    }

    /** ⚠⚠ An empty read never empties a mirror — the provider is briefly empty during a re-sync. */
    @Test
    fun an_empty_read_is_not_applied() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.snapshot = snapshot()
        assertIs<MirrorRefreshOutcome.NotApplied>(w.refresher.refreshAll().single())
        assertEquals(1, w.july(mirror.calendarId).size)
    }

    @Test
    fun a_source_whose_sync_is_off_or_that_vanished_is_left_alone() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.reader.calendars = listOf(source.copy(holdsEvents = false))
        assertIs<MirrorRefreshOutcome.NotApplied>(w.refresher.refreshAll().single())

        w.reader.calendars = emptyList()
        assertIs<MirrorRefreshOutcome.SourceGone>(w.refresher.refreshAll().single())
        assertEquals(1, w.july(mirror.calendarId).size, "an account removed from the phone deletes nothing")
    }

    /** ⚠⚠ The binding is the election: a mirror bound to another device is never refreshed here. */
    @Test
    fun a_mirror_bound_to_another_device_is_skipped() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        environmentFor(w.proxy).getService<CalendarService>(CalendarService.ID).saveCalendarMirror(
            mirror.mirrorId, mirror.calendarId, "device-b", mirror.sourceKind, mirror.sourceKey,
            mirror.sourceLabel, false,
        )

        assertEquals(emptyList(), w.refresher.refreshAll())
        assertEquals(0, w.reader.reads)
        assertEquals(false, w.repo.mirrors().single().boundHere)
    }

    /** Q6: choosing *Keep in sync* again here takes the mirror over, calendar and all. */
    @Test
    fun starting_again_on_this_device_rebinds_the_same_mirror() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        environmentFor(w.proxy).getService<CalendarService>(CalendarService.ID).saveCalendarMirror(
            mirror.mirrorId, mirror.calendarId, "device-b", mirror.sourceKind, mirror.sourceKey,
            mirror.sourceLabel, false,
        )

        val again = w.start()

        assertEquals(mirror.mirrorId, again.mirrorId)
        assertEquals(mirror.calendarId, again.calendarId)
        assertEquals(listOf(true), w.repo.mirrors().map { it.boundHere })
    }

    /** ⚠ §5.7: the source's app already reminds, so Tenshu adds none until asked. */
    @Test
    fun reminders_follow_the_switch_not_the_source() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a", reminders = listOf(10))))
        val mirror = w.start(reminders = false)
        w.refresher.refresh(mirror.mirrorId)
        val eventId = w.july(mirror.calendarId).single().eventId
        assertEquals(emptyList(), w.repo.remindersFor(eventId))

        w.repo.setMirrorReminders(mirror.mirrorId, enabled = true)
        assertIs<MirrorRefreshOutcome.Synced>(w.refresher.refreshAll().single(), "the switch changes the digest")
        assertEquals(listOf(10), w.repo.remindersFor(eventId))

        w.repo.setMirrorReminders(mirror.mirrorId, enabled = false)
        w.refresher.refreshAll()
        assertEquals(emptyList(), w.repo.remindersFor(eventId))
    }

    /** ⚠ The stamp is a synced row: an unchanged refresh re-stamps at most hourly. */
    @Test
    fun an_unchanged_refresh_stamps_at_most_hourly() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)
        val stamped = w.repo.mirrors().single().lastRefreshedEpochMs
        assertEquals(w.clock.now, stamped)

        w.clock.advance(10 * 60_000L)
        w.refresher.refreshAll()
        assertEquals(stamped, w.repo.mirrors().single().lastRefreshedEpochMs, "ten minutes: no write")

        w.clock.advance(MirrorRefresher.STAMP_INTERVAL_MS)
        w.refresher.refreshAll()
        assertEquals(w.clock.now, w.repo.mirrors().single().lastRefreshedEpochMs)
    }

    /**
     * ⚠⚠ P2-a: a stamp writes the device's own stamp row and **never the mirror row** — a re-save of
     * that shared row from a phone that missed a stop was a newer block than the stop's tombstone,
     * and brought the mirror back. The two-device proof is `CalendarMirrorStopSystemTest`; this pins
     * the single-device half: the mirror row is untouched, and the freshness reads back.
     */
    @Test
    fun a_stamp_never_rewrites_the_mirror_row() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        val saved = mutableListOf<String>()
        w.proxy.executionContext.addDataObserver(object : DataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin) {
                (data as? Composite)?.schema?.id?.get()?.takeIf { it.startsWith("tenshu/calendar/") }?.let { saved += it }
            }
            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit
        })

        w.repo.stampMirror(mirror.mirrorId, 5_000L)

        assertEquals(5_000L, w.repo.mirrors().single().lastRefreshedEpochMs)
        assertEquals(listOf(CalendarMirrorStamp.ID), saved, "the stamp row only — never the shared mirror row")
    }

    /**
     * ⚠ Stamps are per device and only the **bound** device's is read: a phone rebound away cannot
     * stamp (it is no longer bound here), and a row another device left is not this binding's
     * freshness.
     */
    @Test
    fun only_the_bound_devices_stamp_is_its_freshness() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        w.repo.stampMirror(mirror.mirrorId, 5_000L)
        val service = environmentFor(w.proxy).getService<CalendarService>(CalendarService.ID)
        service.saveCalendarMirror(
            mirror.mirrorId, mirror.calendarId, "device-b", mirror.sourceKind, mirror.sourceKey,
            mirror.sourceLabel, false,
        )
        assertEquals(0L, w.repo.mirrors().single().lastRefreshedEpochMs, "device-b has not refreshed yet")

        w.repo.stampMirror(mirror.mirrorId, 9_000L)
        assertEquals(0L, w.repo.mirrors().single().lastRefreshedEpochMs, "a device rebound away writes nothing")

        service.saveCalendarMirrorStamp(mirror.mirrorId, "device-b", 7_000L)
        assertEquals(7_000L, w.repo.mirrors().single().lastRefreshedEpochMs)
    }

    /** ⚠ Starting again here resets this device's stamp: an old row must not claim this binding is fresh. */
    @Test
    fun starting_a_mirror_resets_this_devices_stamp() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        w.repo.stampMirror(mirror.mirrorId, 5_000L)

        w.start()

        assertEquals(0L, w.repo.mirrors().single().lastRefreshedEpochMs)
    }

    @Test
    fun stopping_the_sync_keeps_the_events_and_frees_the_calendar() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val mirror = w.start()
        w.refresher.refresh(mirror.mirrorId)

        w.repo.stopMirror(mirror.mirrorId)

        assertEquals(emptyList(), w.repo.mirrors())
        assertEquals(1, w.july(mirror.calendarId).size)
        assertEquals(emptyList(), w.refresher.refreshAll(), "nothing left to refresh")
    }

    /** ⚠ A mirror never lands in the calendar a *Copy once* of the same source made. */
    @Test
    fun a_mirror_and_a_copy_of_one_source_are_different_calendars() = runTest {
        val w = world(createCalendarProxy(backgroundScope), snapshot(event("a")))
        val copy = w.repo.copyExternalCalendar(source, snapshot(event("a")))
        val mirror = w.start()

        assertTrue(copy.calendarId != mirror.calendarId)
    }
}
