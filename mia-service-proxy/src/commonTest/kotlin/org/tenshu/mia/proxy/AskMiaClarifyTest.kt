package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.Event
import org.tenshu.calendar.SaveEvent
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get
import org.tenshu.core.getOrNull
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaChoice
import org.tenshu.mia.MiaClarification
import org.tenshu.mia.MiaSource
import org.tenshu.mia.askMia
import org.tenshu.mia.confirmMiaAction
import org.tenshu.mia.replyToMia
import org.tenshu.preference.RealmSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The clarify turn end to end (`mia-query-layer.md` §25): `askMia` asks, `replyToMia` answers, and
 * `confirmMiaAction` is still the only thing that writes.
 *
 * ⚠⚠ The properties under test are the capability ones. The token answers one param of one plan,
 * once. A reply never writes. The write's preconditions are checked before asking and again at
 * the reply.
 */
class AskMiaClarifyTest {

    /** Wednesday 15 July 2026, 12:00 UTC. */
    private val now = 1_784_116_800_000L

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer =
        unwrap(proxy.miaService().askMia(utterance, now, "UTC"))

    private suspend fun reply(proxy: TestProxy, token: String, text: String): MiaAnswer =
        unwrap(proxy.miaService().replyToMia(token, text, now, "UTC"))

    private suspend fun confirm(proxy: TestProxy, token: String): MiaAnswer =
        unwrap(proxy.miaService().confirmMiaAction(token))

