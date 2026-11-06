package org.tenshu.inference.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The foreground pass on a phone that indexes only in front (`ios-on-device-inference.md` §14.19):
 * the manual bar's truth table, and the driver against a phone whose heat, power, battery,
 * foreground and memory change **mid-pass**.
 */
class ForegroundIndexingTest {

    // ─── the bar ───────────────────────────────────────────────────────────────────────────────

    private fun phone(
        foreground: Boolean = true,
        thermal: ThermalState = ThermalState.NOMINAL,
        lowPowerMode: Boolean = false,
        batteryLevel: Float? = 0.8f,
        charging: Boolean? = false,
    ) = DeviceIndexingState(foreground, thermal, lowPowerMode, batteryLevel, charging)

    @Test
    fun theManualBarIsTheForegroundTheHeatLowPowerModeAndALowBatteryInThatOrder() {
        assertNull(manualIndexingBlock(phone()))
        assertNull(manualIndexingBlock(phone(thermal = ThermalState.FAIR)), "fair is not too warm (§3.3)")
        assertEquals(IndexingBlock.TOO_WARM, manualIndexingBlock(phone(thermal = ThermalState.SERIOUS)))
        assertEquals(IndexingBlock.TOO_WARM, manualIndexingBlock(phone(thermal = ThermalState.CRITICAL)))
        assertEquals(IndexingBlock.LOW_POWER_MODE, manualIndexingBlock(phone(lowPowerMode = true)))
        assertEquals(IndexingBlock.BATTERY_LOW, manualIndexingBlock(phone(batteryLevel = 0.19f)))
        assertNull(manualIndexingBlock(phone(batteryLevel = 0.2f)), "20 % is not low")
        assertNull(manualIndexingBlock(phone(batteryLevel = 0.05f, charging = true)), "charging is never low")
        assertEquals(IndexingBlock.BATTERY_LOW, manualIndexingBlock(phone(batteryLevel = 0.05f, charging = null)), "an unknown charger is not a charger")
        assertNull(manualIndexingBlock(phone(batteryLevel = null, charging = null)), "a battery the phone does not report is not low")
        assertEquals(IndexingBlock.NOT_IN_FOREGROUND, manualIndexingBlock(phone(foreground = false, thermal = ThermalState.CRITICAL, lowPowerMode = true)))
        assertEquals(IndexingBlock.TOO_WARM, manualIndexingBlock(phone(thermal = ThermalState.SERIOUS, lowPowerMode = true, batteryLevel = 0.01f)))
    }

    @Test
    fun theScheduledBarIsNeverMetWithoutAProcessingWake() {
        val conditions = ForegroundIndexingConditions { phone(batteryLevel = 1f, charging = true) }
        assertFalse(conditions.allowIndexing())
        assertTrue(conditions.allowManualIndexing())
        assertFalse(ForegroundIndexingConditions { phone(foreground = false) }.allowManualIndexing())
    }

    // ─── the driver ────────────────────────────────────────────────────────────────────────────

    private var now: DeviceIndexingState = phone()
    private var clockMs = 1_000_000L
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val warnings = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val passes = MutableStateFlow<PassStanding>(PassStanding.Idle(null))
    private var serial = 0L
    private val events = mutableListOf<String>()
    private var startAnswer: () -> StartAnswer = { StartAnswer.Started }

    /** Whether a cancel ends the pass at once; `false` is a pass that has not reached its next photo yet. */
    private var cancelEnds = true

    /** What the op does: the manual bar, then a pass the runner numbers and publishes. */
    private fun start(): StartAnswer {
        events += "start"
        manualIndexingBlock(now)?.let { return StartAnswer.Refused(ForegroundIndexing.NOT_READY) }
        val answer: StartAnswer = startAnswer()
        if (answer == StartAnswer.Started && passes.value !is PassStanding.Running) {
            passes.value = PassStanding.Running(++serial, manual = true)
            outcomes[serial] = CompletableDeferred()
        }
        return answer
    }

    /** The fake runner's terminal outcomes, completed as its standing goes idle. */
    private val outcomes = mutableMapOf<Long, CompletableDeferred<PassOutcome>>()

    private fun end(ending: (Long) -> PassEnding) {
        val running = passes.value as PassStanding.Running
        val ended: PassEnding = ending(running.pass)
        passes.value = PassStanding.Idle(ended)
        outcomes[running.pass]?.complete(passOutcomeOf(ended))
    }

    private val window = object : BackgroundWindow {
        var open = 0
        override fun begin(name: String): Any {
            open++
            events += "window begin"
            return "w$open"
        }

        override fun end(handle: Any?) {
            events += "window end $handle"
        }
    }

