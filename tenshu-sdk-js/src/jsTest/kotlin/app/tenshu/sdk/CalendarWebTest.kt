@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import app.tenshu.sdk.internal.CalendarPlugin
import app.tenshu.sdk.internal.JsRuntimeBuilder
import app.tenshu.sdk.internal.calendarWebOperation
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.calendar.DeliverDueReminders
import org.tenshu.calendar.RefreshSubscriptions
import org.tenshu.calendar.client.calendarService
import org.tenshu.calendar.common.ics.IcsImport
import org.tenshu.calendar.common.ics.ImportedEvent
import org.tenshu.calendar.deliverDueReminders
import org.tenshu.calendar.refreshSubscriptions
import org.tenshu.calendar.saveEvent
import org.tenshu.core.GenericError
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.set
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boundary-contract tests (`docs/testing.md` §9.3) for `Calendar` — web-app-suite.md Wave 2,
 * run A — against the **real** in-process runtime: the published row keys and the values native
 * `TenshuCalendarRepository` would produce, not "returns something".
 *
 * ⚠ The jsTest suite shares one `JsRuntime`, so every test makes its own calendar (a unique name)
 * and only ever looks at rows in it.
 */
class CalendarWebTest : SdkRuntimeTest() {

    private fun stamp() = "${Date.now().toLong()}-${(0..99999).random()}"

    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Double = Date.UTC(y, m - 1, d, h, min)

    private suspend fun newCalendar(name: String = "Cal ${stamp()}"): String {
        val r = Calendar.saveCalendar("", name, 0x334285F4, false).await()
        assertEquals(true, r.ok, "saveCalendar must succeed; got ${JSON.stringify(r)}")
        return r.calendarId as String
    }

    private fun draft(
        calendarId: String,
        title: String,
        start: String,
        end: String = "",
        zone: String = "",
        rule: String = "",
        eventId: String = "",
        allDay: Boolean = false,
    ): dynamic {
        val d: dynamic = js("({})")
        d.eventId = eventId
        d.calendarId = calendarId
        d.title = title
        d.startLocal = start
        d.endLocal = end
        d.timeZoneId = zone
        d.allDay = allDay
        d.location = ""
        d.notes = ""
        d.recurrenceRule = rule
        return d
    }

    private suspend fun save(d: dynamic): String {
        val r = Calendar.saveEvent(d).await()
        assertEquals(true, r.ok, "saveEvent must succeed; got ${JSON.stringify(r)}")
        return r.eventId as String
    }

    private suspend fun occurrencesIn(calendarId: String, from: String, to: String, zone: String = "UTC"): List<dynamic> {
        val r = Calendar.occurrences(from, to, false, zone).await()
        assertEquals(true, r.ok, "occurrences must succeed; got ${JSON.stringify(r)}")
        return r.occurrences.unsafeCast<Array<dynamic>>().filter { it.calendarId == calendarId }
    }

    private suspend fun defaultNow(): String {
        val r = Calendar.defaultCalendar().await()
        assertEquals(true, r.ok)
        return r.calendarId as String
    }

    private fun assertKind(expected: String, result: dynamic) {
        assertEquals(false, result.ok, "expected a failure; got ${JSON.stringify(result)}")
        assertEquals(expected, result.error.kind as String)
    }

    /** Polls until [check] holds: a preference write lands through the store's mailbox (CA-2f). */
    private suspend fun eventually(what: String, check: suspend () -> Boolean) {
        var i = 0
        while (i < 100 && !check()) {
            delay(30); i++
        }
        assertTrue(check(), what)
    }

    @Test
    fun aCreatedEvent_appearsInItsWindow_withTheFullPublishedRowShape() = GlobalScope.promise {
        val name = "Create ${stamp()}"
        val calendarId = newCalendar(name)
        val cal = Calendar.calendars().await().calendars.unsafeCast<Array<dynamic>>()
            .firstOrNull { it.calendarId == calendarId }
        assertNotNull(cal, "a created calendar must appear in calendars()")
        assertEquals(name, cal.name)
        assertEquals(0x334285F4, cal.colorArgb)
        assertEquals(false, cal.sensitive)
        assertEquals(true, cal.visible, "nothing is hidden until the user hides it")

        val eventId = save(draft(calendarId, "Dentist", "2026-03-10T09:00", "2026-03-10T10:00", "Europe/Berlin"))
        val row = occurrencesIn(calendarId, "2026-03-10", "2026-03-10").single()
        assertEquals(eventId, row.eventId)
        assertEquals("Dentist", row.title)
        assertEquals("2026-03-10T09:00", row.startLocal)
        assertEquals("2026-03-10T10:00", row.endLocal)
        assertEquals("Europe/Berlin", row.zoneId)
        assertEquals("2026-03-10T09:00", row.recurrenceId)
        assertEquals(false, row.allDay)
        assertEquals(false, row.isOverridden)
        assertEquals(false, row.isRecurring)
        assertEquals("", row.recurrenceRule)
        assertEquals("", row.location)
        // 09:00 CET (UTC+1) is 08:00Z — resolved in the event's zone, not the reader's.
        assertEquals(utc(2026, 3, 10, 8), row.startEpochMs)
        assertEquals(utc(2026, 3, 10, 9), row.endEpochMs)

        assertTrue(occurrencesIn(calendarId, "2026-03-11", "2026-03-31").isEmpty(), "outside its window it is absent")
    }

