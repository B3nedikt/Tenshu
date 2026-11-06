package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.Event
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaSource
import org.tenshu.mia.askMia
import org.tenshu.mia.confirmMiaAction
import org.tenshu.preference.RealmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MQ-D4a: the first intent that **authors user data**.
 *
 * ⚠⚠ Every read before this produced a wrong *answer* on a misheard slot — seen and ignored.
 * A write produces a wrong *row*, which persists, syncs and fires a reminder weeks later. So
 * the properties under test are that it never writes without a grant, never without a tap, and
 * never anything the user was not shown.
 */
class AskMiaCreateEventTest {

    /** Wednesday 15 July 2026, 12:00 UTC. */
    private val now = 1_784_116_800_000L

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, "UTC")
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private suspend fun confirm(proxy: TestProxy, token: String): MiaAnswer {
        val result = proxy.miaService().confirmMiaAction(token)
        if (result is TResult.Failure<*>) error("confirm failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private suspend fun TestProxy.events(): List<Event> =
        environment().getService<CalendarService>(CalendarService.ID)
            .events.getAll().getOrNull()?.toList().orEmpty().filterIsInstance<Event>()

    private fun TestProxy.seedCalendar(id: String = "cal-1", name: String = "Personal") {
        executionContext.saveData(
            Calendar.create(context = tContext, calendarId = id, name = name, colorArgb = 0)
        )
        executionContext.saveData(
            RealmSetting.create(
                context = tContext,
                preferenceId = CalendarPreferences.DefaultCalendarId.id,
                value = id.toTString(tContext),
            )
        )
    }

    private fun TestProxy.grantWrite(granted: Boolean = true) {
        executionContext.saveData(
            RealmSetting.create(
                context = tContext,
                preferenceId = assertNotNull(MiaSource.CALENDAR.writePreference).id,
                value = granted.toTBoolean(tContext),
            )
        )
    }

    @Test
    fun withoutTheWriteGrantNothingIsEvenOffered() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()

        val answer = ask(proxy, "add lunch with anna tomorrow at 1pm")

        // ⚠⚠ Write is opt-in — the read grant defaults on, this one does not.
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
        assertTrue(answer.explanation.get().contains("settings"), answer.explanation.get())
        assertTrue(proxy.events().isEmpty())
    }

    @Test
    fun theConfirmationShowsTheResolvedRowNotTheUtterance() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.grantWrite()

        val answer = ask(proxy, "add lunch with anna tomorrow at 1pm")

        assertEquals(MiaAnswer.VERDICT_CONFIRM, answer.verdict.get())
        val action = assertNotNull(answer.action)
        assertEquals("lunch with anna", action.title.get())
        // ⚠⚠ The END TIME and the CALENDAR are service-owned defaults the user never said.
        // Letting them land unseen is the failure MQ-D7's write clause exists to prevent.
        assertTrue(action.detail.get().contains("2026-07-16 13:00"), action.detail.get())
        assertTrue(action.detail.get().contains("14:00"), action.detail.get())
        assertTrue(action.detail.get().contains("Personal"), action.detail.get())
        // ⚠ And nothing exists yet.
        assertTrue(proxy.events().isEmpty(), "asking must not write")
    }

    @Test
    fun theTapWritesExactlyWhatWasShown() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.grantWrite()
        val token = assertNotNull(ask(proxy, "add lunch with anna tomorrow at 1pm").action).token.get()

        val ack = confirm(proxy, token)

        assertTrue(ack.explanation.get().contains("Personal"), ack.explanation.get())
        val event = proxy.events().single()
        assertEquals("lunch with anna", event.title.get())
        assertEquals("2026-07-16T13:00", event.startLocal.get())
        assertEquals("2026-07-16T14:00", event.endLocal.get())
        assertEquals("cal-1", event.calendarId.get())
    }

    @Test
    fun aMissingTimeIsAskedForRatherThanGuessed() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.grantWrite()

        val answer = ask(proxy, "add lunch with anna")

        // ⚠⚠ "Probably now" would be a row that fires a reminder at the wrong moment weeks
        // later. A read can afford a bad guess; a write cannot.
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
        assertTrue(answer.explanation.get().contains("when"), answer.explanation.get())
        assertTrue(proxy.events().isEmpty())
    }

    @Test
    fun noDefaultCalendarIsRefusedBeforeAnythingIsOffered() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.grantWrite()

        val answer = ask(proxy, "add lunch with anna tomorrow at 1pm")

        // ⚠ SaveEvent would throw; a confirmation whose button is guaranteed to fail is worse
        // than an honest no.
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
        assertTrue(answer.explanation.get().contains("calendar"), answer.explanation.get())
    }

    @Test
    fun aNamedWeekdayResolvesFORWARD() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.grantWrite()

        // ⚠⚠ 15 July 2026 is a Wednesday. The SEARCH grammar resolves "friday" backwards to
        // the most recent one; a scheduling grammar must go forwards or every event lands in
        // the past.
        val token = assertNotNull(ask(proxy, "schedule standup friday at 9am").action).token.get()
        confirm(proxy, token)

        assertEquals("2026-07-17T09:00", proxy.events().single().startLocal.get())
    }

    @Test
    fun relativeOffsetsWork() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.grantWrite()

        val token = assertNotNull(ask(proxy, "add coffee in an hour").action).token.get()
        confirm(proxy, token)

        assertEquals("2026-07-15T13:00", proxy.events().single().startLocal.get())
    }

    @Test
    fun aSpentTokenCannotWriteTwice() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.grantWrite()
        val token = assertNotNull(ask(proxy, "add lunch tomorrow at 1pm").action).token.get()

        confirm(proxy, token)
        confirm(proxy, token)

        assertEquals(1, proxy.events().size, "re-tapping must not write twice")
    }

    @Test
    fun revokingTheREADGrantAlsoStopsTheWrite() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedCalendar()
        proxy.grantWrite()
        proxy.executionContext.saveData(
            RealmSetting.create(
                context = proxy.tContext,
                preferenceId = MiaSource.CALENDAR.preference.id,
                value = false.toTBoolean(proxy.tContext),
            )
        )

        val answer = ask(proxy, "add lunch with anna tomorrow at 1pm")

        // ⚠ Write implies read: a write intent resolves against existing data, so a write
        // grant without the read one is unsatisfiable and reads as absent.
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
    }
}
