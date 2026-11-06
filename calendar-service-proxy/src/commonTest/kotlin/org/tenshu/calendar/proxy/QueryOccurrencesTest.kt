package org.tenshu.calendar.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.ResolvedEventOccurrence
import org.tenshu.calendar.queryOccurrences
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.saveEventOverride
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.calendar.test.environmentFor
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get

/**
 * `queryOccurrences` (CA-8) — the window read that replaced Mia's capped series scan
 * (`mia-query-layer.md` §15.1).
 *
 * ⚠⚠ **The property under test is that the bound moved from the input to the output.** A test that
 * only checked "asking for tomorrow returns tomorrow's events" would pass against the very shape
 * this op exists to replace. [aCalendarOfHundredsOfSeriesStillAnswersAboutAnOldWeeklyOne] is the
 * one that separates them, and [theCapKeepsTheEarliestNotAnArbitrarySlice] is why the new bound is
 * safe where the old one was not.
 */
class QueryOccurrencesTest {

    private suspend fun service(p: TestProxy): CalendarService =
        environmentFor(p).getService(CalendarService.ID)

    private suspend fun CalendarService.occurrences(
        from: String,
        to: String,
        limit: Int = 100,
    ): List<ResolvedEventOccurrence> {
        val result = queryOccurrences(from, to, limit)
        if (result is TResult.Failure<*>) error("queryOccurrences failed: ${result.error}")
        val list = (result as TResult.Success<*>).value as TList<*>
        return list.toList().filterIsInstance<ResolvedEventOccurrence>()
    }

    private fun twoDigit(value: Int) = value.toString().padStart(2, '0')

    @Test
    fun aRecurringSeriesIsExpandedIntoTheWindow() = runTest {
        // ⚠⚠ The reason this op exists at all: the stored row says 2024 and the answer is about
        // 2026. **The rule is stored; the occurrence is computed** — no predicate over any field
        // of that row could have matched.
        val p = createCalendarProxy(backgroundScope)
        val service = service(p)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent(
            eventId = "standup",
            title = "Team standup",
            startLocal = "2024-01-04T10:00",
            endLocal = "2024-01-04T10:15",
            calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TH",
        )

        val rows = service.occurrences("2026-07-16", "2026-07-16")

        assertEquals(listOf("Team standup"), rows.map { it.title.get() })
        // ⚠ The occurrence's own recurrenceId, which is the handle an override is filed under and
        // the second half of the `OccurrenceId` a Mia citation addresses.
        assertEquals("2026-07-16T10:00", rows.single().recurrenceId.get())
        assertEquals("2026-07-16T10:00", rows.single().startLocal.get())
    }

    @Test
    fun aCalendarOfHundredsOfSeriesStillAnswersAboutAnOldWeeklyOne() = runTest {
        // ⚠⚠⚠ **The defect this op was built to remove.** A caller doing its own scan must bound
        // the SERIES it reads, and no order makes that safe: a series stores its *first*
        // occurrence, so the 2024 standup sorts oldest and is also what still happens tomorrow.
        // ⚠ Seeded LAST, behind 250 decoys, so a store-order truncation cannot pass by luck.
        val p = createCalendarProxy(backgroundScope)
        val service = service(p)
        service.saveCalendar("work", "Work", 0)
        repeat(250) { index ->
            service.saveEvent(
                eventId = "filler-$index",
                title = "Filler $index",
                startLocal = "2026-09-${twoDigit((index % 28) + 1)}T08:00",
                endLocal = "2026-09-${twoDigit((index % 28) + 1)}T08:30",
                calendarId = "work",
            )
        }
        service.saveEvent(
            eventId = "standup",
            title = "Team standup",
            startLocal = "2024-01-04T10:00",
            endLocal = "2024-01-04T10:15",
            calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TH",
        )

        assertEquals(
            listOf("Team standup"),
            service.occurrences("2026-07-16", "2026-07-16").map { it.title.get() },
        )
    }

    @Test
    fun theCapKeepsTheEarliestNotAnArbitrarySlice() = runTest {
        // ⭐ Why the new bound is safe where the old one was not: occurrences inside a window are
        // ordered by when they happen, so truncating drops the furthest away. Series are ordered
        // by nothing a window question cares about.
        val p = createCalendarProxy(backgroundScope)
        val service = service(p)
        service.saveCalendar("work", "Work", 0)
        repeat(10) { index ->
            service.saveEvent(
                eventId = "e$index",
                title = "Event $index",
                startLocal = "2026-07-16T09:${twoDigit(index)}",
                endLocal = "2026-07-16T09:${twoDigit(index)}",
                calendarId = "work",
            )
        }

        val rows = service.occurrences("2026-07-16", "2026-07-16", limit = 3)

        assertEquals(listOf("Event 0", "Event 1", "Event 2"), rows.map { it.title.get() })
    }

    @Test
    fun anOverrideMovesTheOccurrenceAndMarksIt() = runTest {
        val p = createCalendarProxy(backgroundScope)
        val service = service(p)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent(
            eventId = "standup",
            title = "Team standup",
            startLocal = "2024-01-04T10:00",
            endLocal = "2024-01-04T10:15",
            calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TH",
        )
        service.saveEventOverride(
            eventId = "standup",
            recurrenceId = "2026-07-16T10:00",
            startLocal = "2026-07-16T11:30",
            endLocal = "2026-07-16T11:45",
            title = "Team standup (moved)",
        )

        val row = service.occurrences("2026-07-16", "2026-07-16").single()

        assertEquals("Team standup (moved)", row.title.get())
        // ⚠ The EFFECTIVE start, but the ORIGINAL recurrenceId — the key the override is filed
        // under, and the handle needed to move it again.
        assertEquals("2026-07-16T11:30", row.startLocal.get())
        assertEquals("2026-07-16T10:00", row.recurrenceId.get())
        assertTrue(row.isOverridden.get())
    }

    @Test
    fun aWindowThatExcludesTheEventReturnsNothing() = runTest {
        val p = createCalendarProxy(backgroundScope)
        val service = service(p)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent("one-off", "Dentist", "2026-07-16T09:00", "2026-07-16T09:30", "work")

        assertEquals(emptyList(), service.occurrences("2026-07-17", "2026-07-18"))
    }

    @Test
    fun anInvertedOrUnparseableWindowReturnsNothingRatherThanEverything() = runTest {
        // ⚠⚠ The failure of a caller's date arithmetic must not become a full-calendar dump. A
        // window read that answered "everything" on a bad parameter would be the worst possible
        // degradation for the one op whose whole job is bounding.
        val p = createCalendarProxy(backgroundScope)
        val service = service(p)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent("one-off", "Dentist", "2026-07-16T09:00", "2026-07-16T09:30", "work")

        assertEquals(emptyList(), service.occurrences("2026-07-18", "2026-07-16"))
        assertEquals(emptyList(), service.occurrences("not-a-date", "2026-07-16"))
        assertEquals(emptyList(), service.occurrences("2026-07-16", ""))
    }

    @Test
    fun aZeroOrNegativeLimitReturnsNothingRatherThanEverything() = runTest {
        val p = createCalendarProxy(backgroundScope)
        val service = service(p)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent("one-off", "Dentist", "2026-07-16T09:00", "2026-07-16T09:30", "work")

        assertEquals(emptyList(), service.occurrences("2026-07-16", "2026-07-16", limit = 0))
        assertEquals(emptyList(), service.occurrences("2026-07-16", "2026-07-16", limit = -1))
    }
}
