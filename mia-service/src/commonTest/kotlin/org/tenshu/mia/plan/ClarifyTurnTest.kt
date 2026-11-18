package org.tenshu.mia.plan

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.tenshu.calendar.SaveEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The clarify turn's planner half (`mia-query-layer.md` §25): asking for one param of
 * `SaveEvent`, and hearing the answer.
 *
 * ⚠⚠ The property that matters most is negative: no reply ever invents a start, and a start that
 * has passed is never accepted. Every answer below is either the completed command or another
 * question.
 */
class ClarifyTurnTest {

    private val zone = TimeZone.UTC

    /** Wednesday, 15 July 2026, 12:00 UTC. */
    private val nowMs = LocalDateTime(2026, 7, 15, 12, 0).toInstant(zone).toEpochMilliseconds()

    private fun ask(utterance: String) =
        assertIs<QueryPlan.Clarify>(QueryPlanner.plan(utterance, nowMs, zone, MiaActionOffer.OFFERED), utterance)

    private fun reply(clarify: QueryPlan.Clarify, text: String) =
        QueryPlanner.answerClarification(clarify, text, nowMs, zone)

    private fun event(plan: QueryPlan?) = assertIs<QueryPlan.CreateEvent>(plan)

    @Test
    fun aMissingStartIsAskedFor_withTheDayItHeard() {
        val asked = ask("add dentist tomorrow")

        assertEquals(SaveEvent.Params.START_LOCAL, asked.param)
        assertEquals(ClarifyReason.MISSING, asked.reason)
        assertEquals("What time tomorrow?", asked.explanation)
        assertEquals("dentist", asked.heard[SaveEvent.Params.TITLE])
        assertEquals(listOf("Morning 09:00", "Afternoon 14:00", "Evening 19:00"), asked.choices.map { it.label })
    }

    @Test
    fun aChipIsACannedReply_thatCompletesTheCommand() {
        val asked = ask("add dentist tomorrow")
        val evening = asked.choices.single { it.label == "Evening 19:00" }

        val done = event(reply(asked, evening.reply))
        assertEquals(LocalDateTime(2026, 7, 16, 19, 0), done.startLocal)
        assertEquals("dentist", done.title)
    }

    @Test
    fun aTypedReplyIsReadOnlyAsTheTime() {
        val asked = ask("add dentist tomorrow")

        assertEquals(LocalDateTime(2026, 7, 16, 9, 0), event(reply(asked, "9")).startLocal)
        assertEquals(LocalDateTime(2026, 7, 16, 9, 0), event(reply(asked, "nine")).startLocal)
        assertEquals(LocalDateTime(2026, 7, 16, 15, 30), event(reply(asked, "at 15:30")).startLocal)
        // ⚠ The title is never the reply's to change: it was heard, and it stays.
        assertEquals("dentist", event(reply(asked, "at 3")).title)
    }

    @Test
    fun thePartOfDayTheQuestionKnewSettlesABareHour() {
        val evening = ask("add dinner this evening")
        assertEquals("What time this evening?", evening.explanation)
        assertEquals(LocalDateTime(2026, 7, 15, 20, 0), event(reply(evening, "8")).startLocal)

        val morning = ask("add dentist tomorrow morning")
        assertEquals("What time tomorrow morning?", morning.explanation)
        assertEquals(LocalDateTime(2026, 7, 16, 8, 0), event(reply(morning, "8")).startLocal)
    }

    @Test
    fun aStartThatHasPassedIsInvalid_andOffersTomorrowOrTonight() {
        val asked = ask("add a meeting at 10")

        assertEquals(ClarifyReason.INVALID, asked.reason)
        assertEquals("10:00 has passed today. When should it be?", asked.explanation)
        assertEquals(listOf("Tomorrow 10:00", "Today 22:00"), asked.choices.map { it.label })
        assertEquals(LocalDateTime(2026, 7, 16, 10, 0), event(reply(asked, asked.choices.first().reply)).startLocal)
    }

    @Test
    fun aReplyThatHasAlsoPassedIsAskedAgain_neverAccepted() {
        val asked = ask("add dentist today")
        val again = assertIs<QueryPlan.Clarify>(reply(asked, "at 9"))
        assertEquals(ClarifyReason.INVALID, again.reason)
    }

    @Test
    fun aChoiceIsNeverAStartInThePast() {
        // At noon today, the morning is gone.
        assertEquals(listOf("Afternoon 14:00", "Evening 19:00"), ask("add call today").choices.map { it.label })
    }

    @Test
    fun aMissingTitleIsAskedFirst_thenTheStart() {
        val title = ask("add tomorrow")
        assertEquals(SaveEvent.Params.TITLE, title.param)
        assertEquals("What should I call it?", title.explanation)
        assertTrue(title.choices.isEmpty())

        // The title reply re-enters the checks and meets the next open param, still knowing the day.
        val start = assertIs<QueryPlan.Clarify>(reply(title, "dentist"))
        assertEquals(SaveEvent.Params.START_LOCAL, start.param)
        assertEquals("What time tomorrow?", start.explanation)
        assertEquals(LocalDateTime(2026, 7, 16, 9, 0), event(reply(start, "9")).startLocal)
    }

