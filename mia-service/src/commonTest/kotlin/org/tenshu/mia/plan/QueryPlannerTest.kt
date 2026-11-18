package org.tenshu.mia.plan

import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The planner is pure — utterance plus clock in, plan out — which is exactly why it can
 * be asserted this densely with no ML, no IO and no flakiness (`mia-query-layer.md`
 * MQ-D3's testability argument, the same reason `MovementSegmenter` was made pure).
 */
class QueryPlannerTest {

    private val zone = TimeZone.UTC

    /** Wednesday, 15 July 2026, 12:00 UTC. */
    private val now = LocalDateTime(2026, 7, 15, 12, 0)
        .toInstant(TimeZone.UTC)
        .toEpochMilliseconds()

    // ---- the photo intent ------------------------------------------------------

    @Test
    fun photosWithASeason_resolvesTheWindowAndNamesItBack() {
        val plan = plan("photos from last summer")

        assertIs<QueryPlan.FindPhotos>(plan)
        assertEquals(utcMs(2025, 6, 1), plan.window.startMs)
        assertEquals(utcMs(2025, 9, 1), plan.window.endMsExclusive)
        // ⚠ Naming the range is the mitigation for seasons being northern-hemisphere:
        // wrong months are visible and correctable, a wrong photo set is not.
        assertTrue(plan.explanation.contains("summer 2025"), plan.explanation)
    }

    @Test
    fun bareSeason_meansTheMostRecentOneThatHasBegun() {
        // In July 2026, summer has begun — so "summer" is 2026, not 2025.
        val plan = plan("show me photos from summer")
        assertIs<QueryPlan.FindPhotos>(plan)
        assertEquals(utcMs(2026, 6, 1), plan.window.startMs)
    }

    @Test
    fun photosWithNoTime_searchesEverythingRatherThanRefusing() {
        val plan = plan("show me my photos")

        assertIs<QueryPlan.FindPhotos>(plan)
        assertEquals(0L, plan.window.startMs)
        assertTrue(plan.window.endMsExclusive > now)
    }

    @Test
    fun namedMonth_resolvesToTheMostRecentOccurrence() {
        val plan = plan("pictures from march")

        assertIs<QueryPlan.FindPhotos>(plan)
        assertEquals(utcMs(2026, 3, 1), plan.window.startMs)
        assertEquals(utcMs(2026, 4, 1), plan.window.endMsExclusive)
    }

    @Test
    fun namedMonthNotYetReached_stepsBackAYear() {
        // November 2026 has not happened yet on a 15 July 2026 clock.
        val plan = plan("photos from november")

        assertIs<QueryPlan.FindPhotos>(plan)
        assertEquals(utcMs(2025, 11, 1), plan.window.startMs)
    }

    @Test
    fun explicitYear_isTakenLiterally() {
        val plan = plan("photos from 2024")

        assertIs<QueryPlan.FindPhotos>(plan)
        assertEquals(utcMs(2024, 1, 1), plan.window.startMs)
        assertEquals(utcMs(2025, 1, 1), plan.window.endMsExclusive)
    }

    @Test
    fun yesterday_isOneWholeDay() {
        val plan = plan("photos from yesterday")

        assertIs<QueryPlan.FindPhotos>(plan)
        assertEquals(utcMs(2026, 7, 14), plan.window.startMs)
        assertEquals(utcMs(2026, 7, 15), plan.window.endMsExclusive)
    }

    @Test
    fun lastWeek_startsOnMonday() {
        val plan = plan("photos from last week")

        assertIs<QueryPlan.FindPhotos>(plan)
        // 15 July 2026 is a Wednesday; the previous week's Monday is 6 July.
        assertEquals(utcMs(2026, 7, 6), plan.window.startMs)
        assertEquals(utcMs(2026, 7, 13), plan.window.endMsExclusive)
    }

    @Test
    fun windowsAreHalfOpen_soAdjacentOnesCannotDoubleCountMidnight() {
        val yesterday = plan("photos from yesterday") as QueryPlan.FindPhotos
        val today = plan("photos from today") as QueryPlan.FindPhotos

        assertEquals(yesterday.window.endMsExclusive, today.window.startMs)
    }

    // ---- the name intent -------------------------------------------------------

    // ---- "N units ago" (TB-D5) -------------------------------------------------

    @Test
    fun weeksAgo_snapsToThatWholeWeek() {
        val plan = plan("what did I do two weeks ago")

        // ⚠⚠ A window, never an instant. Nobody counting backwards in weeks means a particular
        // Tuesday, and answering with one day is false precision that reads as a bug.
        assertIs<QueryPlan.RecallMoments>(plan)
        assertEquals(utcMs(2026, 6, 29), plan.window.startMs)   // Monday
        assertEquals(utcMs(2026, 7, 6), plan.window.endMsExclusive)
        assertEquals("2 weeks ago", plan.window.label)
    }

    @Test
    fun daysAgo_isThatOneDay() {
        val plan = plan("what did I do 3 days ago")

        assertIs<QueryPlan.RecallMoments>(plan)
        assertEquals(utcMs(2026, 7, 12), plan.window.startMs)
        assertEquals(utcMs(2026, 7, 13), plan.window.endMsExclusive)
    }

    @Test
    fun monthsAgo_snapsToTheCalendarMonth() {
        val plan = plan("what did I do two months ago")

        assertIs<QueryPlan.RecallMoments>(plan)
        assertEquals(utcMs(2026, 5, 1), plan.window.startMs)
        assertEquals(utcMs(2026, 6, 1), plan.window.endMsExclusive)
    }

    @Test
    fun anArticleIsACountOfOne() {
        val plan = plan("what did I do a year ago")

        assertIs<QueryPlan.RecallMoments>(plan)
        assertEquals(utcMs(2025, 1, 1), plan.window.startMs)
        assertEquals(utcMs(2026, 1, 1), plan.window.endMsExclusive)
    }

    @Test
    fun theCountIsStrippedFromTheNameFragment() {
        // ⚠⚠ The measured bug this whole shape exists for: "two" and "ago" surviving into the
        // leftover turned this utterance into a file search for “two ago”.
        val plan = plan("what did I do two weeks ago")

        assertIs<QueryPlan.RecallMoments>(plan)
    }

    // ---- recall at a place (TB-D5) ---------------------------------------------

    @Test
    fun aRecallQuestionAboutAPlaceIsNotAFileSearch() {
        val plan = plan("what did I do in paris")

        assertIs<QueryPlan.RecallAtPlace>(plan)
        assertEquals("paris", plan.text)
        assertTrue(!plan.latestOnly)
    }

    @Test
    fun whenWasILastThere_asksForExactlyOneRow() {
        val plan = plan("when was I last in paris")

        assertIs<QueryPlan.RecallAtPlace>(plan)
        assertEquals("paris", plan.text)
        assertTrue(plan.latestOnly)
        // ⚠ "When were you last there" has one true answer; five would answer a different
        // question ("when have you been there").
        assertEquals(1, plan.limit)
    }

    @Test
    fun theVerbAnchorsThePlace_notOnlyAPreposition() {
        // ⚠ Unlike the photo intent, recall does not demand "in"/"at": "when did I last visit
        // the gym" anchors on the verb, and demanding a preposition would refuse the most
        // natural phrasing of the question this intent exists for.
        val plan = plan("when did I last visit the gym")

        assertIs<QueryPlan.RecallAtPlace>(plan)
        assertEquals("gym", plan.text, "the recall words must not survive into the name")
    }

    @Test
    fun aRecallQuestionThatResolvesToNothingIsRefused_neverAFileNameSearch() {
        // ⚠⚠ The regression guard. This used to be `FindFilesNamed("when")`, presented with an
        // explanation that read as though Mia had understood — worse than a refusal (MQ-D4).
        val plan = plan("when was I")

        assertIs<QueryPlan.NoMatch>(plan)
        assertTrue(plan.explanation.contains("by time or by place"), plan.explanation)
    }

    @Test
    fun aPhotoQuestionNamingAPlaceIsStillAboutPhotos() {
        // ⚠ Ordering: a photo word plus a place goes to the photo join, not to recall.
        assertIs<QueryPlan.FindPhotosAtPlace>(plan("show me my photos from paris"))
    }

    @Test
    fun leftoverWordsBecomeTheNameFragment() {
        val plan = plan("find the invoice")

        assertIs<QueryPlan.FindFilesNamed>(plan)
        assertEquals("invoice", plan.text)
    }

    @Test
    fun aQuotedPhraseOutranksEveryOtherSignal() {
        // Quoted text describes the NAME, so this is not the photo intent even though
        // "photos" is present.
        val plan = plan("photos of \"Berlin trip\"")

        assertIs<QueryPlan.FindFilesNamed>(plan)
        assertEquals("Berlin trip", plan.text)
    }

    @Test
    fun timeWordsAreStrippedBeforeLookingForAName() {
        // Without stripping, this would become a name search for "last".
        val plan = plan("photos from last summer")
        assertIs<QueryPlan.FindPhotos>(plan)
    }

    @Test
    fun everyPlanIsBounded() {
        val named = plan("find the invoice") as QueryPlan.FindFilesNamed
        val photos = plan("show me my photos") as QueryPlan.FindPhotos

        assertEquals(QueryPlanner.DEFAULT_LIMIT, named.limit)
        assertEquals(QueryPlanner.DEFAULT_LIMIT, photos.limit)
    }

    // ---- no-match --------------------------------------------------------------

    @Test
    fun anUnrecognisedUtteranceSaysSo_ratherThanAnsweringSomethingElse() {
        val plan = plan("what is the weather like")

        // ⚠⚠ The characteristic keyword-router failure is confidently answering a
        // DIFFERENT question. "weather" is not a file name, and a plan that searched for
        // it would look like an answer.
        // ⚠ This asserted `FindFilesNamed("weather like")` under the comment above until §22.
        assertIs<QueryPlan.NoMatch>(plan)
        assertTrue(plan.explanation.contains("photos"), plan.explanation)
    }

    // ---- §22: the catch-all searches file names only for a lookup ----------------

    @Test
    fun anOffTopicQuestionIsANoMatch_neverAFileSearch() {
        // ⚠⚠ §21.1 measured 22 of 24 off-topic questions ending in a file search. Each of
        // these ended there, or (with a time word) would reach the window arm next.
        listOf(
            "what's the weather tomorrow",
            "what is the weather like in berlin",
            "tell me a joke",
            "who won the game yesterday",
            "how are you",
            "why is the sky blue",
            "can you recommend a good book",
            "is it going to rain",
            "play some jazz",
            "translate good morning into french",
            "hey mia",
            "thanks a lot",
        ).forEach { utterance ->
            assertIs<QueryPlan.NoMatch>(plan(utterance), utterance)
        }
    }

    @Test
    fun aLookupStillSearchesFileNames() {
        // ⚠ The other half: narrowing must not cost a search anyone meant. A lookup word
        // anywhere, or a bare phrase, is still a name search — and still `viaCatchAll`, so the
        // model seam may still second-guess it (§16.3).
        mapOf(
            "find the invoice" to "invoice",
            "invoice" to "invoice",
            "tax return" to "tax return",
            "kayaking last week" to "kayaking",
            "where is my passport scan" to "passport scan",
            "can you find the lease agreement" to "lease agreement",
            "notes from the workshop with anna" to "notes workshop anna",
        ).forEach { (utterance, expected) ->
            val plan = plan(utterance)
            assertIs<QueryPlan.FindFilesNamed>(plan, utterance)
            assertEquals(expected, plan.text, utterance)
            assertTrue(plan.viaCatchAll, utterance)
        }
    }

    @Test
    fun fillerAloneIsNeverASearch() {
        val plan = plan("show me my files")

        assertIs<QueryPlan.NoMatch>(plan)
        assertTrue(plan.explanation.contains("photos"), plan.explanation)
    }

    @Test
    fun emptyUtteranceIsNoMatch() {
        assertIs<QueryPlan.NoMatch>(plan("   "))
    }

    @Test
    fun aDateFilterOverArbitraryFilesIsRefusedHonestly() {
        // ⚠ The chain DSL has no comparison predicate, so this cannot be lowered. Saying
        // so — and naming what CAN be done — beats quietly answering about photos.
        val plan = plan("files from last week")

        assertIs<QueryPlan.NoMatch>(plan)
        assertTrue(plan.explanation.contains("last week"), plan.explanation)
    }

    @Test
    fun aOneCharacterLeftoverIsNoiseNotASearchTerm() {
        // Searching for "x" would match half the realm and read as broken.
        assertIs<QueryPlan.NoMatch>(plan("find x"))
    }

    @Test
    fun aFileWordIsAPositiveSignal_evenInAQuestion() {
        // ⚠⚠ Codex review: "which files are named invoice" opens with a rejected word and has
        // no lookup word, so the §22 guard alone refused it. A file word is the user saying
        // "files" outright — a name search, and one a model may not overrule (§16.3).
        listOf(
            "which files are named invoice",
            "what documents are named invoice",
            "do i have a file called invoice",
        ).forEach { utterance ->
            val plan = plan(utterance)
            assertIs<QueryPlan.FindFilesNamed>(plan, utterance)
            assertEquals("invoice", plan.text, utterance)
            assertTrue(!plan.viaCatchAll, "$utterance reached the search through a file word")
        }
    }

    // ---- §14.4: a plural is a workout, not a place -----------------------------

    @Test
    fun pluralMovementNouns_areWorkoutsLikeTheirSingulars() {
        // ⚠⚠ The bug this pins, measured on a device: `FITNESS_WORDS` held "run" and not
        // "runs", so "my runs last week" was answered *"I don't know a place called “runs”"*.
        // A vocabulary miss does not degrade to "I didn't understand" — the arms below accept
        // any leftover text, so it degrades to a confident answer about something else.
        listOf(
            "my runs last week",
            "my rides last week",
            "my swims last week",
            "my walks last week",
            "my hikes last week",
        ).forEach { utterance ->
            assertIs<QueryPlan.FindWorkouts>(plan(utterance), utterance)
        }
    }

    @Test
    fun gerundsAreWorkoutsToo() {
        assertIs<QueryPlan.FindWorkouts>(plan("my swimming last week"))
        assertIs<QueryPlan.FindWorkouts>(plan("my hiking last week"))
    }

    @Test
    fun theLastInLastWeek_isATimeWordAndNotARequestForTheLatestOne() {
        // ⚠⚠ The second half of §14.4. `LATEST_WORDS` always *claimed* the time grammar had
        // consumed "last week" first — nothing checked it, so the same "last" also switched on
        // the recall arm, which takes a bare leftover as a place name. One word ran two intents.
        val plan = plan("runs last week")

        assertIs<QueryPlan.FindWorkouts>(plan)
    }

    @Test
    fun aBareNounWithATimeWindow_isNotAPlaceQuestion() {
        // The general form, with a word no vocabulary knows: widening FITNESS_WORDS fixes five
        // utterances, this fixes the class. A word we cannot place must not become a claim
        // about a place we have never heard of.
        val plan = plan("kayaking last week")

        assertTrue(plan !is QueryPlan.RecallAtPlace, "became a place question: $plan")
    }

    @Test
    fun theLatestSignalStillFiresWhenLastIsNotPartOfAWindow() {
        // ⚠ The guard must not eat the intent it is protecting: "last" before a preposition
        // is still "the most recent one".
        val plan = plan("when was I last in paris")

        assertIs<QueryPlan.RecallAtPlace>(plan)
        assertTrue(plan.latestOnly)
    }

    @Test
    fun lastSummerIsStillAWindow_notALatestRequest() {
        // "last" + a season belongs to the time expression, exactly as before.
        val plan = plan("photos from last summer")

        assertIs<QueryPlan.FindPhotos>(plan)
    }

    // ---- §14.5: the calendar read gap answers honestly --------------------------

    @Test
    fun aCalendarQuestionIsACalendarRead_notAFileNameSearch() {
        // ⚠⚠ Before §14.5 this came back as **"Searched your files for names containing
        // “calendar tomorrow”"**; §15 turned the honest refusal into a real answer.
        val plan = plan("what is on my calendar tomorrow")

        assertIs<QueryPlan.QueryCalendar>(plan)
        assertEquals(LocalDate(2026, 7, 16), plan.window.from)
        assertEquals(LocalDate(2026, 7, 16), plan.window.to)
        assertTrue(plan.explanation.contains("tomorrow"), plan.explanation)
    }

    @Test
    fun aCalendarQuestionWithNoTimeMeansToday_andSaysSo() {
        // ⚠⚠ A default the user cannot see is indistinguishable from a wrong answer, so the
        // explanation names the day it picked (MQ-D1).
        val plan = plan("what is on my calendar")

        assertIs<QueryPlan.QueryCalendar>(plan)
        assertEquals(LocalDate(2026, 7, 15), plan.window.from)
        assertTrue(plan.explanation.contains("today"), plan.explanation)
    }

    @Test
    fun anAgendaWeekdayLooksForward_unlikeTheSearchGrammar() {
        // ⚠⚠ §11.2's rule, now with a third grammar: "what did I do on saturday" is the LAST
        // Saturday, "what's on saturday" is the NEXT one. Now = Wednesday 15 July 2026.
        val plan = plan("what is on my calendar saturday")

        assertIs<QueryPlan.QueryCalendar>(plan)
        assertEquals(LocalDate(2026, 7, 18), plan.window.from)
    }

    @Test
    fun thisWeekStartsToday_becauseAnAgendaIsWhatIsLeft() {
        // ⚠ Listing Monday's meetings on Wednesday answers a question about the past.
        val plan = plan("what is on my calendar this week")

        assertIs<QueryPlan.QueryCalendar>(plan)
        assertEquals(LocalDate(2026, 7, 15), plan.window.from)
        assertEquals(LocalDate(2026, 7, 19), plan.window.to)
    }

    @Test
    fun nextWeekIsTheWholeOfIt() {
        val plan = plan("what is on my calendar next week")

        assertIs<QueryPlan.QueryCalendar>(plan)
        assertEquals(LocalDate(2026, 7, 20), plan.window.from)
        assertEquals(LocalDate(2026, 7, 26), plan.window.to)
    }

    @Test
    fun theCalendarKeepsItsPast_soYesterdayResolves() {
        // ⚠ A forward-leaning DEFAULT, not a forward-only vocabulary.
        val plan = plan("what was on my calendar yesterday")

        assertIs<QueryPlan.QueryCalendar>(plan)
        assertEquals(LocalDate(2026, 7, 14), plan.window.from)
    }

    @Test
    fun aQuestionAboutAMeetingIsAQuestion_notAnInstructionToCreateOne() {
        // ⚠⚠ §15.2: "meeting" and "appointment" were in CREATE_EVENT_WORDS, so this asked the
        // user what time to put the new event at. §10.3's rule, one source over — the VERB is what
        // makes an utterance an instruction — and it matters more here, because a device command
        // is confirmed and reversible while a misheard event persists and syncs.
        assertIs<QueryPlan.QueryCalendar>(plan("do I have a meeting tomorrow"))
        assertIs<QueryPlan.QueryCalendar>(plan("any appointments today"))
    }

    @Test
    fun aVerbStillWrites() {
        assertIs<QueryPlan.CreateEvent>(plan("add lunch with anna tomorrow at 1"))
        assertIs<QueryPlan.CreateEvent>(plan("schedule a meeting with anna tomorrow at 1"))
    }

    @Test
    fun theCalendarRefusalDoesNotSwallowTheWriteIntent() {
        // ⚠ "add ... to my calendar" carries both vocabularies, and the write must still win.
        assertIs<QueryPlan.CreateEvent>(plan("add lunch with anna to my calendar tomorrow at 1"))
    }

    // ⚠ The grammar tests are about a runtime that offers actions — Android's and desktop's —
    // since that is the one where every intent is reachable. The withheld half is at the bottom.
    private fun plan(
        utterance: String,
        actionOffer: MiaActionOffer = MiaActionOffer.OFFERED,
    ): QueryPlan = QueryPlanner.plan(utterance, now, zone, actionOffer)

    private fun utcMs(year: Int, month: Int, day: Int): Long =
        LocalDate(year, month, day).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    // ---- the routine intent (RE-5) ---------------------------------------------

    @Test
    fun runARoutine_resolvesTheNameFragmentAndNamesItBack() {
        val plan = plan("run my good morning routine")

        assertIs<QueryPlan.RunRoutine>(plan)
        assertEquals("good morning", plan.text)
        assertTrue(plan.explanation.contains("good morning"), plan.explanation)
    }

    /** ⚠ Home's UI says *automations*, the service says *routines* — a user knows only the first. */
    @Test
    fun automationIsTheSameWordAsRoutine() {
        assertIs<QueryPlan.RunRoutine>(plan("start the bedtime automation"))
    }

    /**
     * ⚠⚠ **The verb is what makes it an instruction.** "show my routines" is a request to *see*
     * them, and firing one in response would be the worst possible misreading — the same rule
     * `deviceIntent` applies to a bare "on", where the cost is also physical.
     */
    @Test
    fun aRoutineNounWithoutARunVerb_neverFiresAnything() {
        val plan = plan("show my routines")

        assertIs<QueryPlan.NoMatch>(plan)
        // ⚠ Names where they can be seen rather than refusing blankly: the tab exists.
        assertTrue(plan.explanation.contains("Automations"), plan.explanation)
    }

    /** ⚠ A run verb with nothing to run is a question, not a guess at which routine. */
    @Test
    fun runWithNoNameAsksWhichOne() {
        val plan = plan("run a routine")

        assertIs<QueryPlan.NoMatch>(plan)
        assertTrue(plan.explanation.contains("Which routine"), plan.explanation)
    }

    /**
     * ⚠⚠ Ahead of the reads: "run my evening routine" carries a time word, and routing it as a
     * search would quietly answer a different question instead of doing what was asked.
     */
    @Test
    fun aTimeWordInTheNameDoesNotTurnItIntoASearch() {
        val plan = plan("run my evening routine")

        assertIs<QueryPlan.RunRoutine>(plan)
        assertEquals("evening", plan.text)
    }

    // ---- a runtime that offers no action (`MiaActionOffer.WITHHELD`, §19) -------

    /**
     * ⚠⚠ **Each row is the SAME utterance planned twice.** The offered half is what stops this
     * from passing on a grammar that never plans an action at all — a withheld-only assertion
     * would be satisfied by deleting the three intents.
     */
    @Test
    fun anActionUtteranceIsWithheld_andTheSameOneIsPlannedWhereActionsAreOffered() {
        val cases = listOf(
            "turn off the kitchen light" to QueryPlan.ControlDevice::class,
            "run my good morning routine" to QueryPlan.RunRoutine::class,
            "add lunch with anna tomorrow at 1" to QueryPlan.CreateEvent::class,
        )

        cases.forEach { (utterance, offeredAs) ->
            val offered = plan(utterance, MiaActionOffer.OFFERED)
            assertTrue(offeredAs.isInstance(offered), "$utterance planned as $offered where actions are offered")

            val withheld = plan(utterance, MiaActionOffer.WITHHELD)
            assertIs<QueryPlan.ActionWithheld>(withheld, utterance)
            // ⚠ Says the device is the reason, not the request — the user did nothing wrong.
            assertTrue(withheld.explanation.contains("this device"), withheld.explanation)
        }
    }

    /**
     * ⚠⚠ The grammar's own *teaching* refusals are offers too: "I can add that once you tell me
     * when", "Which routine should I run?", "I can turn something on or off, but not toggle it".
     * Answering with one would coach the user through a request the runtime then refuses.
     */
    @Test
    fun theSlotQuestionsThatTeachAnAction_areWithheldToo() {
        // ⭐ With actions offered, a command missing its time now ASKS for it (§25); withheld, it
        // still withholds at the intent, ahead of any question.
        val asked = plan("add lunch with anna", MiaActionOffer.OFFERED)
        assertIs<QueryPlan.Clarify>(asked)
        assertEquals(org.tenshu.calendar.SaveEvent.Params.START_LOCAL, asked.param)
        assertIs<QueryPlan.ActionWithheld>(plan("add lunch with anna", MiaActionOffer.WITHHELD))

        val cases = listOf(
            "run a routine" to "Which routine",
            "show my routines" to "I can run a routine",
            "toggle the kitchen light" to "turn something on or off",
        )

        cases.forEach { (utterance, offer) ->
            val offered = plan(utterance, MiaActionOffer.OFFERED)
            assertIs<QueryPlan.NoMatch>(offered, utterance)
            assertTrue(offered.explanation.contains(offer), offered.explanation)

            assertIs<QueryPlan.ActionWithheld>(plan(utterance, MiaActionOffer.WITHHELD), utterance)
        }
    }

    /** ⚠⚠ Read-only means the READS still work: the same nouns, as questions, are untouched. */
    @Test
    fun withholdingActionsLeavesEveryReadAlone() {
        val reads = listOf(
            "is the kitchen light on",
            "what is on my calendar tomorrow",
            "photos from last summer",
            "how far did I run this week",
            "what did I do last saturday",
        )

        reads.forEach { utterance ->
            val offered = plan(utterance, MiaActionOffer.OFFERED)
            assertEquals(offered, plan(utterance, MiaActionOffer.WITHHELD), utterance)
            assertTrue(offered !is QueryPlan.NoMatch, "$utterance should be a read, was $offered")
        }
    }

    // ---- the question-set fixes (§23.5) ----------------------------------------
    // The clock here is Wednesday 2026-07-15, so "tomorrow" is the 16th.

    private fun event(utterance: String) = assertIs<QueryPlan.CreateEvent>(plan(utterance))

    @Test
    fun anExplicitPartOfDayOutranksTheBareHourConvention() {
        assertEquals(LocalDateTime(2026, 7, 16, 20, 0), event("add dinner tomorrow at 8 in the evening").startLocal)
        assertEquals(LocalDateTime(2026, 7, 16, 20, 0), event("add dinner tomorrow at 8 tonight").startLocal)
        // ⚠ And the other way: the convention would make a bare 3 15:00.
        assertEquals(LocalDateTime(2026, 7, 16, 3, 0), event("add flight tomorrow at 3 in the morning").startLocal)
    }

    @Test
    fun aPartOfDayCountsOnlyInTheTimeExpression() {
        // ⚠⚠ "night" here is title; "in the morning" is the time. Reading the first day part
        // anywhere proposed 20:00 (Codex review).
        val shift = event("add night shift handover tomorrow at 8 in the morning")
        assertEquals(LocalDateTime(2026, 7, 16, 8, 0), shift.startLocal)
        assertEquals("night shift handover", shift.title)
        assertEquals(LocalDateTime(2026, 7, 16, 19, 0), event("add call tomorrow evening at 7").startLocal)
        assertEquals(LocalDateTime(2026, 7, 17, 21, 0), event("add drinks friday night at 9").startLocal)
        assertEquals("good morning meeting", event("add good morning meeting tomorrow at 9").title)
    }

    @Test
    fun aPartOfDayAloneStillInventsNoTime() {
        // ⚠⚠ The guard that matters: "morning" narrows a time, it does not fix one, and a guessed
        // time is a wrong row (MQ-D1's amendment). The answer stays "when?".
        // Since §25 the answer is a question, still with no start heard.
        listOf("add dentist tomorrow morning", "add dinner this evening").forEach {
            val asked = assertIs<QueryPlan.Clarify>(plan(it), it)
            assertEquals(org.tenshu.calendar.SaveEvent.Params.START_LOCAL, asked.param, it)
            assertTrue(org.tenshu.calendar.SaveEvent.Params.START_LOCAL !in asked.heard, it)
        }
    }

    @Test
    fun numberWordsAreAnHourOnlyAfterAt() {
        val lunch = event("add lunch with anna on friday at one")
        assertEquals(LocalDateTime(2026, 7, 17, 13, 0), lunch.startLocal)
        assertEquals("lunch with anna", lunch.title)
        assertEquals(LocalDateTime(2026, 7, 16, 12, 0), event("add lunch tomorrow at noon").startLocal)
        // ⚠⚠ A clock time wins over a number word in a name (Codex review).
        assertEquals(LocalDateTime(2026, 7, 16, 20, 0), event("add lunch at five guys tomorrow at 8pm").startLocal)
        // ⚠⚠ And a number word is an hour only where the time expression ends: with no clock
        // time, a venue's "five" is no time at all, so the answer is "when?".
        // (Since §25 "when?" is a question, not a refusal: still no start invented.)
        listOf("add lunch at five guys tomorrow", "add call at one with tom", "add lunch at noon cafe tomorrow").forEach {
            val asked = assertIs<QueryPlan.Clarify>(plan(it), it)
            assertTrue(org.tenshu.calendar.SaveEvent.Params.START_LOCAL !in asked.heard, it)
        }
        assertEquals(LocalDateTime(2026, 7, 16, 13, 0), event("add lunch with anna at one tomorrow").startLocal)
        assertEquals(LocalDateTime(2026, 7, 16, 19, 0), event("add dinner tomorrow at seven in the evening").startLocal)
        assertEquals(LocalDateTime(2026, 7, 16, 9, 0), event("add standup tomorrow at nine o'clock").startLocal)
    }

    @Test
    fun aTitleDropsTheRequestScaffoldingAndNothingElse() {
        assertEquals("dentist", event("add an event dentist tomorrow at 9 in the morning").title)
        assertEquals("call mum", event("remind me to call mum tomorrow at 5").title)
        // "the" is title unless it belongs to "in the evening".
        assertEquals("the dentist", event("add the dentist tomorrow at 9").title)
        // A lone noun is the title, not scaffolding.
        assertEquals("meeting", event("add a meeting tomorrow at 10").title)
    }

    /**
     * ⚠⚠ A title is the utterance minus what the time grammar CONSUMED, never minus a word list:
     * each case below lost a word when titles were stripped of anything that looked like time.
     */
    @Test
    fun aTitleKeepsEveryWordTheTimeDidNotUse() {
        assertEquals("lunch at five guys", event("add lunch at five guys tomorrow at 8pm").title)
        assertEquals("one on one with tom", event("add one on one with tom tomorrow at 3").title)
        assertEquals("book club", event("add book club tomorrow at 7").title)
        assertEquals("table at nopa", event("book a table at nopa tomorrow at 7").title)
        assertEquals("morning standup", event("add morning standup tomorrow at 9").title)
        assertEquals("call with tom", event("add call with tom in an hour").title)
        assertEquals("call with a client", event("add call with a client on next friday at 10").title)
        assertEquals("standup", event("add standup tomorrow at nine o'clock").title)
        assertEquals("call", event("add call this evening at 7").title)
    }

    @Test
    fun lastSeasonIsTheOneThatHasEnded() {
        // July: summer 2026 is under way, so "last summer" is 2025 (pinned above). November:
        // summer 2026 has ended, so it is 2026.
        val november = LocalDateTime(2026, 11, 16, 12, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        val plan = assertIs<QueryPlan.FindPhotos>(
            QueryPlanner.plan("photos from last summer", november, zone, MiaActionOffer.OFFERED),
        )
        assertEquals(utcMs(2026, 6, 1), plan.window.startMs)
    }

    @Test
    fun aLabelIsSearchedInTheSingular_aLikenessIsNot() {
        fun label(u: String) = assertIs<QueryPlan.FindPhotosOfLabel>(plan(u)).text
        assertEquals("cat", label("pictures of cats"))
        assertEquals("box", label("photos of boxes"))
        assertEquals("glass", label("photos of glasses"))
        assertEquals("bus", label("photos of a bus"))
        // ⚠⚠ Truncation only: every label that matched the original phrase must still match. A
        // rewrite ("puppies" → "puppy", "davies" → "davy") would lose a stored label or a named face.
        listOf("puppies", "james davies", "cats", "boxes", "williams").forEach { stored ->
            assertTrue(stored.contains(label("photos of $stored")), "lost the label \"$stored\"")
        }
        // ⚠ A likeness is a description; its wording is the query.
        assertEquals("dogs playing", assertIs<QueryPlan.FindPhotosLike>(plan("photos that look like dogs playing")).text)
    }

    @Test
    fun aCalendarCueNeedsACalendarTime() {
        assertIs<QueryPlan.QueryCalendar>(plan("what's on tomorrow"))
        assertIs<QueryPlan.QueryCalendar>(plan("am I free on friday"))
        // No day named: not the calendar.
        assertTrue(plan("do I have anything from paris") !is QueryPlan.QueryCalendar)
        // ⚠⚠ A past question gets a past window now (the agenda grammar reads "last week"), so the
        // cue routes it. On this clock (Wed 2026-07-15) last week is Mon 6 to Sun 12 July.
        val past = assertIs<QueryPlan.QueryCalendar>(plan("was I busy last week"))
        assertEquals(LocalDate(2026, 7, 6), past.window.from)
        assertEquals(LocalDate(2026, 7, 12), past.window.to)
        assertIs<QueryPlan.QueryCalendar>(plan("am I busy next week"))
        // ⚠ A file word outranks the inferred cue (only a calendar noun outranks the file arm).
        assertIs<QueryPlan.FindFilesNamed>(plan("find files named plans for next week"))
        // "what … on" apart is a timeline question.
        assertIs<QueryPlan.RecallMoments>(plan("what did I do on saturday"))
    }

    /**
     * ⚠⚠ The calendar keeps its past: a past question reads back, never the rest of this week (the
     * defect: "what was on my calendar last week" answered about the coming days).
     */
    @Test
    fun aPastCalendarQuestionGetsAPastWindow() {
        fun window(u: String) = assertIs<QueryPlan.QueryCalendar>(plan(u), u).window.let { it.from to it.to }
        // Wed 2026-07-15.
        assertEquals(LocalDate(2026, 7, 6) to LocalDate(2026, 7, 12), window("what was on my calendar last week"))
        assertEquals(LocalDate(2026, 7, 11) to LocalDate(2026, 7, 12), window("what was on my calendar last weekend"))
        assertEquals(LocalDate(2026, 7, 10) to LocalDate(2026, 7, 10), window("what was on my calendar last friday"))
        assertEquals(LocalDate(2026, 7, 10) to LocalDate(2026, 7, 10), window("what meetings were on friday"))
        assertEquals(LocalDate(2026, 7, 13) to LocalDate(2026, 7, 15), window("what was on my calendar this week"))
        // ⚠⚠ A past the grammar cannot place is refused, never answered about other dates.
        assertIs<QueryPlan.NoMatch>(plan("what was on my calendar a week ago"))
        assertTrue(plan("was I busy a week ago") !is QueryPlan.QueryCalendar)
        assertTrue(plan("was I busy the previous week") !is QueryPlan.QueryCalendar)
        // …and the future still reads forward.
        assertEquals(LocalDate(2026, 7, 17) to LocalDate(2026, 7, 17), window("what is on my calendar friday"))
        assertEquals(LocalDate(2026, 7, 20) to LocalDate(2026, 7, 26), window("what is on my calendar next week"))
    }

    @Test
    fun dimmingToALevelIsACommandWithTheLevel() {
        val dim = assertIs<QueryPlan.ControlDevice>(plan("dim the bedroom light to 30 percent"))
        assertEquals("bedroom light", dim.text)
        assertEquals(30, dim.level)
        assertEquals(30, assertIs<QueryPlan.ControlDevice>(plan("set the kitchen lamp to 30%")).level)
        assertEquals(0, assertIs<QueryPlan.ControlDevice>(plan("dim the kitchen lamp to 0 percent")).level)
        // ⚠ No level heard is never a guessed one, and nothing above 100 exists.
        assertIs<QueryPlan.NoMatch>(plan("dim the bedroom light"))
        assertIs<QueryPlan.NoMatch>(plan("dim the bedroom light to 150 percent"))
        // ⚠⚠ A level verb inside a question describes a state; it is a read, not a command.
        assertIs<QueryPlan.FindDevices>(plan("is the kitchen lamp set to 30 percent"))
        assertIs<QueryPlan.FindDevices>(plan("is the kitchen lamp set to 30 percent", MiaActionOffer.WITHHELD))
        assertEquals(30, assertIs<QueryPlan.ControlDevice>(plan("can you dim the kitchen lamp to 30 percent")).level)
        // ⚠ Every accepted lead-in leaves the device name, or the search finds nothing (Codex review).
        listOf("would you", "hey mia", "will you", "please", "ok mia").forEach { lead ->
            assertEquals("kitchen lamp", assertIs<QueryPlan.ControlDevice>(plan("$lead dim the kitchen lamp to 30 percent")).text, lead)
        }
        // ⚠ Withheld where actions are not offered, as on/off is.
        assertIs<QueryPlan.ActionWithheld>(plan("dim the bedroom light to 30 percent", MiaActionOffer.WITHHELD))
    }

    @Test
    fun germanSmallTalkIsANoMatch_notAFileSearch() {
        listOf("danke", "wie spät ist es", "erzähl mir einen witz", "hallo mia").forEach {
            assertIs<QueryPlan.NoMatch>(plan(it), it)
        }
    }
}