    private fun TestScope.driver(
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        continuation: PassContinuation = PassContinuation.None,
        /** What else counts as the foreground for the bar: Photos' graph adds a held continued task. */
        alsoForeground: () -> Boolean = { false },
    ) =
        ForegroundIndexing(
            scope = scope,
            conditions = ForegroundIndexingConditions { now.copy(foreground = now.foreground || alsoForeground()) },
            changes = changes,
            memoryWarnings = warnings,
            runnerStanding = { passes },
            startPass = { start() },
            cancelPass = {
                events += "cancel"
                if (cancelEnds) (passes.value as? PassStanding.Running)?.let { end { pass -> PassEnding.Stopped(pass) } }
            },
            closeSessions = { events += "close" },
            window = window,
            clock = { clockMs },
            continuation = continuation,
        ).also { it.start() }

    private fun ForegroundIndexing.state(): ForegroundIndexingState = standing.value.state

    private fun deferred(pass: Long) = PassEnding.Finished(pass, IndexPassResult(deferred = true))

    @Test
    fun aPressStartsAPassAndItsEndEndsTheAsk() = runTest {
        val driver = driver()
        assertEquals(IndexNowAnswer.Started, driver.indexNow())
        assertEquals(ForegroundIndexingState.Asked(1), driver.state())

        end { PassEnding.Finished(it, IndexPassResult(embedded = 3, imagesVisited = 3)) }
        assertEquals(ForegroundIndexingState.NotAsked, driver.state())
    }

    @Test
    fun aPressOnAWarmPhoneWaitsAndStartsOnceWhenItCoolsAfterTheRest() = runTest {
        val driver = driver()
        now = phone(thermal = ThermalState.SERIOUS)
        assertEquals(IndexNowAnswer.Waiting(IndexingBlock.TOO_WARM), driver.indexNow())
        assertEquals(ForegroundIndexingState.Waiting(IndexingBlock.TOO_WARM, clockMs), driver.state())

        changes.tryEmit(Unit)
        runCurrent()
        assertEquals(1, events.count { it == "start" }, "a change while still warm started a pass")

        // Cool, but before the rest: nothing yet.
        now = phone(thermal = ThermalState.FAIR)
        changes.tryEmit(Unit)
        changes.tryEmit(Unit)
        runCurrent()
        assertEquals(1, events.count { it == "start" }, "a pass stopped by heat started again before its rest")

        // The rest passes with no further change: the re-check starts it, once.
        clockMs += ForegroundIndexing.REST_AFTER_HEAT_MS
        advanceTimeBy(ForegroundIndexing.REST_AFTER_HEAT_MS + 10)
        runCurrent()
        changes.tryEmit(Unit)
        runCurrent()
        assertEquals(2, events.count { it == "start" }, "a cooled phone started the pass $events")
        assertEquals(ForegroundIndexingState.Asked(1), driver.state())
    }

    @Test
    fun aPassStoppedMidLibraryByLowPowerModeWaitsAndResumesWhenItIsSwitchedOff() = runTest {
        val driver = driver()
        driver.indexNow()
        now = phone(lowPowerMode = true)
        end(::deferred)
        assertEquals(ForegroundIndexingState.Waiting(IndexingBlock.LOW_POWER_MODE, clockMs), driver.state())

        now = phone()
        changes.tryEmit(Unit)
        runCurrent()

        assertEquals(ForegroundIndexingState.Asked(2), driver.state(), "the pass was not resumed")
    }

    /**
     * Deferred with nothing blocking: the stores' gate, say — which looks, from here, exactly like a
     * block that cleared before the ending was read. It is tried once more, and then not waited out.
     */
    @Test
    fun aDeferralNothingHereCanNameIsTriedOnceMoreAndThenNotWaitedOut() = runTest {
        val driver = driver()
        driver.indexNow()
        end(::deferred)
        runCurrent()
        assertEquals(ForegroundIndexingState.Asked(2), driver.state())

        end(::deferred)
        runCurrent()
        assertEquals(ForegroundIndexingState.NotAsked, driver.state(), "a gate that keeps refusing was retried for ever")
        changes.tryEmit(Unit)
        runCurrent()
        assertEquals(2, events.count { it == "start" }, "$events")
    }