    @Test
    fun aWeeklyRule_acrossTheAutumnDstChange_keepsTheWallClock_andMovesTheInstant() = GlobalScope.promise {
        val calendarId = newCalendar()
        // Monday 2026-10-19; Europe/Berlin leaves CEST (UTC+2) for CET (UTC+1) on 2026-10-25.
        save(draft(calendarId, "Standup", "2026-10-19T09:00", "2026-10-19T09:15", "Europe/Berlin", "FREQ=WEEKLY;COUNT=3"))
        val rows = occurrencesIn(calendarId, "2026-10-01", "2026-11-30")
        assertEquals(
            listOf("2026-10-19T09:00", "2026-10-26T09:00", "2026-11-02T09:00"),
            rows.map { it.startLocal as String },
            "a weekly rule repeats the civil time, not the instant",
        )
        assertEquals(listOf(true, true, true), rows.map { it.isRecurring as Boolean })
        assertEquals(
            listOf(utc(2026, 10, 19, 7), utc(2026, 10, 26, 8), utc(2026, 11, 2, 8)),
            rows.map { it.startEpochMs as Double },
            "the instant shifts by the DST hour; a UTC-only zone database would fail here",
        )
        // The same rows read by a reader in New York: a zoned event does not move with the reader.
        assertEquals(
            rows.map { it.startEpochMs as Double },
            occurrencesIn(calendarId, "2026-10-01", "2026-11-30", "America/New_York").map { it.startEpochMs as Double },
        )
    }

    @Test
    fun aFloatingEvent_isPlacedInTheReadersZone_acrossItsSpringChange() = GlobalScope.promise {
        val calendarId = newCalendar()
        // Floating, weekly over New York's spring-forward (2026-03-08, EST −5 → EDT −4).
        save(draft(calendarId, "Gym", "2026-03-02T18:00", "2026-03-02T19:00", "", "FREQ=WEEKLY;COUNT=2"))
        val rows = occurrencesIn(calendarId, "2026-03-01", "2026-03-31", "America/New_York")
        assertEquals(listOf(utc(2026, 3, 2, 23), utc(2026, 3, 9, 22)), rows.map { it.startEpochMs as Double })
        assertEquals("", rows.first().zoneId)
    }

    @Test
    fun editingOneOccurrence_leavesTheSeries_andASeriesEdit_leavesTheOverride() = GlobalScope.promise {
        val calendarId = newCalendar()
        val eventId = save(draft(calendarId, "Weekly", "2026-05-04T09:00", "2026-05-04T10:00", "Europe/Berlin", "FREQ=WEEKLY;COUNT=3"))

        val moved = Calendar.saveOccurrence(
            draft(calendarId, "Moved", "2026-05-11T11:00", "2026-05-11T12:00", "Europe/Berlin", eventId = eventId),
            "2026-05-11T09:00",
        ).await()
        assertEquals(true, moved.ok, "saveOccurrence must succeed; got ${JSON.stringify(moved)}")

        val rows = occurrencesIn(calendarId, "2026-05-01", "2026-05-31")
        assertEquals(listOf("Weekly", "Moved", "Weekly"), rows.map { it.title as String })
        assertEquals(listOf(false, true, false), rows.map { it.isOverridden as Boolean })
        assertEquals("2026-05-11T11:00", rows[1].startLocal)
        assertEquals("2026-05-11T09:00", rows[1].recurrenceId, "the handle stays the ORIGINAL start")

        // The series edit renames every occurrence the override does not speak for.
        save(draft(calendarId, "Renamed", "2026-05-04T09:00", "2026-05-04T10:00", "Europe/Berlin", "FREQ=WEEKLY;COUNT=3", eventId = eventId))
        assertEquals(
            listOf("Renamed", "Moved", "Renamed"),
            occurrencesIn(calendarId, "2026-05-01", "2026-05-31").map { it.title as String },
        )
    }

    @Test
    fun deletingAnOccurrence_skipsIt_survivesASeriesSave_andDeletingTheEventRemovesAll() = GlobalScope.promise {
        val calendarId = newCalendar()
        val eventId = save(draft(calendarId, "Lesson", "2026-06-01T17:00", "2026-06-01T18:00", "", "FREQ=WEEKLY;COUNT=3"))
        assertEquals(true, Calendar.deleteOccurrence(eventId, "2026-06-08T17:00").await().ok)
        assertEquals(
            listOf("2026-06-01T17:00", "2026-06-15T17:00"),
            occurrencesIn(calendarId, "2026-06-01", "2026-06-30").map { it.startLocal as String },
        )

        // ⚠⚠ An editor save carries no exception list: the skipped date must survive it.
        save(draft(calendarId, "Lesson 2", "2026-06-01T17:00", "2026-06-01T18:00", "", "FREQ=WEEKLY;COUNT=3", eventId = eventId))
        assertEquals(2, occurrencesIn(calendarId, "2026-06-01", "2026-06-30").size)

        assertEquals(true, Calendar.deleteEvent(eventId).await().ok)
        assertTrue(occurrencesIn(calendarId, "2026-06-01", "2026-06-30").isEmpty())
        assertEquals(true, Calendar.deleteEvent(eventId).await().ok, "a delete of a gone event is idempotent")
    }

    @Test
    fun anIcsFileWithTwoEvents_importsTwo_atTheirTimes() = GlobalScope.promise {
        val calendarId = newCalendar()
        val s = stamp()
        val text = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN",
            "BEGIN:VEVENT", "UID:a-$s", "SUMMARY:Review", "DTSTART;TZID=Europe/Berlin:20261103T140000",
            "DTEND;TZID=Europe/Berlin:20261103T150000", "END:VEVENT",
            "BEGIN:VEVENT", "UID:b-$s", "SUMMARY:Flight", "DTSTART:20261104T063000",
            "DTEND:20261104T090000", "LOCATION:BER", "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n", postfix = "\r\n")

        val r = Calendar.importIcs(text, calendarId, "test.ics").await()
        assertEquals(true, r.ok, "import must succeed; got ${JSON.stringify(r)}")
        assertEquals(2, r.imported)
        assertEquals(0, r.overrides)
        assertEquals(0, r.skipped)
        assertEquals(0, r.failed)

        val rows = occurrencesIn(calendarId, "2026-11-01", "2026-11-30")
        assertEquals(listOf("$calendarId~a-$s", "$calendarId~b-$s"), rows.map { it.eventId as String }, "the UID, namespaced by its calendar")
        assertEquals("2026-11-03T14:00", rows[0].startLocal)
        assertEquals("Europe/Berlin", rows[0].zoneId)
        assertEquals(utc(2026, 11, 3, 13), rows[0].startEpochMs)
        assertEquals("2026-11-04T06:30", rows[1].startLocal)
        assertEquals("", rows[1].zoneId, "no TZID is floating")
        assertEquals("BER", rows[1].location)

