@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.JsRuntimeBuilder
import app.tenshu.sdk.internal.MiaPlugin
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.tenshu.calendar.SaveEvent
import org.tenshu.calendar.common.recurrence.OccurrenceId
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.core.health.Workout
import org.tenshu.core.health.recordWorkout
import org.tenshu.core.place.Place
import org.tenshu.core.place.recordPlace
import org.tenshu.health.client.healthService
import org.tenshu.home.device.IssueDeviceCommand
import org.tenshu.inference.ClassifyIntent
import org.tenshu.inference.PhotosLike
import org.tenshu.mia.AskMia
import org.tenshu.mia.ConfirmMiaAction
import org.tenshu.mia.MiaSource
import org.tenshu.place.client.placeService
import org.tenshu.preference.ClearSetting
import org.tenshu.preference.WriteSetting
import org.tenshu.preference.client.clear
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.set
import org.tenshu.routine.RunRoutineNow
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boundary-contract tests (`docs/testing.md` §9.3) for `Mia` (`web-app-suite.md` Wave 4),
 * against the **real** in-process runtime — the native planner, lowering and validator, run by
 * `MiaPlugin` — with calendar, health and place rows seeded through the real service ops.
 *
 * ⚠ `GlobalScope.promise`, not `runTest`: the service ops never complete on the test dispatcher.
 * ⚠ The jsTest suite shares ONE `JsRuntime`, so every question is asked against its own clock in a
 * random far-future window (years 2300–2900) and its own random names, and assertions look only at
 * rows this test seeded. ⚠ The conversation is shared too: history assertions are relative.
 *
 * They assert the keys `web/sdk/src/mia.ts` publishes and the verdicts native `AskMiaImplementation`
 * returns (`noMatch`, never an empty `answered`, for every refusal) — not "returns something".
 */
class MiaWebContractTest : SdkRuntimeTest() {

    private val client get() = JsRuntime.tenshuClient
    private val ctx get() = client.environment.tContext

    /** Letters only: the grammar tokenises on words, and a digit run is not one. */
    private fun word(n: Int = 8) = (1..n).map { ('a'..'z').random() }.joinToString("")

    private fun farDay(): LocalDate =
        LocalDate.fromEpochDays(LocalDate(2300, 1, 1).toEpochDays() + (0..220_000).random())

    private fun LocalDate.atUtc(hour: Int, minute: Int = 0): Long =
        atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + (hour * 60 + minute) * 60_000L

    private fun assertOk(result: TResult<Data?>, what: String) =
        result.fold(onSuccess = {}, onFailure = { throw AssertionError("$what failed: $it") })

    private suspend fun ok(promise: Promise<dynamic>, what: String): dynamic {
        val result = promise.await()
        assertEquals(true, result.ok, "$what must succeed; got ${JSON.stringify(result)}")
        return result
    }

    private suspend fun ask(utterance: String, nowMs: Long, zone: String = "UTC"): dynamic {
        val result = Mia.askAt(utterance, nowMs, zone)
        assertEquals(true, result.ok, "ask '$utterance' must succeed; got ${JSON.stringify(result)}")
        return result.turn.answer
    }

    private fun titles(answer: dynamic): List<String> =
        answer.hits.unsafeCast<Array<dynamic>>().map { it.title as String }

    private fun assertRefused(answer: dynamic, what: String) {
        // ⚠⚠ The refusal shape: `noMatch` with nothing cited. An `answered` with no hits here is the
        // §14.1 defect — it renders as "… — nothing matched." and asserts an absence nobody checked.
        assertEquals("noMatch", answer.verdict, "$what: ${JSON.stringify(answer)}")
        assertEquals(0, answer.hits.unsafeCast<Array<dynamic>>().size, "$what cites nothing")
    }

    // ── the three sources ─────────────────────────────────────────────────────────────────────