    /**
     * ⚠⚠ A pass deferred by a short block (heat touching `.serious`, a charger unplugged and
     * replugged) whose block has cleared by the time its ending is read: the ask is resumed at
     * once, never left `NotAsked` with nothing to resume it.
     */
    @Test
    fun aPassDeferredByABlockThatClearedBeforeItsEndingWasReadIsResumed() = runTest {
        val driver = driver()
        driver.indexNow()
        // The phone was warm at the pass's last photo, and has cooled since: no block now.
        now = phone()
        end(::deferred)
        runCurrent()

        assertEquals(2, events.count { it == "start" }, "the ask was not resumed: $events")
        assertEquals(ForegroundIndexingState.Asked(2), driver.state())
        end { PassEnding.Finished(it, IndexPassResult(embedded = 1, imagesVisited = 1)) }
        assertEquals(ForegroundIndexingState.NotAsked, driver.state())
    }

    /**
     * ⚠⚠ The pass a press found running ends DURING the op, which then launches the next: the ask
     * is the next pass's, and the earlier pass's ending, delivered after the ask was set, does not
     * end it.
     */
    @Test
    fun aPassThatEndsWhileAPressIsInTheOpDoesNotEndTheAskThePressMade() = runTest {
        val driver = driver()
        driver.indexNow()
        assertEquals(ForegroundIndexingState.Asked(1), driver.state())

        // The op reads the stores first; pass 1 finishes meanwhile, and the op starts pass 2.
        startAnswer = {
            end { PassEnding.Finished(it, IndexPassResult(embedded = 1, imagesVisited = 1)) }
            StartAnswer.Started
        }
        assertEquals(IndexNowAnswer.Started, driver.indexNow())
        assertEquals(PassStanding.Running(2, manual = true), passes.value, "precondition: the op launched pass 2")
        assertEquals(ForegroundIndexingState.Asked(2), driver.state(), "the press's ask was taken for the pass that ended during it")

        // Pass 1's ending, read by the collector only now.
        passes.value = PassStanding.Idle(PassEnding.Finished(1, IndexPassResult(embedded = 1, imagesVisited = 1)))
        runCurrent()
        assertEquals(ForegroundIndexingState.Asked(2), driver.state(), "the earlier pass's ending ended the ask of the pass that replaced it")
    }