    @Test
    fun aTitleReplyKeepsTheStartAlreadyHeard() {
        val asked = ask("add tomorrow at 1pm")
        assertEquals(SaveEvent.Params.TITLE, asked.param)
        val done = event(reply(asked, "lunch with anna"))
        assertEquals("lunch with anna", done.title)
        assertEquals(LocalDateTime(2026, 7, 16, 13, 0), done.startLocal)
    }

    /** ⚠⚠ A reply that is not the param is a new question, never forced into the slot. */
    @Test
    fun aReplyThatIsNotATimeIsNotAnAnswer() {
        val asked = ask("add dentist tomorrow")
        assertNull(reply(asked, "what's on my calendar today"))
        assertNull(reply(asked, "actually never mind"))
        assertNull(reply(asked, ""))
        // ⚠⚠ A new question that merely CONTAINS a time is still a new question (Codex review):
        // it once booked the dentist for 15:00.
        assertNull(reply(asked, "actually, what's on my calendar tomorrow at 3pm"))
        assertNull(reply(asked, "remind me to call mum at 5"))
    }

    @Test
    fun aTimeWithFillerOrAChipLabelIsStillAnAnswer() {
        val asked = ask("add dentist tomorrow")
        assertEquals(LocalDateTime(2026, 7, 16, 9, 0), event(reply(asked, "make it 9")).startLocal)
        assertEquals(LocalDateTime(2026, 7, 16, 9, 0), event(reply(asked, "how about nine")).startLocal)
        // ⚠ Trailing filler after a spoken hour, as after a digit one (Codex review).
        assertEquals(LocalDateTime(2026, 7, 16, 9, 0), event(reply(asked, "nine please")).startLocal)
        assertEquals(LocalDateTime(2026, 7, 16, 9, 0), event(reply(asked, "9 please")).startLocal)
        assertEquals(LocalDateTime(2026, 7, 16, 19, 0), event(reply(asked, "Evening 19:00")).startLocal)
        assertEquals(LocalDateTime(2026, 7, 16, 20, 0), event(reply(asked, "8 in the evening")).startLocal)
        // ⚠ "this" belongs to the part of day, as in a request (Codex review).
        val dinner = ask("add dinner")
        assertEquals(LocalDateTime(2026, 7, 15, 20, 0), event(reply(dinner, "this evening at 8")).startLocal)
        assertEquals(LocalDateTime(2026, 7, 16, 20, 0), event(reply(dinner, "tomorrow evening at 8")).startLocal)
        // ⚠ And "this evening" names today over the question's day (Codex review).
        assertEquals(LocalDateTime(2026, 7, 15, 20, 0), event(reply(ask("add dentist tomorrow"), "this evening at 8")).startLocal)
    }

    /** ⚠ Relative before clock: "in 10 minutes" is never 10:00 (Codex review). */
    @Test
    fun aRelativeReplyIsFromNow() {
        val asked = ask("add call today")
        assertEquals(LocalDateTime(2026, 7, 15, 12, 10), event(reply(asked, "in 10 minutes")).startLocal)
        assertEquals(LocalDateTime(2026, 7, 15, 13, 0), event(reply(asked, "in an hour")).startLocal)
    }

    // ---- CL-4: the other half of the day for a bare hour (CL-D6) -------------------

    private fun plan(utterance: String) = QueryPlanner.plan(utterance, nowMs, zone, MiaActionOffer.OFFERED)

    @Test
    fun aBareHourOffersTheOtherHalfOfTheDay() {
        val dinner = event(plan("add dinner tomorrow at 8"))
        assertEquals(LocalDateTime(2026, 7, 16, 8, 0), dinner.startLocal, "the convention still decides")
        assertEquals(LocalDateTime(2026, 7, 16, 20, 0), dinner.alternativeStart)
        assertEquals(LocalDateTime(2026, 7, 16, 3, 0), event(plan("add call tomorrow at 3")).alternativeStart)
        assertEquals(LocalDateTime(2026, 7, 16, 21, 0), event(plan("add standup tomorrow at nine")).alternativeStart)
    }

    /** ⚠ Only a bare hour: whatever the words settled gets no alternative. */
    @Test
    fun aSettledTimeOffersNoAlternative() {
        listOf(
            "add dinner tomorrow at 8pm",
            "add dinner tomorrow at 8 in the evening",
            "add dinner tomorrow evening at 8",
            "add gym tomorrow at 18:30",
            "add call in an hour",
            "add lunch tomorrow at noon",
            "add lunch tomorrow at 12",
        ).forEach { assertNull(event(plan(it)).alternativeStart, it) }
    }