        // Re-importing the same file replaces, never duplicates.
        assertEquals(2, Calendar.importIcs(text, calendarId, "test.ics").await().imported)
        assertEquals(2, occurrencesIn(calendarId, "2026-11-01", "2026-11-30").size)
    }

    /** A weekly series with one moved occurrence, under [uid]. */
    private fun meetingFile(uid: String) = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN",
        "BEGIN:VEVENT", "UID:$uid", "SUMMARY:Sync", "DTSTART:20261102T100000", "DTEND:20261102T110000",
        "RRULE:FREQ=WEEKLY;COUNT=3", "END:VEVENT",
        "BEGIN:VEVENT", "UID:$uid", "RECURRENCE-ID:20261109T100000", "SUMMARY:Sync (moved)",
        "DTSTART:20261109T120000", "DTEND:20261109T130000", "END:VEVENT",
        "END:VCALENDAR",
    ).joinToString("\r\n", postfix = "\r\n")

    /**
     * ⚠⚠ An export carries the file's `UID`, never our namespaced id (§7) — for the series and its
     * override alike. Leaking `work~meet` would unmatch the invite from its meeting everywhere else,
     * and re-import elsewhere as `home~work~meet`.
     */
    @Test
    fun anExportCarriesTheImportedUid_notTheNamespacedId() = GlobalScope.promise {
        val calendarId = newCalendar()
        val uid = "meet-${stamp()}@corp"
        assertEquals(true, Calendar.importIcs(meetingFile(uid), calendarId, "test.ics").await().ok)

        val exported = Calendar.exportIcs(calendarId).await().ics as String
        val events = IcsImport.read(exported).events
        assertEquals(2, events.size, exported)
        assertEquals(listOf(uid, uid), events.map { it.uid }, "series and override keep the file's UID")
        assertTrue("~" !in exported.lines().filter { it.startsWith("UID") }.joinToString(), exported)
    }

    /**
     * ⚠⚠ Through `Calendar.importIcs` itself (§7): one file imported into two calendars leaves both
     * holding it. Keyed by bare `UID`, the second import moved the events out of the first.
     */
    @Test
    fun importingOneFileIntoTwoCalendars_leavesBothHoldingIt() = GlobalScope.promise {
        val a = newCalendar()
        val b = newCalendar()
        val file = meetingFile("shared-${stamp()}@corp")
        assertEquals(true, Calendar.importIcs(file, a, "test.ics").await().ok)
        assertEquals(true, Calendar.importIcs(file, b, "test.ics").await().ok)

        val expected = listOf("Sync", "Sync (moved)", "Sync")
        assertEquals(expected, occurrencesIn(a, "2026-11-01", "2026-11-30").map { it.title as String })
        assertEquals(expected, occurrencesIn(b, "2026-11-01", "2026-11-30").map { it.title as String })
    }

    /**
     * ⚠ …and `importIcs` reads what is already in the target: a row stored under the bare `UID`
     * before §7 is re-imported in place, not doubled beside a namespaced twin. (Only the real
     * `eventRows()` read can make this pass — an empty set would namespace and double it.)
     */
    @Test
    fun importingOntoABareUidRowFromBeforeNamespacing_updatesItInPlace() = GlobalScope.promise {
        val calendarId = newCalendar()
        val uid = "legacy-${stamp()}@corp"
        save(draft(calendarId, "Old sync", "2026-11-02T10:00", "2026-11-02T11:00", rule = "FREQ=WEEKLY;COUNT=3", eventId = uid))

        assertEquals(true, Calendar.importIcs(meetingFile(uid), calendarId, "test.ics").await().ok)

        val rows = occurrencesIn(calendarId, "2026-11-01", "2026-11-30")
        assertEquals(listOf("Sync", "Sync (moved)", "Sync"), rows.map { it.title as String })
        assertEquals(setOf(uid), rows.mapTo(mutableSetOf()) { it.eventId as String })
    }

    @Test
    fun icsWithNoEvent_isTheNoneInFileError() = GlobalScope.promise {
        val calendarId = newCalendar()
        assertKind("error_calendar_none_in_file", Calendar.importIcs("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", calendarId, "").await())
    }

    @Test
    fun anExport_roundTripsThroughImport_includingTheRule_theOverride_andReminders() = GlobalScope.promise {
        val source = newCalendar()
        val eventId = save(draft(source, "Choir", "2026-09-01T19:00", "2026-09-01T21:00", "Europe/Berlin", "FREQ=WEEKLY;COUNT=3"))
        assertEquals(true, Calendar.saveOccurrence(
            draft(source, "Choir (hall B)", "2026-09-08T19:30", "2026-09-08T21:30", "Europe/Berlin", eventId = eventId),
            "2026-09-08T19:00",
        ).await().ok)
        assertEquals(true, Calendar.setReminders(eventId, arrayOf(30)).await().ok)

        val exported = Calendar.exportIcs(source).await()
        assertEquals(true, exported.ok, "export must succeed; got ${JSON.stringify(exported)}")
        val ics = exported.ics as String
        assertTrue(ics.contains("BEGIN:VCALENDAR") && ics.contains("RRULE:"), "a calendar with its rule")

        // A fresh calendar to compare: the import lands under its own namespaced id (§7), not the source's.
        assertEquals(true, Calendar.deleteEvent(eventId).await().ok)
        val target = newCalendar()
        val imported = Calendar.importIcs(ics, target, "roundtrip.ics").await()
        assertEquals(1, imported.imported)
        assertEquals(1, imported.overrides)

        val rows = occurrencesIn(target, "2026-09-01", "2026-09-30")
        assertEquals(listOf("Choir", "Choir (hall B)", "Choir"), rows.map { it.title as String })
        assertEquals(listOf("2026-09-01T19:00", "2026-09-08T19:30", "2026-09-15T19:00"), rows.map { it.startLocal as String })
        assertEquals(listOf("Europe/Berlin", "Europe/Berlin", "Europe/Berlin"), rows.map { it.zoneId as String })
        assertEquals(listOf(30), Calendar.remindersFor("$target~$eventId").await().minutes.unsafeCast<Array<Int>>().toList())
    }

    @Test
    fun aMissingId_isNotFound_forWrites_whileDeletesStayIdempotent() = GlobalScope.promise {
        val calendarId = newCalendar()
        val missing = "missing-${stamp()}"
        assertKind("error_calendar_not_found", Calendar.saveOccurrence(draft(calendarId, "x", "2026-01-01T09:00", eventId = missing), "2026-01-01T09:00").await())
        assertKind("error_calendar_not_found", Calendar.deleteOccurrence(missing, "2026-01-01T09:00").await())
        assertKind("error_calendar_not_found", Calendar.remindersFor(missing).await())
        assertKind("error_calendar_not_found", Calendar.setReminders(missing, arrayOf(10)).await())
        assertKind("error_calendar_not_found", Calendar.saveEvent(draft(missing, "x", "2026-01-01T09:00")).await())
        assertKind("error_calendar_not_found", Calendar.importIcs("BEGIN:VEVENT\r\nEND:VEVENT\r\n", missing, "").await())
        assertKind("error_calendar_not_found", Calendar.exportIcs(missing).await())
        assertKind("error_calendar_not_found", Calendar.setDefaultCalendar(missing).await())
        assertKind("error_calendar_not_found", Calendar.setSubscriptionEnabled(missing, false).await())
        assertEquals(true, Calendar.deleteEvent(missing).await().ok)
        assertEquals(true, Calendar.deleteCalendar(missing).await().ok)
    }

    @Test
    fun aDraftThatWouldSaveWrongly_isRefused() = GlobalScope.promise {
        val calendarId = newCalendar()
        assertKind("error_calendar_invalid_rule", Calendar.saveEvent(draft(calendarId, "x", "2026-01-01T09:00", rule = "NOT A RULE")).await())
        assertKind("error_calendar_unknown_zone", Calendar.saveEvent(draft(calendarId, "x", "2026-01-01T09:00", zone = "Mars/Olympus")).await())
        assertKind("error_calendar_invalid_argument", Calendar.saveEvent(draft(calendarId, "x", "next tuesday")).await())
        val partial: dynamic = js("({ title: 'no other keys' })")
        assertKind("error_calendar_invalid_argument", Calendar.saveEvent(partial).await())
        assertKind("error_calendar_invalid_argument", Calendar.occurrences("2026-13-01", "2026-12-31", false, "UTC").await())
        assertKind("error_calendar_unknown_zone", Calendar.occurrences("2026-01-01", "2026-01-31", false, "Mars/Olympus").await())
        assertTrue(occurrencesIn(calendarId, "2026-01-01", "2026-01-31").isEmpty(), "nothing was written")
    }

    @Test
    fun reminders_areASet_andTheDefaultsResolveTheirSentinels() = GlobalScope.promise {
        val calendarId = newCalendar()
        val eventId = save(draft(calendarId, "Remind", "2026-04-01T09:00", "2026-04-01T10:00"))
        assertEquals(true, Calendar.setReminders(eventId, arrayOf(60, 10, 10)).await().ok)
        assertEquals(listOf(10, 60), Calendar.remindersFor(eventId).await().minutes.unsafeCast<Array<Int>>().toList())
        assertEquals(true, Calendar.setReminders(eventId, arrayOf(60)).await().ok)
        assertEquals(listOf(60), Calendar.remindersFor(eventId).await().minutes.unsafeCast<Array<Int>>().toList())
        assertEquals(true, Calendar.setReminders(eventId, emptyArray()).await().ok)
        assertEquals(0, Calendar.remindersFor(eventId).await().minutes.length as Int)
    }

    @Test
    fun theDefaultReminder_isTheStoredValue_withItsSentinelsResolved() = GlobalScope.promise {
        TenshuSdk.awaitReady().await()
        val prefs = JsRuntime.tenshuClient.preferenceService
        // ⚠ Typed locals: a scope function on a `dynamic` compiles to a JS method call.
        suspend fun read(allDay: Boolean): Any? {
            val r: dynamic = Calendar.defaultReminderMinutes(allDay).await()
            assertEquals(true, r.ok as Boolean, "defaultReminderMinutes must succeed; got ${JSON.stringify(r)}")
            val minutes: Any? = r.minutes
            return minutes
        }
        suspend fun timed(): Any? = read(false)
        suspend fun allDay(): Any? = read(true)
        try {
            // The declared defaults: 10 before a timed event, 09:00 on the day (-540) for all-day.
            assertEquals(10, timed())
            assertEquals(-540, allDay())
            prefs.set(CalendarPreferences.DefaultReminderMinutes, 25L)
            eventually("a stored timed default must be read back") { timed() == 25 }
            prefs.set(CalendarPreferences.DefaultReminderMinutes, -1L)
            eventually("-1 is OFF") { timed() == null }
            prefs.set(CalendarPreferences.DefaultReminderMinutes, -30L)
            eventually("a timed negative is off too") { timed() == null }
            prefs.set(CalendarPreferences.DefaultAllDayReminderMinutes, -480L)
            eventually("an all-day negative is minutes after midnight, kept") { allDay() == -480 }
            prefs.set(CalendarPreferences.DefaultAllDayReminderMinutes, -1L)
            eventually("-1 is OFF for all-day as well") { allDay() == null }
        } finally {
            prefs.set(CalendarPreferences.DefaultReminderMinutes, 10L)
            prefs.set(CalendarPreferences.DefaultAllDayReminderMinutes, -540L)
        }
    }

    @Test
    fun aFailedPreferenceRead_isAFailure_neverTheDeclaredDefault() = GlobalScope.promise {
        val error = boom()
        assertKind(error.kind(), defaultReminderResult(false, Read.Failed(error)))
        assertKind(error.kind(), defaultReminderResult(true, Read.Failed(error)))
        assertKind(error.kind(), defaultCalendarResult(Read.Failed(error)))
        assertEquals(15, defaultReminderResult(false, Read.Ok(15L)).minutes)
    }

    @Test
    fun theDefaultCalendar_isSettable_andRepointedWhenItsCalendarIsDeleted() = GlobalScope.promise {
        val a = newCalendar("Default A ${stamp()}")
        assertEquals(true, Calendar.setDefaultCalendar(a).await().ok)
        eventually("the default must become $a") { defaultNow() == a }

        // A blank calendarId lands in the default.
        val eventId = save(draft("", "Unfiled", "2026-02-02T09:00", "2026-02-02T09:30"))
        assertEquals(eventId, occurrencesIn(a, "2026-02-02", "2026-02-02").single().eventId)

        val survivor = Calendar.calendars().await().calendars.unsafeCast<Array<dynamic>>()
            .map { it.calendarId as String }.filter { it != a }.minOrNull().orEmpty()
        assertTrue(survivor.isNotEmpty(), "the suite always has another calendar")
        assertEquals(true, Calendar.deleteCalendar(a).await().ok)
        eventually("a deleted default is re-pointed to the first survivor by id, $survivor") { defaultNow() == survivor }
        assertTrue(occurrencesIn(a, "2026-02-02", "2026-02-02").isEmpty(), "the calendar's events go with it")
    }

    @Test
    fun visibility_isPerBrowser_hidesFromTheWindow_andIsObservable() = GlobalScope.promise {
        val calendarId = newCalendar()
        save(draft(calendarId, "Secret", "2026-07-01T09:00", "2026-07-01T10:00"))

        val pushes = mutableListOf<Array<String>>()
        val stop = Calendar.observeHiddenCalendars({ pushes.add(it.hidden.unsafeCast<Array<String>>()) }, { })
        try {
            eventually("observeHiddenCalendars must emit at once") { pushes.isNotEmpty() }
            val hide = Calendar.setCalendarVisible(calendarId, false).await()
            assertEquals(true, hide.ok, "hide must succeed; got ${JSON.stringify(hide)}")
            assertEquals(true, hide.changed)
            eventually("the hidden set must reach the observer") { pushes.last().contains(calendarId) }
        } finally {
            stop()
        }
        eventually("a hidden calendar leaves the default window") {
            occurrencesIn(calendarId, "2026-07-01", "2026-07-01").isEmpty()
        }
        val all = Calendar.occurrences("2026-07-01", "2026-07-01", true, "UTC").await()
            .occurrences.unsafeCast<Array<dynamic>>().filter { it.calendarId == calendarId }
        assertEquals(1, all.size, "includeHidden still sees it")
        assertEquals(false, Calendar.calendars().await().calendars.unsafeCast<Array<dynamic>>()
            .first { it.calendarId == calendarId }.visible)
        assertEquals(false, Calendar.setCalendarVisible(calendarId, false).await().changed, "a no-op is visible")
        assertEquals(true, Calendar.setCalendarVisible(calendarId, true).await().ok)
    }

    @Test
    fun observeOccurrences_pushesACreatedEvent_hiddenCalendarsIncluded() = GlobalScope.promise {
        val calendarId = newCalendar()
        val pushes = mutableListOf<dynamic>()
        val stop = Calendar.observeOccurrences("2026-08-01", "2026-08-31", "UTC", { pushes.add(it) }, { })
        try {
            eventually("the live window must emit at once") { pushes.isNotEmpty() }
            val eventId = save(draft(calendarId, "Live", "2026-08-12T12:00", "2026-08-12T13:00"))
            eventually("the created event must be pushed") {
                val last: dynamic = pushes.last()
                (last.ok as Boolean) && last.occurrences.unsafeCast<Array<dynamic>>().any { (it.eventId as String) == eventId }
            }
        } finally {
            stop()
        }
    }

    @Test
    fun observeCalendars_pushesANewCalendar() = GlobalScope.promise {
        val pushes = mutableListOf<dynamic>()
        val stop = Calendar.observeCalendars({ pushes.add(it) }, { })
        try {
            val name = "Observed ${stamp()}"
            val calendarId = newCalendar(name)
            eventually("the new calendar must be pushed") {
                if (pushes.isEmpty()) return@eventually false
                val last: dynamic = pushes.last()
                (last.ok as Boolean) && last.calendars.unsafeCast<Array<dynamic>>()
                    .any { (it.calendarId as String) == calendarId && (it.name as String) == name }
            }
        } finally {
            stop()
        }
    }

    @Test
    fun subscriptions_areRowsOnly_httpIsRefused_andTheBrowserNeverFetches() = GlobalScope.promise {
        assertKind("error_calendar_subscription_url", Calendar.addSubscription("http://example.org/plain.ics", "x").await())

        val url = "webcal://example.org/${stamp()}.ics"
        val added = Calendar.addSubscription(url, "Holidays").await()
        assertEquals(true, added.ok, "add must succeed; got ${JSON.stringify(added)}")
        val id = added.subscriptionId as String
        val row = Calendar.subscriptions().await().subscriptions.unsafeCast<Array<dynamic>>().first { it.subscriptionId == id }
        assertEquals("https://" + url.removePrefix("webcal://"), row.url, "webcal is rewritten, never fetched as-is")
        assertEquals(true, row.enabled)
        assertEquals(0.0, row.lastFetchedEpochMs, "never fetched — and nothing in a tab will fetch it")
        assertEquals("$id-cal", row.calendarId)

        assertEquals(true, Calendar.setSubscriptionEnabled(id, false).await().ok)
        assertEquals(false, Calendar.subscriptions().await().subscriptions.unsafeCast<Array<dynamic>>().first { it.subscriptionId == id }.enabled)

        // ⚠⚠ W1/W2: the two background ops have no implementation in a browser at all.
        val service = JsRuntime.tenshuClient.calendarService
        assertNoImplementation("a tab must not fetch feeds", service.refreshSubscriptions())
        assertNoImplementation("a tab must not fire reminders", service.deliverDueReminders())

        assertEquals(true, Calendar.removeSubscription(id).await().ok)
        assertNull(Calendar.subscriptions().await().subscriptions.unsafeCast<Array<dynamic>>().firstOrNull { it.subscriptionId == id })
    }

    private suspend fun boom(): TError = TenshuSdk.awaitReady().await().let { GenericError.create(JsRuntime.tContext, "boom") }

    private fun TError.kind(): String = calendarFailure(this).error.kind as String

    /**
     * Not just "a failure": the dispatch must fail because **nothing implements it** — neither the
     * calendar plugin, nor any other plugin in the chain, nor core — not because a half-wired
     * implementation ran and errored.
     */
    private fun assertNoImplementation(why: String, result: TResult<*>) {
        assertTrue(result is TResult.Failure, "$why; got $result")
        assertTrue(result.error.toString().contains("No implementation found for operation"), "$why; got ${result.error}")
    }

    @Test
    fun theExcludedOps_haveNoImplementation_anywhereInTheChain() = GlobalScope.promise {
        TenshuSdk.awaitReady().await()
        for (id in listOf(DeliverDueReminders.ID, RefreshSubscriptions.ID)) {
            assertTrue(id in CalendarPlugin.EXCLUDED_OPS, id)
            assertNull(calendarWebOperation(id), "the calendar plugin maps no $id")
            assertNull(implementationFactory(id), "core maps no $id")
            val mapped = JsRuntimeBuilder.orderedPlugins.filter { it.operationFactory(JsRuntime)?.invoke(id) != null }
            assertTrue(mapped.isEmpty(), "no plugin maps $id; got ${mapped.map { it.id }}")
        }
        // The rest of the service still is implemented: the exclusion is two ids, not the plugin.
        assertNotNull(calendarWebOperation(org.tenshu.calendar.SaveEvent.ID))
    }

    // ── the bundled time-zone database (tzdb 2026a, @js-joda/timezone 2.26.0) ─────────────────

    @Test
    fun mexicoCity_isUtcMinus6InSummer_becauseMexicoAbolishedDst() = GlobalScope.promise {
        val calendarId = newCalendar()
        // Abolished in 2022: July 2026 is CST, UTC-6. tzdb 2020a still applies CDT (UTC-5, 14:00Z).
        save(draft(calendarId, "CDMX", "2026-07-01T09:00", "2026-07-01T10:00", "America/Mexico_City"))
        val row = occurrencesIn(calendarId, "2026-07-01", "2026-07-01").single()
        assertEquals(utc(2026, 7, 1, 15), row.startEpochMs, "Mexico City is UTC-6 in July 2026")
    }

    @Test
    fun theZonesTzdb2020aLacks_resolve() = GlobalScope.promise {
        val calendarId = newCalendar()
        // Europe/Kyiv (the 2022 rename of Europe/Kiev) and America/Ciudad_Juarez (2022b) are absent
        // from 2020a, so the save itself was refused; Almaty moved from UTC+6 to UTC+5 in 2024.
        save(draft(calendarId, "Kyiv", "2026-07-01T09:00", "2026-07-01T10:00", "Europe/Kyiv"))
        save(draft(calendarId, "Juarez", "2026-07-01T09:00", "2026-07-01T10:00", "America/Ciudad_Juarez"))
        save(draft(calendarId, "Almaty", "2026-07-01T09:00", "2026-07-01T10:00", "Asia/Almaty"))
        val byTitle = occurrencesIn(calendarId, "2026-07-01", "2026-07-01").associateBy { it.title as String }
        assertEquals(utc(2026, 7, 1, 6), byTitle.getValue("Kyiv").startEpochMs, "Kyiv is EEST, UTC+3")
        assertEquals(utc(2026, 7, 1, 15), byTitle.getValue("Juarez").startEpochMs, "Ciudad Juárez is MDT, UTC-6")
        assertEquals(utc(2026, 7, 1, 4), byTitle.getValue("Almaty").startEpochMs, "Almaty is UTC+5 since 2024")
        assertTrue(byTitle.values.none { it.zoneUnresolved as Boolean })
    }

    // ── the reader-zone fallback ────────────────────────────────────────────────────────────────

    /** `new Date(y, m-1, d, h, min)`: the civil time on the browser's own clock. */
    private fun browserLocal(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Double = Date(y, m - 1, d, h, min).getTime()

    @Test
    fun aBrowserZoneTheDatabaseLacks_fallsBackToTheBrowserClock_andAnUnknownEventZoneStillRenders() = GlobalScope.promise {
        val calendarId = newCalendar()
        save(draft(calendarId, "Floating", "2026-07-01T09:00", "2026-07-01T10:00"))
        val zoned = save(draft(calendarId, "Zoned", "2026-07-01T09:00", "2026-07-01T10:00", "Europe/Berlin"))
        // Another device, on a newer tzdb, wrote a zone this one does not know. Through the op
        // directly: this surface's own `saveEvent` refuses it (below).
        val unknownId = "evt-unknown-${stamp()}"
        val written = JsRuntime.tenshuClient.calendarService.saveEvent(
            eventId = unknownId,
            title = "Elsewhere",
            startLocal = "2026-07-01T11:00",
            endLocal = "2026-07-01T12:00",
            calendarId = calendarId,
            timeZoneId = "Mars/Olympus",
            allDay = false,
            location = "",
            notes = "",
            recurrenceRule = "",
            exceptionDates = "",
        )
        assertTrue(written is TResult.Success, "the op stores any zone id; got $written")

        val real = browserZoneId
        browserZoneId = { "Mars/Olympus" }
        try {
            val r = Calendar.occurrences("2026-07-01", "2026-07-01", false, "").await()
            assertEquals(true, r.ok, "a browser zone the database lacks must never fail the window; got ${JSON.stringify(r)}")
            assertEquals(true, r.readerZoneFallback)
            assertEquals("Mars/Olympus", r.readerZone)
            val byTitle = r.occurrences.unsafeCast<Array<dynamic>>().filter { it.calendarId == calendarId }
                .associateBy { it.title as String }
            assertEquals(browserLocal(2026, 7, 1, 9), byTitle.getValue("Floating").startEpochMs, "placed on the browser's clock")
            assertEquals(browserLocal(2026, 7, 1, 10), byTitle.getValue("Floating").endEpochMs)
            assertEquals(utc(2026, 7, 1, 7), byTitle.getValue("Zoned").startEpochMs, "a known zone is unaffected")
            assertEquals(false, byTitle.getValue("Zoned").zoneUnresolved)
            val elsewhere = byTitle.getValue("Elsewhere")
            assertEquals(true, elsewhere.zoneUnresolved, "an unknown event zone renders, flagged")
            assertEquals("Mars/Olympus", elsewhere.zoneId)
            assertEquals(browserLocal(2026, 7, 1, 11), elsewhere.startEpochMs, "in the reader's (fallback) zone, never null")

            // The live window takes the same path.
            val pushes = mutableListOf<dynamic>()
            val stop = Calendar.observeOccurrences("2026-07-01", "2026-07-01", "", { pushes.add(it) }, { })
            try {
                eventually("the live window with a blank reader zone must emit ok") {
                    // ⚠ A typed local: a scope function on a `dynamic` compiles to a JS method call.
                    val last: dynamic = pushes.lastOrNull()
                    last != null && (last.ok as Boolean) && (last.readerZoneFallback as Boolean)
                }
                val live = pushes.last().occurrences.unsafeCast<Array<dynamic>>().first { it.eventId == unknownId }
                assertEquals(browserLocal(2026, 7, 1, 11), live.startEpochMs)
                assertEquals(true, live.zoneUnresolved)
            } finally {
                stop()
            }
        } finally {
            browserZoneId = real
        }

        // A browser zone the database knows is no fallback.
        browserZoneId = { "Asia/Tokyo" }
        try {
            val r = Calendar.occurrences("2026-07-01", "2026-07-01", false, "").await()
            assertEquals(false, r.readerZoneFallback)
            assertEquals("Asia/Tokyo", r.readerZone)
            assertEquals(utc(2026, 7, 1, 0), r.occurrences.unsafeCast<Array<dynamic>>().first { it.title == "Floating" && it.calendarId == calendarId }.startEpochMs)
        } finally {
            browserZoneId = real
        }

        // Editing the unknown-zone event stays refused, typed: a save would have to pick a zone.
        assertKind("error_calendar_unknown_zone", Calendar.saveEvent(draft(calendarId, "Elsewhere", "2026-07-01T11:00", zone = "Mars/Olympus", eventId = unknownId)).await())
        // An explicit unknown reader zone is a caller bug, still refused.
        assertKind("error_calendar_unknown_zone", Calendar.occurrences("2026-07-01", "2026-07-01", false, "Mars/Olympus").await())
        assertEquals(true, Calendar.deleteEvent(zoned).await().ok)
    }

    // ── all-day events and window intersection ──────────────────────────────────────────────────

    @Test
    fun aSingleDayAllDayEvent_isThatCivilDayInTheReadersZone() = GlobalScope.promise {
        val calendarId = newCalendar()
        save(draft(calendarId, "Holiday", "2026-07-01", "", allDay = true))
        val row = occurrencesIn(calendarId, "2026-07-01", "2026-07-01", "Asia/Tokyo").single()
        assertEquals(true, row.allDay)
        assertEquals("2026-07-01T00:00", row.startLocal)
        assertEquals("2026-07-02T00:00", row.endLocal, "a blank all-day end is one day, exclusive")
        // Tokyo is UTC+9: its July 1st runs from 15:00Z on June 30th — not the UTC day.
        assertEquals(utc(2026, 6, 30, 15), row.startEpochMs)
        assertEquals(utc(2026, 7, 1, 15), row.endEpochMs)
        assertEquals(false, row.zoneUnresolved)
        assertTrue(occurrencesIn(calendarId, "2026-07-02", "2026-07-02", "Asia/Tokyo").isEmpty(), "the exclusive end is not a day it covers")
        assertTrue(occurrencesIn(calendarId, "2026-06-30", "2026-06-30", "Asia/Tokyo").isEmpty())
    }

    @Test
    fun anAllDayEventSpanningMidnight_isInTheWindowOfEachDayItCovers() = GlobalScope.promise {
        val calendarId = newCalendar()
        save(draft(calendarId, "Trip", "2026-07-01", "2026-07-03", allDay = true))
        // Its second day: the event started before this window and runs through it.
        val second = occurrencesIn(calendarId, "2026-07-02", "2026-07-02", "America/New_York").single()
        assertEquals("2026-07-01T00:00", second.startLocal)
        assertEquals("2026-07-03T00:00", second.endLocal)
        // New York is EDT, UTC-4, in July.
        assertEquals(utc(2026, 7, 1, 4), second.startEpochMs)
        assertEquals(utc(2026, 7, 3, 4), second.endEpochMs)
        assertEquals(1, occurrencesIn(calendarId, "2026-07-01", "2026-07-01", "America/New_York").size)
        assertEquals(1, occurrencesIn(calendarId, "2026-06-25", "2026-07-05", "America/New_York").size, "once per window, not once per day")
        assertTrue(occurrencesIn(calendarId, "2026-07-03", "2026-07-03", "America/New_York").isEmpty(), "DTEND is exclusive")
    }

    @Test
    fun anEventThatStartsBeforeTheWindow_andOverlapsIt_isInTheWindow() = GlobalScope.promise {
        val calendarId = newCalendar()
        // A night shift, 22:00 to 02:00 Berlin time, read as the day it runs into.
        save(draft(calendarId, "Night shift", "2026-07-10T22:00", "2026-07-11T02:00", "Europe/Berlin"))
        val row = occurrencesIn(calendarId, "2026-07-11", "2026-07-11").single()
        assertEquals("2026-07-10T22:00", row.startLocal)
        assertEquals(utc(2026, 7, 10, 20), row.startEpochMs)
        assertEquals(utc(2026, 7, 11, 0), row.endEpochMs)
        // A weekly multi-day series: each occurrence that reaches into the window is in it.
        save(draft(calendarId, "Weekend", "2026-08-01T18:00", "2026-08-03T09:00", "Europe/Berlin", "FREQ=WEEKLY;COUNT=3"))
        assertEquals(
            listOf("2026-08-08T18:00"),
            occurrencesIn(calendarId, "2026-08-09", "2026-08-09").filter { it.title == "Weekend" }.map { it.startLocal as String },
        )
        // Ending exactly as the window opens is not overlapping it.
        save(draft(calendarId, "Until midnight", "2026-07-20T22:00", "2026-07-21T00:00", "Europe/Berlin"))
        assertTrue(occurrencesIn(calendarId, "2026-07-21", "2026-07-21").none { it.title == "Until midnight" })
    }

    // ── .ics import, partial failure ────────────────────────────────────────────────────────────

    private class FailingWrites(
        val error: TError,
        val failSeries: Set<String> = emptySet(),
        val failSource: Set<String> = emptySet(),
        val failReminders: Set<String> = emptySet(),
    ) : IcsImportWrites {
        val series = mutableListOf<String>()
        val overrides = mutableListOf<String>()
        override suspend fun saveSeries(eventId: String, event: ImportedEvent, calendarId: String): TError? {
            if (eventId in failSeries) return error
            series += eventId
            return null
        }
        override suspend fun saveSource(eventId: String, event: ImportedEvent, origin: String): TError? =
            if (eventId in failSource) error else null
        override suspend fun setReminders(eventId: String, minutes: List<Int>): TError? =
            if (eventId in failReminders) error else null
        override suspend fun saveOverride(eventId: String, event: ImportedEvent): TError? {
            overrides += "$eventId@${event.recurrenceId}"
            return null
        }
    }

    private fun seriesWithException(uid: String) = listOf(
        "BEGIN:VEVENT", "UID:$uid", "SUMMARY:Series $uid", "DTSTART:20260901T190000", "DTEND:20260901T200000",
        "RRULE:FREQ=WEEKLY;COUNT=3", "BEGIN:VALARM", "TRIGGER:-PT30M", "ACTION:DISPLAY", "END:VALARM", "END:VEVENT",
        "BEGIN:VEVENT", "UID:$uid", "RECURRENCE-ID:20260908T190000", "SUMMARY:Moved $uid",
        "DTSTART:20260908T193000", "DTEND:20260908T203000", "END:VEVENT",
    )

    private fun ics(vararg blocks: List<String>) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN") + blocks.flatMap { it } + "END:VCALENDAR")
            .joinToString("\r\n", postfix = "\r\n")

    @Test
    fun anImportedSeriesThatFailedToSave_takesItsOverridesWithIt() = GlobalScope.promise {
        val events = IcsImport.read(ics(seriesWithException("bad"), seriesWithException("good"))).events
        assertEquals(4, events.size)
        val writes = FailingWrites(boom(), failSeries = setOf("cal~bad"))
        val outcome = importEvents(events, "cal", "test.ics", writes, inCalendar = emptySet())
        assertEquals(1, outcome.imported)
        assertEquals(1, outcome.overrides)
        assertEquals(2, outcome.failed, "the failed series AND its unwritten override")
        assertEquals(listOf("cal~good@2026-09-08T19:00"), writes.overrides, "no override is written on a series that failed")
        assertTrue(outcome.warnings.isEmpty())
    }

    @Test
    fun anImportedSeriesWhoseSourceOrRemindersFailed_isImported_withAWarning_neverAnOrphanCountedFailed() = GlobalScope.promise {
        val events = IcsImport.read(ics(seriesWithException("src"), seriesWithException("rem"))).events
        val writes = FailingWrites(boom(), failSource = setOf("cal~src"), failReminders = setOf("cal~rem"))
        val outcome = importEvents(events, "cal", "test.ics", writes, inCalendar = emptySet())
        assertEquals(2, outcome.imported, "the event rows landed; that is what was imported")
        assertEquals(2, outcome.overrides, "and their overrides with them")
        assertEquals(0, outcome.failed)
        assertEquals(listOf("cal~src" to "source", "cal~rem" to "reminders"), outcome.warnings.map { it.eventId to it.part })
        assertEquals(listOf("cal~src", "cal~rem"), writes.series, "kept, never rolled back")
    }

    /**
     * ⚠⚠ The native import's ids (`calendar-external-sources.md` §7): one `UID` imported into two
     * calendars is two rows, a series and its override share one, and a bare-`UID` row already in
     * the target is adopted. Diverging from native would double every event on a mixed realm.
     */
    @Test
    fun importedIds_areNamespacedByTheTargetCalendar_andAdoptABareRow() = GlobalScope.promise {
        val events = IcsImport.read(ics(seriesWithException("meet"))).events
        val intoA = FailingWrites(boom())
        importEvents(events, "a", "test.ics", intoA, inCalendar = emptySet())
        val intoB = FailingWrites(boom())
        importEvents(events, "b", "test.ics", intoB, inCalendar = emptySet())
        assertEquals(listOf("a~meet"), intoA.series)
        assertEquals(listOf("b~meet"), intoB.series)
        assertEquals(listOf("b~meet@2026-09-08T19:00"), intoB.overrides)

        val legacy = FailingWrites(boom())
        importEvents(events, "a", "test.ics", legacy, inCalendar = setOf("meet"))
        assertEquals(listOf("meet"), legacy.series, "the row stored before namespacing is re-imported in place")
    }

    // ── the hidden-set flow holds no timer ──────────────────────────────────────────────────────

    @Test
    fun theHiddenFlow_withNoDeviceId_resolvesOnce_andPollsNothing() = GlobalScope.promise {
        TenshuSdk.awaitReady().await()
        var resolves = 0
        val pushes = mutableListOf<Any>()
        val job = GlobalScope.launch {
            Calendar.hiddenFlow { resolves++; null }.collect { pushes += it }
        }
        try {
            delay(2_500)
            assertEquals(1, resolves, "one resolution, then parked: a poll would have re-resolved every second")
            assertEquals(1, pushes.size, "the 'nothing hidden' seed, and nothing else")
            assertTrue(job.isActive, "parked on the seed, not completed (observeJob would call that a dead stream)")
        } finally {
            job.cancel()
        }
    }
}