    private fun unwrap(result: TResult<MiaAnswer>): MiaAnswer {
        if (result is TResult.Failure<*>) error("call failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun MiaAnswer.asked(): MiaClarification {
        assertEquals(MiaAnswer.VERDICT_CLARIFY, verdict.get(), explanation.get())
        assertNull(action, "a question is never also a card")
        return assertNotNull(clarification)
    }

    private suspend fun TestProxy.events(): List<Event> =
        environment().getService<CalendarService>(CalendarService.ID)
            .events.getAll().getOrNull()?.toList().orEmpty().filterIsInstance<Event>()

    private fun TestProxy.seedCalendar() {
        executionContext.saveData(Calendar.create(context = tContext, calendarId = "cal-1", name = "Personal", colorArgb = 0))
        executionContext.saveData(
            RealmSetting.create(
                context = tContext,
                preferenceId = CalendarPreferences.DefaultCalendarId.id,
                value = "cal-1".toTString(tContext),
            )
        )
    }

    /** The calendar grant: since 2026-11-19 the only switch a calendar write needs (MQ-D6). */
    private fun TestProxy.grantCalendar(granted: Boolean) {
        executionContext.saveData(
            RealmSetting.create(
                context = tContext,
                preferenceId = MiaSource.CALENDAR.preference.id,
                value = granted.toTBoolean(tContext),
            )
        )
    }

    private suspend fun ready(proxy: TestProxy): TestProxy = proxy.apply { seedCalendar() }

    @Test
    fun aMissingStartIsAsked_andTheQuestionCarriesItsChoices() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))

        val asked = ask(proxy, "add dentist tomorrow").asked()

        assertEquals(SaveEvent.Params.START_LOCAL, asked.param.get())
        assertEquals(MiaClarification.REASON_MISSING, asked.reason.get())
        assertEquals("What time tomorrow?", asked.question.get())
        assertEquals(
            listOf("Morning 09:00", "Afternoon 14:00", "Evening 19:00"),
            asked.choices.toList().filterIsInstance<MiaChoice>().map { it.label.get() },
        )
        assertTrue(proxy.events().isEmpty())
    }

    /**
     * ⚠⚠ "never mind" declines: the question closes, nothing is searched and nothing is written
     * (§25.13; it used to fall through to a file-name search). A late answer finds nothing open.
     */
    @Test
    fun neverMindClosesTheQuestion() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        val asked = ask(proxy, "add dentist tomorrow").asked()

        val declined = reply(proxy, asked.token.get(), "never mind")
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, declined.verdict.get())
        assertEquals("OK, I've left it.", declined.explanation.get())

        val late = reply(proxy, asked.token.get(), "9")
        assertEquals("That question is no longer open — ask me again.", late.explanation.get())
        assertTrue(proxy.events().isEmpty())
    }

    /** ⭐⭐ The whole turn: question, typed answer, card, tap, row. Only the tap writes. */
    @Test
    fun aReplyCompletesTheCommand_andOnlyTheTapWrites() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        val asked = ask(proxy, "add dentist tomorrow").asked()

        val card = reply(proxy, asked.token.get(), "9")
        assertEquals(MiaAnswer.VERDICT_CONFIRM, card.verdict.get(), card.explanation.get())
        val action = assertNotNull(card.action)
        assertTrue(action.detail.get().contains("09:00"), action.detail.get())
        assertTrue(proxy.events().isEmpty(), "a reply never writes")

        confirm(proxy, action.token.get())
        val event = proxy.events().single()
        assertEquals("dentist", event.title.get())
        assertEquals("2026-07-16T09:00", event.startLocal.get())
    }

    @Test
    fun aChipIsTheSameReplyAsTyping() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        val asked = ask(proxy, "add dentist tomorrow").asked()
        val evening = asked.choices.toList().filterIsInstance<MiaChoice>().single { it.label.get() == "Evening 19:00" }

        val card = reply(proxy, asked.token.get(), evening.reply.get())
        confirm(proxy, assertNotNull(card.action).token.get())
        assertEquals("2026-07-16T19:00", proxy.events().single().startLocal.get())
    }

    /** ⚠⚠ A reply that is not the param is answered as a new question, and nothing is written. */
    @Test
    fun aReplyThatIsNotAnAnswerIsANewQuestion() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        val asked = ask(proxy, "add dentist tomorrow").asked()

        val answer = reply(proxy, asked.token.get(), "what is on my calendar tomorrow")
        assertNotEquals(MiaAnswer.VERDICT_CLARIFY, answer.verdict.get())
        assertNull(answer.action)
        assertTrue(proxy.events().isEmpty())
    }

    /** ⚠⚠ A reply that is neither a time nor a question asks for the time again (§25.14). */
    @Test
    fun aMissedTimeIsAskedAgain() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        val asked = ask(proxy, "add dentist tomorrow").asked()

        val again = reply(proxy, asked.token.get(), "banana")
        assertEquals("I didn't catch a time in “banana”. What time tomorrow?", again.explanation.get())
        assertEquals(asked.choices.toList().size, again.asked().choices.toList().size)
        assertNotNull(reply(proxy, again.asked().token.get(), "9").action)
        assertTrue(proxy.events().isEmpty())
    }

    /**
     * ⚠⚠ A question about a source that is switched off is still a question: it gets the grant's
     * own refusal, never the time question again (Codex review).
     */
    @Test
    fun aQuestionAboutARevokedSourceIsNoMissedAnswer() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        proxy.executionContext.saveData(
            RealmSetting.create(
                context = proxy.tContext,
                preferenceId = MiaSource.DEVICES.preference.id,
                value = false.toTBoolean(proxy.tContext),
            )
        )
        val asked = ask(proxy, "add dentist tomorrow").asked()

        val answer = reply(proxy, asked.token.get(), "what devices do I have")
        assertNotEquals(MiaAnswer.VERDICT_CLARIFY, answer.verdict.get())
        assertTrue(answer.explanation.get().contains("not allowed"), answer.explanation.get())
        assertTrue(proxy.events().isEmpty())
    }

    @Test
    fun theQuestionTokenIsSingleUse() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        val token = ask(proxy, "add dentist tomorrow").asked().token.get()

        reply(proxy, token, "9")
        val again = reply(proxy, token, "10")
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, again.verdict.get())
        assertTrue(again.explanation.get().contains("no longer open"), again.explanation.get())
    }

    /** ⚠ One open question at a time: a stale one can never fill a plan the user moved past. */
    @Test
    fun aNewQuestionClosesTheOldOne() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        val first = ask(proxy, "add dentist tomorrow").asked().token.get()
        ask(proxy, "add dinner tomorrow").asked()

        assertEquals(MiaAnswer.VERDICT_NO_MATCH, reply(proxy, first, "9").verdict.get())
    }

    @Test
    fun anUnknownTokenIsRefused() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, reply(proxy, "not-a-token", "9").verdict.get())
    }

    /** ⚠⚠ Never ask about a write the calendar will refuse: no calendar grant, no question. */
    @Test
    fun withoutTheCalendarGrantNothingIsAsked() = runTest {
        val proxy = createMiaProxy(backgroundScope).apply { seedCalendar(); grantCalendar(false) }

        val answer = ask(proxy, "add dentist tomorrow")
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.clarification)
    }

    @Test
    fun withoutADefaultCalendarNothingIsAsked() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        val answer = ask(proxy, "add dentist tomorrow")
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.clarification)
    }

    /** ⚠ The grants are read again at the reply: a revocation in between is honoured. */
    @Test
    fun aGrantRevokedBeforeTheReplyIsRefusedAtTheReply() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        val token = ask(proxy, "add dentist tomorrow").asked().token.get()
        proxy.grantCalendar(false)

        val answer = reply(proxy, token, "9")
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertNull(answer.action)
    }

    @Test
    fun aStartThatHasPassedIsAskedAgain() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))

        val asked = ask(proxy, "add a meeting at 10").asked()
        assertEquals(MiaClarification.REASON_INVALID, asked.reason.get())
        assertEquals(
            listOf("Tomorrow 10:00", "Today 22:00"),
            asked.choices.toList().filterIsInstance<MiaChoice>().map { it.label.get() },
        )
    }

    // ---- CL-4: "20:00 instead?" on the card (CL-D6) ---------------------------------

    private fun MiaAnswer.card(): org.tenshu.mia.MiaAction {
        assertEquals(MiaAnswer.VERDICT_CONFIRM, verdict.get(), explanation.get())
        return assertNotNull(action)
    }

    /** ⭐⭐ The alternative replaces the card, and only the new card's tap writes. */
    @Test
    fun theAlternativeReplacesTheCard_andTheOldCardIsSpent() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))

        val first = ask(proxy, "add dinner tomorrow at 8").card()
        assertTrue(first.detail.get().contains("08:00"), first.detail.get())
        val alternative = first.alternatives.toList().filterIsInstance<MiaChoice>().single()
        assertEquals("20:00 instead?", alternative.label.get())

        val second = reply(proxy, first.token.get(), alternative.reply.get()).card()
        assertTrue(second.detail.get().contains("20:00"), second.detail.get())
        assertTrue(second.alternatives.toList().isEmpty(), "a chosen time is settled")
        assertTrue(proxy.events().isEmpty(), "an alternative never writes")

        // ⚠⚠ The first card is spent: it cannot write 08:00 beside the 20:00 card. And it says so
        // with `noMatch`, so the client marks it "Not done", never "Done" (§25.13).
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, confirm(proxy, first.token.get()).verdict.get())
        assertTrue(proxy.events().isEmpty())

        assertEquals(MiaAnswer.VERDICT_ANSWERED, confirm(proxy, second.token.get()).verdict.get())
        assertEquals("2026-07-16T20:00", proxy.events().single().startLocal.get())
    }

    /** ⚠⚠ And the other way: once the card is confirmed, its alternative writes nothing more. */
    @Test
    fun aConfirmedCardsAlternativeIsSpentToo() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        val card = ask(proxy, "add dinner tomorrow at 8").card()
        confirm(proxy, card.token.get())

        val alternative = card.alternatives.toList().filterIsInstance<MiaChoice>().single()
        val after = reply(proxy, card.token.get(), alternative.reply.get())
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, after.verdict.get())
        assertNull(after.action)
        assertEquals("2026-07-16T08:00", proxy.events().single().startLocal.get())
    }

    /**
     * ⚠⚠ A card's alternative is its own, not "the open question" (Codex review): a second card, or
     * a question asked in between, must not disable the first card's "20:00 instead?".
     */
    @Test
    fun aLaterCardOrQuestionLeavesAnEarlierCardsAlternativeWorking() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        val first = ask(proxy, "add dinner tomorrow at 8").card()
        ask(proxy, "add breakfast tomorrow at 7").card()
        ask(proxy, "add dentist tomorrow").asked()

        val alternative = first.alternatives.toList().filterIsInstance<MiaChoice>().single()
        val replaced = reply(proxy, first.token.get(), alternative.reply.get()).card()
        assertTrue(replaced.detail.get().contains("20:00"), replaced.detail.get())
    }

    @Test
    fun aSettledTimeShowsNoAlternative() = runTest {
        val proxy = ready(createMiaProxy(backgroundScope))
        assertTrue(ask(proxy, "add dinner tomorrow at 8pm").card().alternatives.toList().isEmpty())
    }
}