    @Test
    fun aCalendarQuestion_answersWithTheOccurrencesOfThatDay() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val day = farDay()
        val title = "Standup ${word()}"
        val cal = ok(Calendar.saveCalendar("", "Mia ${word()}", 0x334285F4, false), "saveCalendar").calendarId as String
        val event = ok(Calendar.saveEvent(draft(cal, title, "${day}T09:00", "${day}T09:30")), "saveEvent").eventId as String
        val decoy = "Decoy ${word()}"
        ok(Calendar.saveEvent(draft(cal, decoy, "${day.plusDays(2)}T09:00", "${day.plusDays(2)}T10:00")), "decoy")

        val answer = ask("what is on my calendar tomorrow", nowMs = day.plusDays(-1).atUtc(12))

        assertEquals("answered", answer.verdict, JSON.stringify(answer))
        assertTrue(title in titles(answer), "tomorrow's event is cited: ${titles(answer)}")
        assertFalse(decoy in titles(answer), "the day after is not tomorrow")
        val hit = answer.hits.unsafeCast<Array<dynamic>>().first { it.title == title }
        // The occurrence, not the series: `(eventId, recurrenceId)` — what a tap would open.
        assertEquals(OccurrenceId.TYPE, hit.target.type)
        assertEquals(listOf(event, "${day}T09:00"), hit.target.keys.unsafeCast<Array<String>>().toList())
        assertTrue((answer.explanation as String).isNotBlank(), "the answer says what it searched")
    }

    @Test
    fun aHealthQuestion_answersWithTheWorkoutsThatStartedInTheWindow() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val day = farDay()
        val id = "mia-${word()}"
        val title = "Run ${word()}"
        seedWorkout(id, title, startMs = day.atUtc(7))
        val old = "Old ${word()}"
        seedWorkout("mia-${word()}", old, startMs = day.plusDays(-5).atUtc(7))

        val answer = ask("workouts yesterday", nowMs = day.plusDays(1).atUtc(12))

        assertEquals("answered", answer.verdict, JSON.stringify(answer))
        assertEquals(listOf(title), titles(answer).filter { it == title || it == old })
        val hit = answer.hits.unsafeCast<Array<dynamic>>().first { it.title == title }
        assertEquals(Workout.ID, hit.target.type)
        assertEquals(listOf(id), hit.target.keys.unsafeCast<Array<String>>().toList())
        assertEquals(day.atUtc(7).toDouble(), hit.atMs, "a workout is cited at its start")
    }

    @Test
    fun aPlaceQuestion_answersWithThePlaceItNames() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val name = word()
        val id = "mia-place-${word()}"
        val city = "City ${word()}"
        seedPlace(id, label = "${name.replaceFirstChar { it.uppercase() }} Coffee", city = city)
        seedPlace("mia-place-${word()}", label = "${word()} Bakery", city = city)

        val answer = ask("where was that café $name", nowMs = Date.now().toLong())

        assertEquals("answered", answer.verdict, JSON.stringify(answer))
        val hits = answer.hits.unsafeCast<Array<dynamic>>()
        assertEquals(1, hits.size, "only the named place: ${titles(answer)}")
        assertEquals(Place.ID, hits[0].target.type)
        assertEquals(listOf(id), hits[0].target.keys.unsafeCast<Array<String>>().toList())
        assertEquals(city, hits[0].subtitle)
    }

    // ── refusals ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun aQuestionOutsideTheGrammar_isNotUnderstood_neverAnEmptyAnswer() = GlobalScope.promise {
        JsRuntime.awaitReady()
        // ⚠ Not "what is the weather like": natively that falls into the file-name catch-all and is
        // `answered` (`QueryPlannerTest`, §16.3's measured gap). The web mirrors it, not fixes it.
        val answer = ask("show me my files", nowMs = Date.now().toLong())
        assertRefused(answer, "outside the grammar")
        assertTrue((answer.explanation as String).isNotBlank(), "Mia says what it can do instead")
    }

    /**
     * ⚠⚠ The refusal case. Mutation-checked: with `QueryPlanner.plan`'s grant check bypassed (and,
     * separately, with this file's verdict mapping turning `noMatch` into `answered`) this test goes
     * red — see the PR body.
     */
    @Test
    fun aQuestionNeedingARevokedGrant_isRefusedByName_neverAnEmptyAnswer() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val day = farDay()
        val title = "Ride ${word()}"
        seedWorkout("mia-${word()}", title, startMs = day.atUtc(7))
        val now = day.plusDays(1).atUtc(12)
        val prefs = client.preferenceService
        // Control: granted, the same question finds the row — so the refusal below is the grant's.
        assertTrue(title in titles(ask("workouts yesterday", now)))

        assertOk(prefs.set(MiaSource.FITNESS.preference, false), "revoke")
        try {
            val answer = ask("workouts yesterday", now)
            assertRefused(answer, "revoked fitness grant")
            assertTrue("workouts" in (answer.explanation as String), "the refusal names the source: ${answer.explanation}")
            val grants = ok(Mia.grants(), "grants").grants.unsafeCast<Array<dynamic>>()
            assertEquals(false, grants.first { it.source == "FITNESS" }.read, "the view shows the revocation")
        } finally {
            assertOk(prefs.clear(MiaSource.FITNESS.preference), "restore")
        }
        assertTrue(title in titles(ask("workouts yesterday", now)), "a restored grant answers on the next question")
    }

    @Test
    fun writeShapedUtterances_areRefused_andWriteNothing() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val day = farDay()
        val now = day.plusDays(-1).atUtc(12)
        // Granting the calendar write would make "add …" a confirmation on Android; the tab must
        // still refuse it — the refusal is the runtime's, not the grant's.
        val prefs = client.preferenceService
        val write = MiaSource.CALENDAR.writePreference!!
        assertOk(prefs.set(write, true), "grant calendar write")
        try {
            // ⚠⚠ A device and a routine the utterances name, so each would resolve where actions are
            // offered: without them "no such device" is also a `noMatch`, and dropping WITHHELD stayed green.
            val seeded = MiaWithheldFixture.seed()
            // ⚠ Every case is checked before failing, so a regression names all three, not the first.
            val wrong = seeded.utterances.mapNotNull { (utterance, withheld) ->
                val result = Mia.askAt(utterance, now, "UTC")
                val answer = result.turn?.answer
                val refused = result.ok == true && answer.verdict == "noMatch" &&
                    answer.hits.unsafeCast<Array<dynamic>>().isEmpty() && answer.explanation == withheld
                if (refused) null else "'$utterance' → ${JSON.stringify(result)}"
            }
            assertTrue(wrong.isEmpty(), "refused as withheld (noMatch, nothing cited, the WITHHELD sentence): $wrong")
            val agenda = ok(Calendar.occurrences(day.toString(), day.toString(), false, "UTC"), "occurrences")
                .occurrences.unsafeCast<Array<dynamic>>()
            assertTrue(agenda.none { it.title == seeded.eventTitle }, "no event was written")
        } finally {
            assertOk(prefs.clear(write), "restore")
        }
    }

    @Test
    fun theTabMapsAskMiaOnly_noConfirmation_noModel() = GlobalScope.promise {
        JsRuntime.awaitReady()
        assertNotNull(MiaPlugin.miaWebOperation(AskMia.ID))
        assertNull(MiaPlugin.miaWebOperation(ConfirmMiaAction.ID), "confirmMiaAction is excluded")
        // Nothing else in the chain maps the action op, the model planner or visual search.
        for (id in listOf(ConfirmMiaAction.ID, ClassifyIntent.ID, PhotosLike.ID)) {
            assertNull(implementationFactory(id), "core maps no $id")
            val mapped = JsRuntimeBuilder.orderedPlugins.filter { it.operationFactory(JsRuntime)?.invoke(id) != null }
            assertTrue(mapped.isEmpty(), "no plugin maps $id; got ${mapped.map { it.id }}")
        }
        assertTrue(MiaPlugin in JsRuntimeBuilder.orderedPlugins)
    }

    @Test
    fun aVisualSearchQuestion_couldNotLook_neverAnEmptyAnswer() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val answer = ask("photos like a sunset over water", nowMs = Date.now().toLong())
        assertEquals("unavailable", answer.verdict, JSON.stringify(answer))
        assertEquals(0, answer.hits.unsafeCast<Array<dynamic>>().size)
    }

    // ── input ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun badInput_isRefusedBeforeAnythingIsAsked_andRecordsNothing() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val before = ok(Mia.history(), "history").conversations.unsafeCast<Array<dynamic>>()
            .sumOf { it.turnCount as Int }
        assertEquals("error_mia_empty_utterance", Mia.askAt("   ", 0, "UTC").error.kind)
        assertEquals("error_mia_utterance_too_long", Mia.askAt("a".repeat(MAX_UTTERANCE_CHARS + 1), 0, "UTC").error.kind)
        assertEquals("error_mia_invalid_zone", Mia.askAt("workouts today", 0, "Mars/Olympus").error.kind)
        assertEquals(true, Mia.askAt("a".repeat(MAX_UTTERANCE_CHARS), Date.now().toLong(), "").ok, "the bound is inclusive")
        val after = ok(Mia.history(), "history").conversations.unsafeCast<Array<dynamic>>()
            .sumOf { it.turnCount as Int }
        assertEquals(before + 1, after, "only the accepted question is a turn")
    }

    // ── history ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun historyKeepsEveryTurn_andPagesNewestFirst_withoutGapsOrRepeats() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val now = Date.now().toLong()
        val asked = (1..7).map { "where was that café ${word()}" }
        var conversationId = ""
        for (utterance in asked) {
            conversationId = ok(Mia.ask(utterance, "UTC"), utterance).conversationId as String
        }

        val history = ok(Mia.history(), "history")
        val conversations = history.conversations.unsafeCast<Array<dynamic>>()
        assertEquals(1, conversations.size, "one conversation per session")
        assertEquals(conversationId, conversations[0].conversationId)
        assertEquals(false, history.replying)
        val total = conversations[0].turnCount as Int
        assertTrue(total >= asked.size)
        assertTrue((conversations[0].updatedAtMs as Double) >= now.toDouble())

        // Page the whole conversation, 3 at a time.
        val seen = mutableListOf<dynamic>()
        var cursor: String? = null
        var pages = 0
        do {
            assertTrue(pages++ < 1_000, "paging did not terminate")
            val page = ok(Mia.turnsPage(conversationId, cursor, 3), "turnsPage")
            val turns = page.turns.unsafeCast<Array<dynamic>>()
            assertTrue(turns.size in 1..3)
            seen.addAll(turns)
            cursor = page.nextCursor as String?
        } while (cursor != null)

        assertEquals(total, seen.size, "every turn, once")
        assertEquals(total, seen.map { it.turnId as String }.toSet().size, "no repeats")
        // Newest first: the last seven asked, in reverse, head the conversation.
        assertEquals(asked.reversed(), seen.take(asked.size).map { it.utterance as String })
        assertEquals(conversations[0].title, seen.last().utterance, "titled by its first question")
        seen.take(asked.size).forEach { assertTrue(it.answer.verdict == "answered" || it.answer.verdict == "noMatch") }
    }

    @Test
    fun aTurnAskedAfterACursor_neverShiftsTheNextPage() = GlobalScope.promise {
        JsRuntime.awaitReady()
        repeat(4) { ok(Mia.ask("what is the weather like", "UTC"), "seed") }
        val id = ok(Mia.history(), "history").conversations.unsafeCast<Array<dynamic>>()[0].conversationId as String
        val head = ok(Mia.turnsPage(id, null, 2), "head")
        val next = head.nextCursor as String
        val expected = ok(Mia.turnsPage(id, next, 2), "page 2").turns.unsafeCast<Array<dynamic>>().map { it.turnId as String }

        ok(Mia.ask("what is the weather like", "UTC"), "a new turn")

        val again = ok(Mia.turnsPage(id, next, 2), "page 2 again").turns.unsafeCast<Array<dynamic>>().map { it.turnId as String }
        assertEquals(expected, again, "keyset: a new turn sits above the cursor")
    }

    @Test
    fun turnsPage_refusesBadCursorsSizesAndUnknownConversations() = GlobalScope.promise {
        JsRuntime.awaitReady()
        ok(Mia.ask("what is the weather like", "UTC"), "seed")
        val id = ok(Mia.history(), "history").conversations.unsafeCast<Array<dynamic>>()[0].conversationId as String
        for (bad in listOf("x", "m1.", "m1.-1", "m1.01", "m1.0", "m1.1a", "m2.5")) {
            assertEquals("error_mia_bad_cursor", Mia.turnsPage(id, bad, 10).await().error.kind, bad)
        }
        assertEquals("error_mia_bad_page_size", Mia.turnsPage(id, null, 0).await().error.kind)
        assertEquals("error_mia_bad_page_size", Mia.turnsPage(id, null, MAX_TURN_PAGE_SIZE + 1).await().error.kind)
        assertEquals("error_mia_conversation_not_found", Mia.turnsPage("c0-nope", null, 10).await().error.kind)
    }

    @Test
    fun observeHistory_pushesEachTurn() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val pushes = mutableListOf<dynamic>()
        val errors = mutableListOf<String>()
        val stop = Mia.observeHistory({ pushes.add(it) }, { errors.add(it) })
        try {
            waitFor { pushes.isNotEmpty() }
            val before = pushes.last().conversations.unsafeCast<Array<dynamic>>().sumOf { it.turnCount as Int }
            ok(Mia.ask("what is the weather like", "UTC"), "ask")
            waitFor { pushes.last().conversations.unsafeCast<Array<dynamic>>().sumOf { it.turnCount as Int } == before + 1 }
            assertTrue(pushes.any { it.replying == true }, "replying is pushed while the answer is composed")
            assertEquals(false, pushes.last().replying)
        } finally {
            stop()
        }
        assertTrue(errors.isEmpty(), "$errors")
    }

    /**
     * ⚠⚠ `Session.clear` (sign-out, and the apex's lock, which keeps the runtime) must void every ask
     * made before it — one not yet started and one the proxy is still answering — or the pre-lock
     * question reappears in the next session's history.
     */
    @Test
    fun aClearVoidsAsksMadeBeforeIt_queuedOrInFlight() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val queued = "where was that café ${word()}"
        val pending = Mia.ask(queued, "UTC")
        MiaConversations.clear()
        val voided = pending.await()
        assertEquals("error_mia_session_cleared", voided.error?.kind, "asked before the clear: ${JSON.stringify(voided)}")

        val inFlight = "where was that café ${word()}"
        // ⚠ Unconfined, so the clear lands INSIDE the ask — after its first generation check, before the
        // proxy answers — rather than whenever a poll happens to look (an answer can take a few ms).
        val clearer = GlobalScope.launch(Dispatchers.Unconfined) {
            MiaConversations.history.first { it.replying }
            MiaConversations.clear()
        }
        val running = Mia.ask(inFlight, "UTC")
        val late = running.await()
        assertEquals("error_mia_session_cleared", late.error?.kind, "answered after the clear: ${JSON.stringify(late)}")
        assertTrue(clearer.isCompleted, "the clear ran while the ask was in flight")

        val utterances = ok(Mia.history(), "history").conversations.unsafeCast<Array<dynamic>>()
        assertTrue(utterances.isEmpty(), "nothing survived the clear: ${JSON.stringify(utterances)}")
        // And the next session starts empty: its one conversation holds only its own question.
        val next = "where was that café ${word()}"
        assertEquals("answered", ok(Mia.ask(next, "UTC"), "after").turn.answer.verdict)
        val after = ok(Mia.history(), "history after").conversations.unsafeCast<Array<dynamic>>()
        assertEquals(1, after.size, "one conversation: ${JSON.stringify(after)}")
        assertEquals(next, after[0].title, "titled by the next session's first question, not a voided one")
        assertEquals(1, after[0].turnCount, "no voided turn rode into the next session")
    }

    // ── grants ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theGrantsView_isEverySource_withTheProxysDefaults() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val grants = ok(Mia.grants(), "grants").grants.unsafeCast<Array<dynamic>>()
        assertEquals(MiaSource.entries.map { it.name }, grants.map { it.source as String })
        // Untouched (every test restores what it flips): reads on, the one write off, only Calendar
        // has a write line at all.
        grants.forEach { assertEquals(true, it.read, it.source as String) }
        assertEquals(false, grants.first { it.source == "CALENDAR" }.write)
        assertTrue(grants.filter { it.source != "CALENDAR" }.all { it.write == null })
    }

    @Test
    fun observeGrants_pushesARevocation() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val pushes = mutableListOf<dynamic>()
        val stop = Mia.observeGrants({ pushes.add(it) }, { throw AssertionError(it) })
        val prefs = client.preferenceService
        try {
            waitFor { pushes.isNotEmpty() }
            assertOk(prefs.set(MiaSource.PLACES.preference, false), "revoke")
            waitFor {
                pushes.last().grants.unsafeCast<Array<dynamic>>().first { it.source == "PLACES" }.read == false
            }
        } finally {
            stop()
            assertOk(prefs.clear(MiaSource.PLACES.preference), "restore")
        }
    }

    // ── the grant switches ────────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ The decided contract (`web-app-suite.md` §8): a grant turned off ON THE WEB
     * makes the next question needing it `noMatch`, naming the source — never an empty `answered`
     * (§14.1) — and nothing is written but the grant row itself. Then turned on again, the same
     * question answers. Writes are counted by op dispatch, so a switch that also wrote anything else
     * (another grant, a calendar event, a device command) goes red here.
     */
    @Test
    fun aGrantSwitchedOffOnTheWeb_refusesTheNextQuestionByName_andWritesNothingElse() = GlobalScope.promise {
        JsRuntime.awaitReady()
        val day = farDay()
        val title = "Swim ${word()}"
        seedWorkout("mia-${word()}", title, startMs = day.atUtc(7))
        val now = day.plusDays(1).atUtc(12)
        // Control: granted, the question finds the row — so the refusal below is the switch's.
        assertTrue(title in titles(ask("workouts yesterday", now)))
        val before = realmSettingValues()

        try {
            val (off, offWrites) = writesDuring { Mia.setGranted("FITNESS", false).await() }
            assertEquals(true, off.ok, "setGranted: ${JSON.stringify(off)}")
            assertEquals(mapOf(WriteSetting.ID to 1), offWrites, "one write: the grant row, nothing else")
            assertEquals(
                false,
                off.grants.unsafeCast<Array<dynamic>>().first { it.source == "FITNESS" }.read,
                "the result is the set read back after the write",
            )
            val changed = realmSettingValues().filter { (id, v) -> before[id] != v }.keys
            assertEquals(setOf(MiaSource.FITNESS.preference.id), changed, "only the fitness grant row moved")

            val (answer, askWrites) = writesDuring { ask("workouts yesterday", now) }
            assertRefused(answer, "fitness switched off on the web")
            assertTrue("workouts" in (answer.explanation as String), "the refusal names the source: ${answer.explanation}")
            assertEquals(emptyMap(), askWrites, "a refused question writes nothing")
        } finally {
            val on = Mia.setGranted("FITNESS", true).await()
            assertEquals(true, on.ok, "setGranted(on): ${JSON.stringify(on)}")
            assertEquals(true, on.grants.unsafeCast<Array<dynamic>>().first { it.source == "FITNESS" }.read)
        }
        assertTrue(title in titles(ask("workouts yesterday", now)), "switched on again, the next question answers")
    }

    @Test
    fun theWriteSwitch_mirrorsNativesWriteTier_andRefusesWhatHasNone() = GlobalScope.promise {
        JsRuntime.awaitReady()
        try {
            val on = ok(Mia.setWritable("CALENDAR", true), "setWritable")
            assertEquals(true, on.grants.unsafeCast<Array<dynamic>>().first { it.source == "CALENDAR" }.write)
        } finally {
            val off = ok(Mia.setWritable("CALENDAR", false), "setWritable(off)")
            assertEquals(false, off.grants.unsafeCast<Array<dynamic>>().first { it.source == "CALENDAR" }.write)
        }
        val before = realmSettingValues()
        val (refused, writes) = writesDuring {
            listOf(
                Mia.setWritable("FITNESS", true).await(),
                Mia.setGranted("WEATHER", false).await(),
                Mia.setWritable("WEATHER", true).await(),
            )
        }
        assertEquals(
            listOf("error_mia_no_write_grant", "error_mia_unknown_source", "error_mia_unknown_source"),
            refused.map { it.error.kind as String },
        )
        assertEquals(emptyMap(), writes, "a refused switch writes nothing")
        assertEquals(before, realmSettingValues())
    }

    /** Every realm setting, by preference id, as its stored value — the before/after of a switch. */
    private suspend fun realmSettingValues(): Map<String, String> =
        (client.preferenceService.realmSettings.getAll() as TResult.Success).value!!.toList()
            .associate { it.preferenceId.get() to it.value.toString() }

    /**
     * The write ops a grant switch or a question could reach: settings, events, devices, routines.
     * `writesDuring` asserts the first three are mapped, so the count cannot pass by wrapping nothing.
     */
    private val writeOps = listOf(WriteSetting.ID, ClearSetting.ID, SaveEvent.ID, IssueDeviceCommand.ID, RunRoutineNow.ID)

    /**
     * Runs [body] with each of [writeOps] wrapped in a counting delegate (`HomeWebContractTest`'s
     * recipe over `JsRuntime.operationOverridesForTest`): the result and the dispatch counts.
     */
    private suspend fun <T> writesDuring(body: suspend () -> T): Pair<T, Map<String, Int>> {
        val counts = mutableMapOf<String, Int>()
        for (id in writeOps) {
            // ⚠ Home's writes map nothing in a tab (`HomePlugin`, read-only by absence), so there is
            // nothing to wrap — and nothing for them to write. Wrapping one would MAP it.
            val delegate = implementationFactory(id)
                ?: JsRuntimeBuilder.orderedPlugins.firstNotNullOfOrNull { it.operationFactory(JsRuntime)?.invoke(id) }
                ?: continue
            JsRuntime.operationOverridesForTest[id] = counting(id, delegate, counts)
        }
        assertTrue(
            listOf(WriteSetting.ID, ClearSetting.ID, SaveEvent.ID).all { it in JsRuntime.operationOverridesForTest },
            "the settings and event writes are mapped in a tab, so they are counted",
        )
        val result = try {
            body()
        } finally {
            writeOps.forEach { JsRuntime.operationOverridesForTest.remove(it) }
        }
        return result to counts.filterValues { it > 0 }
    }

    private fun counting(id: String, delegate: OperationImplementation, counts: MutableMap<String, Int>): OperationImplementation =
        when (delegate) {
            is SyncOperationImplementation -> object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?,
                ): T {
                    counts[id] = (counts[id] ?: 0) + 1
                    return delegate.execute(executionContext, operationContext, implementationFactory, caller, params)
                }
            }
            is AsyncOperationImplementation -> object : AsyncOperationImplementation {
                override suspend fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?,
                ): T {
                    counts[id] = (counts[id] ?: 0) + 1
                    return delegate.execute(executionContext, operationContext, implementationFactory, caller, params)
                }
            }
            else -> throw AssertionError("$id is implemented as ${delegate::class.simpleName}")
        }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private suspend fun seedWorkout(id: String, title: String, startMs: Long) = assertOk(
        client.healthService.recordWorkout(
            Workout.create(
                ctx, id, "Running", "Manual", UnixInstantDto(startMs), UnixInstantDto(startMs + 30 * 60_000L),
                durationSec = 1_800, title = title,
            ),
        ),
        "recordWorkout($id)",
    )

    private suspend fun seedPlace(id: String, label: String, city: String) = assertOk(
        client.placeService.recordPlace(Place.create(ctx, id, 0, 0, UnixInstantDto(0L), label = label, city = city)),
        "recordPlace($id)",
    )

    private fun draft(calendarId: String, title: String, start: String, end: String): dynamic {
        val d: dynamic = js("({})")
        d.eventId = ""
        d.calendarId = calendarId
        d.title = title
        d.startLocal = start
        d.endLocal = end
        d.timeZoneId = "UTC"
        d.allDay = false
        d.location = ""
        d.notes = ""
        d.recurrenceRule = ""
        return d
    }

    private fun LocalDate.plusDays(n: Int) = LocalDate.fromEpochDays(toEpochDays() + n)

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            kotlinx.coroutines.delay(25)
        }
        throw AssertionError("condition not met within 5 s")
    }
}
