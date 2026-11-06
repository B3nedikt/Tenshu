package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.common.recurrence.OccurrenceId
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.toTBoolean
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import org.tenshu.mia.MiaSource
import org.tenshu.mia.askMia
import org.tenshu.mia.plan.QueryPlanner
import org.tenshu.preference.RealmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MQ-D4a's **read** half (§15): "what is on my calendar tomorrow".
 *
 * ⚠⚠ The property that matters here is not that a query returns rows — it is that the answer is
 * about **occurrences**, which are computed rather than stored. A test that only seeds one-off
 * events would pass against an implementation that filtered stored rows by date, and that
 * implementation cannot answer this question at all: a weekly event created two years ago has no
 * row for tomorrow. [aRecurringSeriesOccursTomorrowEvenThoughNoRowSaysSo] is the test that
 * separates the two.
 */
class AskMiaCalendarTest {

    /** Wednesday 15 July 2026, 12:00 UTC. */
    private val now = 1_784_116_800_000L

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, "UTC")
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun MiaAnswer.hitList(): List<MiaHit> =
        hits.toList().filterIsInstance<MiaHit>()

    private fun MiaAnswer.titles(): List<String> = hitList().map { it.title.get() }

    private fun TestProxy.seedCalendar(id: String = "cal-1") {
        executionContext.saveData(
            Calendar.create(context = tContext, calendarId = id, name = "Personal", colorArgb = 0)
        )
    }

    private fun TestProxy.seedEvent(
        eventId: String,
        title: String,
        startLocal: String,
        endLocal: String,
        recurrenceRule: String = "",
        allDay: Boolean = false,
    ) {
        executionContext.saveData(
            Event.create(
                context = tContext,
                eventId = eventId,
                calendarId = "cal-1",
                title = title,
                startLocal = startLocal,
                endLocal = endLocal,
                timeZoneId = "UTC",
                allDay = allDay,
                recurrenceRule = recurrenceRule,
            )
        )
    }

    @Test
    fun anEventTomorrowIsCitedWithItsTime() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.seedEvent("e1", "Dentist", "2026-07-16T09:00", "2026-07-16T09:30")

        val answer = ask(proxy, "what is on my calendar tomorrow")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(listOf("Dentist"), answer.titles())
        assertEquals("09:00–09:30", answer.hitList().single().subtitle.get())
        // ⭐⭐ **The citation addresses the OCCURRENCE, not the series** — §15.6's open item, closed
        // by TT-1c. It used to carry the bare `eventId`, so a tap on "Dentist tomorrow" would have
        // opened the series, whose stored row is dated whenever the event was first created.
        //
        // ⚠⚠ Asserting BOTH parts is the point. `targetKey` is deliberately empty for a multi-part
        // id, so a test that only checked it would pass against a `StringDataId` carrying the wrong
        // thing — and against one carrying nothing at all.
        val target = answer.hitList().single().target
        val parts = OccurrenceId.parse(target)
        assertEquals("e1" to "2026-07-16T09:00", parts)
        assertEquals(OccurrenceId.TYPE, answer.hitList().single().targetType)
    }

    @Test
    fun theWindowActuallyNarrows() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.seedEvent("today", "Standup", "2026-07-15T09:00", "2026-07-15T09:15")
        proxy.seedEvent("tomorrow", "Dentist", "2026-07-16T09:00", "2026-07-16T09:30")

        assertEquals(listOf("Standup"), ask(proxy, "what is on my calendar today").titles())
        assertEquals(listOf("Dentist"), ask(proxy, "what is on my calendar tomorrow").titles())
    }

    @Test
    fun aRecurringSeriesOccursTomorrowEvenThoughNoRowSaysSo() = runTest {
        // ⚠⚠ **The test this whole design exists for.** The stored row says 2024, and the
        // question is about tomorrow — a Thursday. No predicate over any field of this row
        // matches the question, whatever `startLocal`'s type: the occurrence is not stored, the
        // rule is. §8.4 read the string typing as the blocker; it was never the blocker.
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.seedEvent(
            eventId = "standup",
            title = "Team standup",
            startLocal = "2024-01-04T10:00",
            endLocal = "2024-01-04T10:15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TH",
        )

        assertEquals(listOf("Team standup"), ask(proxy, "what is on my calendar tomorrow").titles())
    }

    @Test
    fun anOverrideMovesTheOccurrenceItNames() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.seedEvent(
            eventId = "standup",
            title = "Team standup",
            startLocal = "2024-01-04T10:00",
            endLocal = "2024-01-04T10:15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TH",
        )
        // ⚠ The second chain. Without it the answer would be confidently wrong about the time —
        // which is why the override read is not optional decoration.
        proxy.executionContext.saveData(
            EventOverride.create(
                context = proxy.tContext,
                eventId = "standup",
                // ⚠ `formatRecurrenceId`'s exact shape — this string is the KEY joining an
                // override to its occurrence, and a format that differs by one character orphans
                // the override silently rather than failing.
                recurrenceId = "2026-07-16T10:00",
                startLocal = "2026-07-16T11:30",
                endLocal = "2026-07-16T11:45",
                title = "Team standup (moved)",
            )
        )

        val answer = ask(proxy, "what is on my calendar tomorrow")

        assertEquals(listOf("Team standup (moved)"), answer.titles())
        assertEquals("11:30–11:45", answer.hitList().single().subtitle.get())
    }

    @Test
    fun anAllDayEventHasNoTimesToShow() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.seedEvent(
            eventId = "holiday",
            title = "Public holiday",
            startLocal = "2026-07-16T00:00",
            endLocal = "2026-07-16T23:59",
            allDay = true,
        )

        assertEquals("All day", ask(proxy, "what is on my calendar tomorrow")
            .hitList().single().subtitle.get())
    }

    @Test
    fun anEmptyDayIsAnsweredEmpty_notRefused() = runTest {
        // ⚠⚠ The distinction §14.1 is about, from the other side: nothing on tomorrow is a real
        // ANSWER — Mia looked — so the surface may legitimately say "nothing matched".
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.seedEvent("today", "Standup", "2026-07-15T09:00", "2026-07-15T09:15")

        val answer = ask(proxy, "what is on my calendar tomorrow")

        assertEquals(MiaAnswer.VERDICT_ANSWERED, answer.verdict.get())
        assertEquals(emptyList(), answer.hitList())
    }

    @Test
    fun occurrencesComeBackInTimeOrder() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.seedEvent("late", "Dinner", "2026-07-16T19:00", "2026-07-16T21:00")
        proxy.seedEvent("early", "Dentist", "2026-07-16T09:00", "2026-07-16T09:30")

        assertEquals(
            listOf("Dentist", "Dinner"),
            ask(proxy, "what is on my calendar tomorrow").titles(),
        )
    }

    @Test
    fun revokingTheCalendarGrantRefusesByName() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.seedEvent("e1", "Dentist", "2026-07-16T09:00", "2026-07-16T09:30")
        proxy.executionContext.saveData(
            RealmSetting.create(
                context = proxy.tContext,
                preferenceId = MiaSource.CALENDAR.preference.id,
                value = false.toTBoolean(proxy.tContext),
            )
        )

        val answer = ask(proxy, "what is on my calendar tomorrow")

        // ⚠ Refused during PLANNING and by name — never an empty answer, which would assert an
        // absence we never established (MQ-2b).
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertTrue(
            answer.explanation.get().contains("calendar", ignoreCase = true),
            answer.explanation.get(),
        )
        assertEquals(emptyList(), answer.hitList())
    }

    // ---- §15.1: the series cap, and why it had to go -----------------------------

    @Test
    fun anOldWeeklyMeetingSurvivesACalendarOfHundredsOfSeries() = runTest {
        // ⚠⚠⚠ **The regression test this whole change exists for.** The read used to scan at most
        // 200 event SERIES, because a chain bound caps rows and rows here are series — and there
        // is no order that makes that truncation safe: a series stores its **first** occurrence,
        // so the standup created in 2024 is both the oldest row and the event that still happens
        // tomorrow (§15.1).
        //
        // ⚠⚠ **The decoys are seeded FIRST, and that is load-bearing.** The old chain read
        // `events.take(200)` in STORE order, so a standup seeded first would have survived by luck
        // and this test would have passed against the very code it exists to condemn.
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        repeat(250) { index ->
            // ⚠ Dated after the standup and outside the window, so they crowd the scan without
            // ever being part of the answer — precisely how the defect stayed invisible.
            proxy.seedEvent(
                eventId = "filler-$index",
                title = "Filler $index",
                startLocal = "2026-09-${twoDigit((index % 28) + 1)}T08:00",
                endLocal = "2026-09-${twoDigit((index % 28) + 1)}T08:30",
            )
        }
        proxy.seedEvent(
            eventId = "standup",
            title = "Team standup",
            startLocal = "2024-01-04T10:00",
            endLocal = "2024-01-04T10:15",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=TH",
        )

        assertEquals(
            listOf("Team standup"),
            ask(proxy, "what is on my calendar tomorrow").titles(),
        )
    }

    @Test
    fun anExactlyFullAgendaDoesNotClaimThereAreMore() = runTest {
        // ⚠⚠ The old code compared `series >= cap` and therefore said "there may be more" at
        // *exactly* the cap, when there were not. The chain now asks for one more than it shows,
        // so the claim is a fact rather than a guess — and this is the case that separates them.
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        repeat(QueryPlanner.DEFAULT_LIMIT) { index ->
            proxy.seedEvent(
                eventId = "e$index",
                title = "Event $index",
                startLocal = "2026-07-16T09:${twoDigit(index)}",
                endLocal = "2026-07-16T09:${twoDigit(index)}",
            )
        }

        val answer = ask(proxy, "what is on my calendar tomorrow")

        assertEquals(QueryPlanner.DEFAULT_LIMIT, answer.hitList().size)
        assertFalse(
            answer.explanation.get().contains("more", ignoreCase = true),
            answer.explanation.get(),
        )
    }

    @Test
    fun oneMoreThanFitsSaysSo_andKeepsTheEarliest() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        repeat(QueryPlanner.DEFAULT_LIMIT + 1) { index ->
            proxy.seedEvent(
                eventId = "e$index",
                title = "Event $index",
                // ⚠ One per minute, so the ORDER is unambiguous and the test can assert *which*
                // survived truncation rather than merely how many.
                startLocal = "2026-07-16T09:${twoDigit(index)}",
                endLocal = "2026-07-16T09:${twoDigit(index)}",
            )
        }

        val answer = ask(proxy, "what is on my calendar tomorrow")

        assertEquals(QueryPlanner.DEFAULT_LIMIT, answer.hitList().size)
        assertTrue(answer.explanation.get().contains("there are more"), answer.explanation.get())
        // ⚠⚠ The EARLIEST survive, not an arbitrary 20. That is the property the series cap could
        // not offer at all: occurrences are ordered by when they happen, series are not.
        assertEquals("Event 0", answer.titles().first())
        assertEquals("Event ${QueryPlanner.DEFAULT_LIMIT - 1}", answer.titles().last())
    }

    private fun twoDigit(value: Int): String = value.toString().padStart(2, '0')
}
