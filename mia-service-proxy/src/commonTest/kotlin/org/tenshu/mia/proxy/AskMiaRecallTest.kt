package org.tenshu.mia.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.get
import org.tenshu.core.place.Place
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaHit
import org.tenshu.mia.askMia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recall — "what did I do in Paris", "when was I last at the gym"
 * (`takeout-import-and-recall.md` TB-D5), end to end through the op.
 *
 * ⚠⚠ **Every utterance here used to answer with a file-name search.** "when was I last in paris"
 * searched the user's *files* for the string `"when paris"`, with an explanation that read as
 * though Mia had understood. These tests exist as much to keep that from coming back as to prove
 * the new intent: the last one asserts the refusal that replaced the fallback.
 */
class AskMiaRecallTest {

    /** Wednesday 15 July 2026, 12:00 UTC. */
    private val now = 1_784_116_800_000L
    private val zone = "UTC"
    private val day = 24 * 60 * 60 * 1000L

    private suspend fun ask(proxy: TestProxy, utterance: String): MiaAnswer {
        val result = proxy.miaService().askMia(utterance, now, zone)
        if (result is TResult.Failure<*>) error("askMia failed: ${result.error}")
        return (result as TResult.Success<*>).value as MiaAnswer
    }

    private fun MiaAnswer.hitList(): List<MiaHit> = hits.toList().filterIsInstance<MiaHit>()

    private fun MiaAnswer.titles(): List<String> = hitList().map { it.title.get() }

    private fun TestProxy.seedPlace(id: String, label: String) {
        executionContext.saveData(
            Place.create(
                context = tContext,
                placeId = id,
                latE7 = 0,
                lngE7 = 0,
                capturedAt = UnixInstantDto(now),
                label = label,
                city = "Paris",
            )
        )
    }

    private fun TestProxy.seedMomentAt(id: String, placeId: String, atMs: Long, title: String) {
        executionContext.saveData(
            Moment.create(
                context = tContext,
                momentId = id,
                timestamp = UnixInstantDto(atMs),
                kind = "Visit",
                title = title,
                subject = SubjectRef.create(tContext, kind = "place", id = placeId),
            )
        )
    }

    // ---- "N units ago" ----------------------------------------------------------

    @Test
    fun aCountOfWeeksAgoResolvesToThatWeek() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedMomentAt("m1", "p1", now - 14 * day, "The market")
        proxy.seedMomentAt("m2", "p1", now - 2 * day, "This week's thing")

        val answer = ask(proxy, "what did I do two weeks ago")

        // ⚠ A window, not an instant: "two weeks ago" is that *week*, so a moment two days off
        // the exact 14-day mark is still in it — and this week's is not.
        assertEquals(listOf("The market"), answer.titles())
        assertTrue(answer.explanation.get().contains("2 weeks ago"), answer.explanation.get())
    }

    @Test
    fun aCountOfDaysAgoResolvesToThatDay() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedMomentAt("m1", "p1", now - 3 * day, "Three days back")
        proxy.seedMomentAt("m2", "p1", now - 4 * day, "Four days back")

        assertEquals(listOf("Three days back"), ask(proxy, "what did I do 3 days ago").titles())
    }

    @Test
    fun aBareArticleCountsAsOne() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedMomentAt("m1", "p1", now - 8 * day, "Last week's thing")

        // "a week ago" — the count word is an article, and it means one.
        assertEquals(listOf("Last week's thing"), ask(proxy, "what did I do a week ago").titles())
    }

    // ---- Recall at a place ------------------------------------------------------

    @Test
    fun momentsAtAPlaceReadThroughTheNestedSubjectField() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedPlace("p1", "Marienplatz")
        proxy.seedPlace("p2", "Somewhere else")
        proxy.seedMomentAt("m1", "p1", now - 2 * day, "Coffee")
        proxy.seedMomentAt("m2", "p2", now - 1 * day, "Not this one")

        val answer = ask(proxy, "what did I do in marienplatz")

        // ⭐⭐ The first nested-field predicate in the codebase — `Moment.subject.id`, reaching
        // through the `SubjectRef` composite. Everything else filters a top-level field.
        assertEquals(listOf("Coffee"), answer.titles())
        assertEquals(Moment.ID, answer.hitList().single().targetType)
    }

    @Test
    fun whenWasILastThereReturnsExactlyTheMostRecentOne() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedPlace("p1", "The Gym")
        proxy.seedMomentAt("m1", "p1", now - 30 * day, "Old visit")
        proxy.seedMomentAt("m2", "p1", now - 2 * day, "Recent visit")
        proxy.seedMomentAt("m3", "p1", now - 10 * day, "Middle visit")

        val answer = ask(proxy, "when did I last visit the gym")

        // ⚠ One row, and it is the newest: "when were you last there" has one true answer, and a
        // handful in arbitrary order would be answering "when have you been there".
        assertEquals(listOf("Recent visit"), answer.titles())
    }

    @Test
    fun recencyIsEstablishedAcrossEveryMatchingPlace() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        // One name, two rows — and the newer visit belongs to the one that resolves second.
        proxy.seedPlace("p1", "Gym Mitte")
        proxy.seedPlace("p2", "Gym Kreuzberg")
        proxy.seedMomentAt("m1", "p1", now - 20 * day, "Mitte, ages ago")
        proxy.seedMomentAt("m2", "p2", now - 1 * day, "Kreuzberg, yesterday")

        // ⚠⚠ Taking the first row of a concatenation would answer about whichever place happened
        // to resolve first, which is not a fact about the user's life.
        assertEquals(listOf("Kreuzberg, yesterday"), ask(proxy, "when was I last at the gym").titles())
    }

    @Test
    fun aPlaceTheUserHasNeverBeenIsSaidSoRatherThanAnsweredEmpty() = runTest {
        val proxy = createMiaProxy(backgroundScope)
        proxy.seedPlace("p1", "Marienplatz")

        val answer = ask(proxy, "when was I last in lisbon")

        // ⚠⚠ `NO_MATCH`, not `ANSWERED` — nothing was searched, so claiming an absence would
        // assert something never established, and the chat surface turns an empty `ANSWERED` into
        // "… — nothing matched." (§14.1).
        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertEquals(emptyList(), answer.hitList())
        assertTrue(answer.explanation.get().contains("don't know"), answer.explanation.get())
    }

    // ---- The refusal that replaced the fallback ---------------------------------

    @Test
    fun aRecallQuestionNeverBecomesAFileNameSearch() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        // ⚠⚠ The regression guard for the measured bug: this used to search files for "when" and
        // report it as an answer. Naming neither a time nor a place, it is now refused honestly.
        val answer = ask(proxy, "when was I")

        assertEquals(MiaAnswer.VERDICT_NO_MATCH, answer.verdict.get())
        assertTrue(answer.explanation.get().contains("by time or by place"), answer.explanation.get())
    }

    @Test
    fun anOrdinaryFileSearchStillWorks() = runTest {
        val proxy = createMiaProxy(backgroundScope)

        // ⚠ The guard must not swallow the file intent it sits in front of: no recall word here,
        // so this is still a name search.
        val answer = ask(proxy, "find my tax return")

        assertTrue(
            answer.explanation.get().contains("tax return"),
            answer.explanation.get(),
        )
    }
}