    /** ⚠⚠ §3.3: cancelled, never suspended mid-pass; the towers go; the window ends once they have. */
    @Test
    fun leavingTheForegroundStopsThePassLetsGoOfTheTowersAndEndsTheWindowOnce() = runTest {
        val driver = driver()
        driver.indexNow()
        events.clear()

        now = phone(foreground = false)
        driver.leftForeground()
        runCurrent()

        assertEquals(listOf("window begin", "cancel", "close", "window end w1"), events)
        assertEquals(ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LEFT_PHOTOS), driver.state())
        // Back in front, the phone moves: nothing resumes but a press.
        now = phone()
        changes.tryEmit(Unit)
        runCurrent()
        assertFalse("start" in events, "a pass resumed by itself after Photos left")
    }

    @Test
    fun aWaitingAskEndsWhenPhotosLeavesTheForeground() = runTest {
        val driver = driver()
        now = phone(thermal = ThermalState.SERIOUS)
        driver.indexNow()
        now = phone(foreground = false)
        driver.leftForeground()
        clockMs += ForegroundIndexing.REST_AFTER_HEAT_MS
        advanceTimeBy(ForegroundIndexing.REST_AFTER_HEAT_MS + 10)
        now = phone()
        changes.tryEmit(Unit)
        runCurrent()

        assertEquals(1, events.count { it == "start" }, "a waiting ask outlived the foreground")
        assertEquals(ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LEFT_PHOTOS), driver.state())
    }

    @Test
    fun leavingWithNoPassStillLetsGoOfTheTowers() = runTest {
        val driver = driver()
        driver.leftForeground()
        runCurrent()

        assertEquals(listOf("window begin", "cancel", "close", "window end w1"), events)
        assertEquals(ForegroundIndexingState.NotAsked, driver.state())
    }

    /** ⚠ A teardown before the stop runs: the window is still ended, once. */
    @Test
    fun aWindowIsEndedEvenWhenTheDriversScopeIsAlreadyGone() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val driver = driver(scope)
        scope.cancel()
        driver.leftForeground()
        runCurrent()

        assertEquals(1, events.count { it.startsWith("window end") }, "$events")
    }

    /** ⚠⚠ The press's answer carries which foreground it was made in: it says nothing after Photos left. */
    @Test
    fun aPressThatReturnsAfterPhotosLeftStopsWhatItStartedAndAsksForNothing() = runTest {
        lateinit var driver: ForegroundIndexing
        startAnswer = {
            driver.leftForeground()
            StartAnswer.Started
        }
        driver = driver()
        // ⚠ The pass has not reached its next photo when the press returns: nothing has ended yet.
        cancelEnds = false
        driver.indexNow()
        runCurrent()

        assertEquals(ForegroundIndexingState.NotAsked, driver.state(), "a press made before Photos left asked for a pass after it")
        assertEquals(2, events.count { it == "cancel" }, "the pass a late press started was left running: $events")
    }

    /** ⚠⚠ Identity: an ending of a pass an earlier ask started does not end this one. */
    @Test
    fun anEarlierPassesEndingDoesNotEndTheAskThatFollowedIt() = runTest {
        val driver = driver()
        driver.indexNow()
        end { PassEnding.Finished(it, IndexPassResult()) }
        driver.indexNow()
        assertEquals(ForegroundIndexingState.Asked(2), driver.state())

        // The first pass's ending, delivered late.
        passes.value = PassStanding.Idle(PassEnding.Stopped(1))
        runCurrent()
        assertEquals(ForegroundIndexingState.Asked(2), driver.state(), "a stale ending ended the ask")
    }

    @Test
    fun aSecondPressJoinsTheRunningPass() = runTest {
        val driver = driver()
        driver.indexNow()
        assertEquals(IndexNowAnswer.Started, driver.indexNow())

        assertEquals(ForegroundIndexingState.Asked(1), driver.state())
        end { PassEnding.Finished(it, IndexPassResult()) }
        assertEquals(ForegroundIndexingState.NotAsked, driver.state())
    }

    @Test
    fun aMemoryWarningStopsThePassAndClosesTheTowersAndNothingResumesIt() = runTest {
        val driver = driver()
        driver.indexNow()
        events.clear()

        warnings.tryEmit(Unit)
        runCurrent()

        assertEquals(listOf("cancel", "close"), events)
        assertEquals(ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LOW_MEMORY), driver.state())
        changes.tryEmit(Unit)
        runCurrent()
        assertFalse("start" in events)
    }

    @Test
    fun aRefusalThatIsNotThePhonesIsReturnedAndAsksForNothing() = runTest {
        val driver = driver()
        startAnswer = { StartAnswer.Refused("NO_CONSENT") }
        assertEquals(IndexNowAnswer.Refused("NO_CONSENT"), driver.indexNow())
        assertEquals(ForegroundIndexingState.NotAsked, driver.state())
        startAnswer = { StartAnswer.Failed }
        assertEquals(IndexNowAnswer.Failed, driver.indexNow())
        assertEquals(ForegroundIndexingState.NotAsked, driver.state())
    }

    @Test
    fun aModelThatLeavesReadyEndsTheAsk() = runTest {
        val driver = driver()
        driver.indexNow()
        // `AiReadiness.leaveTo` → `cancelRunningPass`: a stop nobody here asked for.
        end { PassEnding.Stopped(it) }
        assertEquals(ForegroundIndexingState.NotAsked, driver.state())
    }

    /** ⚠ Another block starts its own rest: a pass warm, then on Low Power Mode, then warm again rests anew. */
    @Test
    fun aChangedBlockRestartsTheRestClock() = runTest {
        val driver = driver()
        now = phone(thermal = ThermalState.SERIOUS)
        driver.indexNow()
        clockMs += ForegroundIndexing.REST_AFTER_HEAT_MS
        now = phone(lowPowerMode = true)
        changes.tryEmit(Unit)
        runCurrent()
        now = phone(thermal = ThermalState.SERIOUS)
        changes.tryEmit(Unit)
        runCurrent()

        assertEquals(ForegroundIndexingState.Waiting(IndexingBlock.TOO_WARM, clockMs), driver.state(), "the rest was measured from an earlier block")
    }

    @Test
    fun aResumeThatThePhoneRefusesAgainKeepsWaiting() = runTest {
        val driver = driver()
        now = phone(lowPowerMode = true)
        driver.indexNow()
        now = phone()
        // Between the read and the start the battery went: the op refuses it.
        startAnswer = {
            now = phone(batteryLevel = 0.1f)
            StartAnswer.Refused(ForegroundIndexing.NOT_READY)
        }
        changes.tryEmit(Unit)
        runCurrent()

        assertEquals(ForegroundIndexingState.Waiting(IndexingBlock.BATTERY_LOW, clockMs), driver.state())
    }

    /**
     * A start that throws — an `Error`, which no op answers as a refusal — ends the ask instead of
     * leaving it `Resuming` for good, and the driver still follows the phone after it.
     */
    @Test
    fun aResumeWhoseStartThrowsEndsTheAskAndTheDriverStillFollowsThePhone() = runTest {
        val driver = driver()
        now = phone(lowPowerMode = true)
        assertEquals(IndexNowAnswer.Waiting(IndexingBlock.LOW_POWER_MODE), driver.indexNow())
        now = phone()
        startAnswer = { throw NotImplementedError("a start that throws") }
        changes.tryEmit(Unit)
        runCurrent()
        assertEquals(ForegroundIndexingState.NotAsked, driver.state(), "a start that threw left the ask behind")

        startAnswer = { StartAnswer.Started }
        now = phone(lowPowerMode = true)
        driver.indexNow()
        now = phone()
        changes.tryEmit(Unit)
        runCurrent()
        assertEquals(ForegroundIndexingState.Asked(1), driver.state(), "the driver stopped following the phone")
    }

    @Test
    fun aPressWhoseStartThrowsAnswersFailed() = runTest {
        val driver = driver()
        startAnswer = { throw NotImplementedError("a start that throws") }
        assertEquals(IndexNowAnswer.Failed, driver.indexNow())
        assertEquals(ForegroundIndexingState.NotAsked, driver.state())
    }

    // ─── a pass a continued task owns (§15) ──────────────────────────────────────────────────

    /** A continuation the case holds (as a launched task) or lets go of by hand. */
    private class Holding(var holds: Boolean = false) : PassContinuation {
        val offers = mutableListOf<Long>()
        override fun offer(pass: Long) {
            offers += pass
        }

        override fun holdsPass(): Boolean = holds
        override fun standing(): ContinuationStanding = if (holds) ContinuationStanding.LIVE else ContinuationStanding.NONE
        override fun holdUntilResolved(end: () -> Unit): Boolean = false
    }

    /**
     * ⚠⚠ §3.3 with a continued task: with a live one the background hook does **not** cancel, and
     * without one it still does.
     */
    @Test
    fun withALiveContinuedTaskLeavingDoesNotCancelAndWithoutOneItStillDoes() = runTest {
        val continued = Holding()
        val driver = driver(continuation = continued)
        assertEquals(IndexNowAnswer.Started, driver.indexNow())
        assertEquals(listOf(1L), continued.offers, "the press did not offer its pass")

        continued.holds = true
        now = phone(foreground = false)
        driver.leftForeground()
        runCurrent()
        assertEquals(listOf("start"), events, "a leave cancelled a pass its continued task owns")
        assertEquals(ForegroundIndexingState.Asked(1), driver.state())

        // The task lets go while Photos is still not in front: what the leave would have done.
        continued.holds = false
        driver.continuationReleased()
        runCurrent()
        assertEquals(listOf("start", "window begin", "cancel", "close", "window end w1"), events)
        assertEquals(ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LEFT_PHOTOS), driver.state())
    }

    @Test
    fun withNoContinuedTaskTheLeaveCancelsAsBefore() = runTest {
        val driver = driver(continuation = Holding(holds = false))
        driver.indexNow()
        now = phone(foreground = false)
        driver.leftForeground()
        runCurrent()
        assertEquals(listOf("start", "window begin", "cancel", "close", "window end w1"), events)
    }

    /** A task that lets go while Photos is in front leaves an ordinary foreground pass running. */
    @Test
    fun aContinuedTaskThatLetsGoInFrontStopsNothing() = runTest {
        val continued = Holding(holds = true)
        val driver = driver(continuation = continued)
        driver.indexNow()
        continued.holds = false
        driver.continuationReleased()
        runCurrent()
        assertEquals(listOf("start"), events)
        assertEquals(ForegroundIndexingState.Asked(1), driver.state())
    }

    private fun TestScope.realContinuation(
        answer: Boolean?,
        bridgeScope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        driver: () -> ForegroundIndexing,
    ): ContinuedIndexing {
        lateinit var bridge: ContinuedIndexing
        bridge = ContinuedIndexing(
            settled = { driver().continuationSettled() },
            scope = bridgeScope,
            scheduler = {
                ContinuedTaskScheduler { identifier ->
                    events += "submit"
                    answer?.let { bridge.answered(identifier, it) }
                }
            },
            outcomeOf = { outcomes[it] },
            progress = { MutableStateFlow(null) },
            released = { driver().continuationReleased() },
            identifierPrefix = "app.test.indexnow.",
            newSuffix = { "only" },
        )
        return bridge
    }

    /**
     * ⚠⚠ A rejected submission (`.fail`) still started the pass at once — the press starts it
     * before it asks — and the pass is then the ordinary foreground pass, which a leave cancels.
     */
    @Test
    fun aRejectedSubmissionStartsTheForegroundPassAtOnceAndALeaveStillCancelsIt() = runTest {
        lateinit var driver: ForegroundIndexing
        val bridge = realContinuation(answer = false) { driver }
        driver = driver(continuation = bridge)

        assertEquals(IndexNowAnswer.Started, driver.indexNow())
        assertEquals(listOf("start", "submit"), events, "the pass did not start before the request was submitted")
        assertEquals(PassStanding.Running(1, manual = true), passes.value, "a refused request left no pass running")
        assertFalse(bridge.holdsPass())

        now = phone(foreground = false)
        driver.leftForeground()
        runCurrent()
        assertEquals(listOf("start", "submit", "window begin", "cancel", "close", "window end w1"), events)
    }

    /**
     * ⚠⚠ The task's pass ends in the background: the leave's stop begins — its background window
     * open — **before** the task completes, so iOS does not suspend Photos with a pass still running.
     */
    @Test
    fun aContinuedPassThatEndsInTheBackgroundIsStoppedBeforeItsTaskCompletes() = runTest {
        lateinit var driver: ForegroundIndexing
        val bridge = realContinuation(answer = null) { driver }
        driver = driver(continuation = bridge)
        driver.indexNow()
        val task = object : ContinuedTask {
            override fun progress(done: Long, total: Long) = Unit
            override fun complete(success: Boolean) {
                events += "complete $success"
            }
        }
        bridge.launched("app.test.indexnow.only", task)
        now = phone(foreground = false)
        driver.leftForeground()
        runCurrent()
        assertEquals(listOf("start", "submit"), events, "the leave cancelled the task's pass")

        end { PassEnding.Finished(it, IndexPassResult(embedded = 2, imagesVisited = 2)) }
        runCurrent()
        // ⚠ The stop itself is launched, so it may finish after the task completes: what must
        // come first is its background window, which keeps Photos running until it has.
        assertTrue(events.indexOf("window begin") in 0 until events.indexOf("complete true"), "the task completed before the stop's window was open: $events")
        assertTrue(events.containsAll(listOf("cancel", "close", "window end w1")), "the leave's stop did not run: $events")
    }

    // ─── the continued task: a submitted claim, an expiry, an ending in the background ─────────

    private class Recorded(val events: MutableList<String>) : ContinuedTask {
        val completions = mutableListOf<Boolean>()
        override fun progress(done: Long, total: Long) = Unit
        override fun complete(success: Boolean) {
            completions += success
            events += "complete $success"
        }
    }

    /**
     * ⚠⚠ A leave while the task is only **submitted** opens a background window and holds it —
     * nothing cancelled yet — until the launch, which ends it.
     */
    @Test
    fun aLeaveWhileATaskIsOnlySubmittedHoldsAWindowUntilItIsLaunched() = runTest {
        lateinit var driver: ForegroundIndexing
        val bridge = realContinuation(answer = null) { driver }
        driver = driver(continuation = bridge)
        driver.indexNow()
        now = phone(foreground = false)
        driver.leftForeground()
        runCurrent()
        assertEquals(listOf("start", "submit", "window begin"), events, "a leave while submitted held no window, or stopped the pass")
        assertEquals(ForegroundIndexingState.Asked(1), driver.state())

        bridge.launched("app.test.indexnow.only", Recorded(events))
        assertEquals(listOf("start", "submit", "window begin", "window end w1"), events, "the launch did not end the held window")
    }

    /** …and a refusal that comes after the leave does the leave in full, then ends the held window. */
    @Test
    fun aLeaveWhileATaskIsOnlySubmittedIsDoneInFullWhenTheRequestIsRefused() = runTest {
        lateinit var driver: ForegroundIndexing
        val bridge = realContinuation(answer = null) { driver }
        driver = driver(continuation = bridge)
        driver.indexNow()
        now = phone(foreground = false)
        driver.leftForeground()
        bridge.answered("app.test.indexnow.only", accepted = false)
        runCurrent()
        assertEquals(listOf("start", "submit", "window begin", "window begin", "cancel", "close", "window end w2", "window end w1"), events)
        assertEquals(ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LEFT_PHOTOS), driver.state())
    }

    /**
     * ⚠⚠ An expiry while Photos is in front stops nothing — the pass goes on as an ordinary
     * foreground pass — and the task is completed once, with `false`, at once.
     */
    @Test
    fun anExpiryInFrontLeavesThePassRunningAndCompletesTheTaskOnceWithFailure() = runTest {
        lateinit var driver: ForegroundIndexing
        val bridge = realContinuation(answer = null) { driver }
        driver = driver(continuation = bridge)
        driver.indexNow()
        val task = Recorded(events)
        bridge.launched("app.test.indexnow.only", task)

        bridge.expired("app.test.indexnow.only")
        runCurrent()
        assertEquals(listOf("start", "submit", "complete false"), events, "an expiry in front stopped the user's pass")
        assertEquals(PassStanding.Running(1, manual = true), passes.value)
        assertEquals(ForegroundIndexingState.Asked(1), driver.state())

        end { PassEnding.Finished(it, IndexPassResult(embedded = 1, imagesVisited = 1)) }
        runCurrent()
        assertEquals(listOf(false), task.completions, "the expired task was completed again")
        assertEquals(ForegroundIndexingState.NotAsked, driver.state())
    }

    /**
     * ⚠⚠ A continued pass that ends in the background is what its ending says, whichever of the
     * standing's collector and the task's release runs first — done is done, and a heat stop waits
     * for the heat — never *stopped when you left*.
     */
    @Test
    fun aContinuedPassEndingInTheBackgroundKeepsItsEndingInEitherOrder() = runTest {
        for (collectorFirst in listOf(true, false)) {
            for (heat in listOf(false, true)) {
                events.clear()
                passes.value = PassStanding.Idle(null)
                serial = 0
                now = phone()
                lateinit var driver: ForegroundIndexing
                val bridge = realContinuation(answer = null) { driver }
                // ⚠ A queued driver scope: its collector reads the ending only at `runCurrent`, after
                // the bridge (unconfined) has already released the task.
                val scope = if (collectorFirst) {
                    CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
                } else {
                    CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
                }
                driver = driver(scope = scope, continuation = bridge, alsoForeground = { bridge.holdsPass() })
                runCurrent()
                driver.indexNow()
                bridge.launched("app.test.indexnow.only", Recorded(events))
                now = phone(foreground = false, thermal = if (heat) ThermalState.SERIOUS else ThermalState.NOMINAL)
                driver.leftForeground()
                runCurrent()

                end { if (heat) deferred(it) else PassEnding.Finished(it, IndexPassResult(embedded = 1, imagesVisited = 1)) }
                runCurrent()
                val expected: ForegroundIndexingState =
                    if (heat) ForegroundIndexingState.Waiting(IndexingBlock.TOO_WARM, clockMs) else ForegroundIndexingState.NotAsked
                assertEquals(expected, driver.state(), "collectorFirst=$collectorFirst heat=$heat: $events")
                assertTrue("complete ${!heat}" in events, "collectorFirst=$collectorFirst heat=$heat: the task was not completed: $events")
                scope.cancel()
            }
        }
    }

    // ─── the continued task: a deferral, and a wait across the release ─────────────────────────

    private fun TestScope.queued(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    /**
     * ⚠⚠ A continued pass deferred in the background with no block the phone still names is **not**
     * resumed while its task holds the pass — the bar would read the task as the foreground and a
     * second pass could start just as the task lets go. Both scopes queued, so the collector, the
     * settle and the release interleave as on a real dispatcher.
     */
    @Test
    fun aDeferralWithNoBlockEndingUnderAContinuedTaskStartsNoSecondPass() = runTest {
        lateinit var driver: ForegroundIndexing
        val bridge = realContinuation(answer = null, bridgeScope = queued()) { driver }
        driver = driver(scope = queued(), continuation = bridge, alsoForeground = { bridge.holdsPass() })
        runCurrent()
        driver.indexNow()
        bridge.launched("app.test.indexnow.only", Recorded(events))
        runCurrent()
        now = phone(foreground = false)
        driver.leftForeground()
        runCurrent()

        end(::deferred)
        runCurrent()
        assertEquals(1, events.count { it == "start" }, "a second pass was started under the task: $events")
        assertEquals(ForegroundIndexingState.NotAsked, driver.state())
        assertTrue("complete false" in events, "$events")
    }

    /**
     * ⚠⚠ A wait kept across the release is not rewritten in the background — the re-check that
     * fires there leaves its block and its clock — and the return to the foreground
     * ([ForegroundIndexing.enteredForeground]) reads it again and resumes.
     */
    @Test
    fun aWaitKeptAcrossTheReleaseSurvivesTheBackgroundAndResumesInFront() = runTest {
        val since: Long = waitingAfterAContinuedHeatStop()
        // The rest passes in the background: the re-check fires there and must change nothing.
        clockMs += ForegroundIndexing.REST_AFTER_HEAT_MS
        now = phone(foreground = false)
        advanceTimeBy(ForegroundIndexing.REST_AFTER_HEAT_MS + 10)
        changes.tryEmit(Unit)
        runCurrent()
        assertEquals(ForegroundIndexingState.Waiting(IndexingBlock.TOO_WARM, since), driverUnderTest.state(), "the background rewrote the wait")
        assertEquals(1, events.count { it == "start" })

        now = phone()
        driverUnderTest.enteredForeground()
        runCurrent()
        assertEquals(2, events.count { it == "start" }, "the return to the foreground did not resume the wait: $events")
        assertEquals(ForegroundIndexingState.Asked(2), driverUnderTest.state())
    }

    /** …and its re-check timer is kept by the release: it alone resumes the wait once Photos is in front. */
    @Test
    fun aWaitKeptAcrossTheReleaseKeepsItsReCheck() = runTest {
        waitingAfterAContinuedHeatStop()
        now = phone()
        clockMs += ForegroundIndexing.REST_AFTER_HEAT_MS
        advanceTimeBy(ForegroundIndexing.REST_AFTER_HEAT_MS + 10)
        runCurrent()
        assertEquals(2, events.count { it == "start" }, "the release cancelled the wait's re-check: $events")
    }

    private lateinit var driverUnderTest: ForegroundIndexing

    /** A continued pass stopped by heat in the background, released: the ask waits. Returns its clock. */
    private suspend fun TestScope.waitingAfterAContinuedHeatStop(): Long {
        lateinit var driver: ForegroundIndexing
        val bridge = realContinuation(answer = null) { driver }
        driver = driver(continuation = bridge, alsoForeground = { bridge.holdsPass() })
        driverUnderTest = driver
        driver.indexNow()
        bridge.launched("app.test.indexnow.only", Recorded(events))
        now = phone(foreground = false, thermal = ThermalState.SERIOUS)
        driver.leftForeground()
        end(::deferred)
        runCurrent()
        val waiting = assertIs<ForegroundIndexingState.Waiting>(driver.state())
        assertEquals(IndexingBlock.TOO_WARM, waiting.block)
        assertFalse(bridge.holdsPass())
        return waiting.sinceMs
    }

    /**
     * An expiry that wins against the task's own `finally`, after the pass ended, still reads the
     * ending first — a heat stop waits, never *stopped when you left*.
     */
    @Test
    fun anExpiryAfterThePassEndedKeepsItsEnding() = runTest {
        lateinit var driver: ForegroundIndexing
        val bridge = realContinuation(answer = null, bridgeScope = queued()) { driver }
        driver = driver(scope = queued(), continuation = bridge, alsoForeground = { bridge.holdsPass() })
        runCurrent()
        driver.indexNow()
        bridge.launched("app.test.indexnow.only", Recorded(events))
        runCurrent()
        now = phone(foreground = false, thermal = ThermalState.SERIOUS)
        driver.leftForeground()
        runCurrent()

        // The pass ends; neither its collector nor the task's `finally` has run when the expiry lands.
        end(::deferred)
        bridge.expired("app.test.indexnow.only")
        runCurrent()
        assertIs<ForegroundIndexingState.Waiting>(driver.state(), "the expiry overwrote the ending: $events")
        assertEquals(1, events.count { it.startsWith("complete") }, "$events")
    }

    /** A submitted claim that resolves between the standing's read and the hold: the window ends, and the leave is done. */
    @Test
    fun aClaimThatResolvesAsTheLeaveHoldsItEndsTheWindowAndLeaves() = runTest {
        val continuation = object : PassContinuation {
            var asked = 0
            override fun offer(pass: Long) = Unit
            override fun holdsPass(): Boolean = false
            override fun standing(): ContinuationStanding = if (asked++ == 0) ContinuationStanding.SUBMITTED else ContinuationStanding.NONE
            override fun holdUntilResolved(end: () -> Unit): Boolean = false
        }
        val driver = driver(continuation = continuation)
        driver.indexNow()
        now = phone(foreground = false)
        driver.leftForeground()
        runCurrent()
        assertEquals(listOf("start", "window begin", "window end w1", "window begin", "cancel", "close", "window end w2"), events)
    }

    /** Back in front while submitted, then refused there: the held window ends and nothing is stopped. */
    @Test
    fun aRefusalAfterReturningToTheForegroundEndsTheHeldWindowAndStopsNothing() = runTest {
        lateinit var driver: ForegroundIndexing
        val bridge = realContinuation(answer = null) { driver }
        driver = driver(continuation = bridge)
        driver.indexNow()
        now = phone(foreground = false)
        driver.leftForeground()
        now = phone()
        driver.enteredForeground()
        bridge.answered("app.test.indexnow.only", accepted = false)
        runCurrent()
        assertEquals(listOf("start", "submit", "window begin", "window end w1"), events)
        assertEquals(ForegroundIndexingState.Asked(1), driver.state())
        assertEquals(PassStanding.Running(1, manual = true), passes.value)
    }
}