    /** ⚠ Never an alternative in the past: at noon, 01:00 today is gone. */
    @Test
    fun aPastAlternativeIsNotOffered() {
        val lunch = event(plan("add lunch today at 1"))
        assertEquals(LocalDateTime(2026, 7, 15, 13, 0), lunch.startLocal)
        assertNull(lunch.alternativeStart)
    }

    @Test
    fun aBareReplyOffersItToo_unlessTheQuestionKnewThePartOfDay() {
        assertEquals(LocalDateTime(2026, 7, 16, 20, 0), event(reply(ask("add dentist tomorrow"), "8")).alternativeStart)
        assertNull(event(reply(ask("add dinner this evening"), "8")).alternativeStart)
        // A chip's canonical reply is settled.
        val chip = ask("add dentist tomorrow").choices.first().reply
        assertNull(event(reply(ask("add dentist tomorrow"), chip)).alternativeStart)
    }

    /** ⚠⚠ A create verb inside a question is not a command, so nothing is asked. */
    @Test
    fun onlyACommandThatOpensWithItsVerbIsAskedAbout() {
        assertIs<QueryPlan.NoMatch>(QueryPlanner.plan("can you recommend a good book", nowMs, zone, MiaActionOffer.OFFERED))
        assertIs<QueryPlan.Clarify>(QueryPlanner.plan("can you add dentist tomorrow", nowMs, zone, MiaActionOffer.OFFERED))
        assertIs<QueryPlan.Clarify>(QueryPlanner.plan("please remind me to call mum tomorrow", nowMs, zone, MiaActionOffer.OFFERED))
    }
}

/**
 * The which-one matcher (`mia-query-layer.md` §25, CL-3), pure: a reply is read against the
 * candidates offered and nothing else.
 */
class CandidateReplyTest {

    private val lamps = QueryPlan.Clarify(
        op = org.tenshu.home.device.IssueDeviceCommand.ID,
        heard = emptyMap(),
        param = org.tenshu.home.device.IssueDeviceCommand.Params.TARGET_DEVICE_ID,
        reason = ClarifyReason.AMBIGUOUS,
        choices = listOf(Choice("Kitchen lamp", "ref:d1"), Choice("Kitchen lamp strip", "ref:d2"), Choice("Bedroom lamp", "ref:d3")),
        explanation = "Which “lamp” did you mean?",
    )

    private fun pick(reply: String) = QueryPlanner.answerCandidate(lamps, reply)
    private fun picked(reply: String) = assertIs<QueryPlanner.Pick.One>(pick(reply), reply).choice.reply

    @Test
    fun aChipANameOrAUniquePartPicksOne() {
        assertEquals("ref:d3", picked("ref:d3"))
        assertEquals("ref:d1", picked("Kitchen lamp"))
        assertEquals("ref:d3", picked("the bedroom one"))
        assertEquals("ref:d2", picked("strip please"))
    }

    @Test
    fun anOrdinalPicksByPosition() {
        assertEquals("ref:d2", picked("the second one"))
        assertEquals("ref:d3", picked("last"))
        assertEquals("ref:d1", picked("1"))
        assertNull(pick("fourth"), "beyond the candidates is no answer")
    }

    /** ⚠ Several fit: ask again with just those, never a guess between them. */
    @Test
    fun aPartThatFitsSeveralNarrowsTheQuestion() {
        val fewer = assertIs<QueryPlanner.Pick.Fewer>(pick("kitchen"))
        assertEquals(listOf("ref:d1", "ref:d2"), fewer.clarify.choices.map { it.reply })
    }

    /** ⚠ An offered label typed verbatim matches, punctuation and all (Codex review). */
    @Test
    fun aLabelTypedVerbatimMatches() {
        val rooms = lamps.copy(choices = listOf(Choice("Lamp (Bedroom)", "ref:b"), Choice("Lamp (Kitchen)", "ref:k")))
        assertEquals("ref:b", assertIs<QueryPlanner.Pick.One>(QueryPlanner.answerCandidate(rooms, "Lamp (Bedroom)")).choice.reply)
    }

    /** ⚠⚠ Two candidates with one label are never picked between, even on an exact match. */
    @Test
    fun anExactMatchOnTwoIdenticalLabelsAsksAgain() {
        val twins = lamps.copy(choices = listOf(Choice("Evening", "ref:r1"), Choice("Evening", "ref:r2"), Choice("Morning", "ref:r3")))
        val fewer = assertIs<QueryPlanner.Pick.Fewer>(QueryPlanner.answerCandidate(twins, "evening"))
        assertEquals(listOf("ref:r1", "ref:r2"), fewer.clarify.choices.map { it.reply })
    }

    /** ⚠⚠ Not a candidate: a new question, never forced onto one of them. */
    @Test
    fun anythingElseIsNoAnswer() {
        assertNull(pick("hall heater"))
        assertNull(pick("what's on tomorrow"))
        assertNull(pick(""))
    }
}
