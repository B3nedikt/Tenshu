package org.tenshu.inference.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * Why a pass the user asked for may not work **now** on a phone that indexes only in the
 * foreground (`ios-on-device-inference.md` §14.19). In the order they are said: the first that
 * holds is the reason.
 */
enum class IndexingBlock {
    /** Photos is not the app in front. ⚠ Never a press's reason: a press is made in front. */
    NOT_IN_FOREGROUND,

    /** `ProcessInfo.thermalState` is `.serious` or `.critical` (sweep D5, §3.3). */
    TOO_WARM,

    /** Low Power Mode is on: the OS's own "save power" (§1.3). */
    LOW_POWER_MODE,

    /** Under [LOW_BATTERY_LEVEL] and not charging (§3.3). */
    BATTERY_LOW,
}

/** `ProcessInfo.thermalState`, in its order. */
enum class ThermalState { NOMINAL, FAIR, SERIOUS, CRITICAL }

/**
 * What the phone says now, each half as the platform reads it (§14.19's table).
 *
 * @param batteryLevel 0.0 … 1.0, or `null` where the phone reports none (a simulator, battery
 *   monitoring off). ⚠ Not known is not low: a press is refused only on a signal the phone gave.
 * @param charging charging or full; `null` when the state is unknown.
 */
data class DeviceIndexingState(
    val foreground: Boolean,
    val thermal: ThermalState,
    val lowPowerMode: Boolean,
    val batteryLevel: Float?,
    val charging: Boolean?,
)

/** Below this, on battery, a pass the user asked for does not run (§3.3: 20 %). */
const val LOW_BATTERY_LEVEL: Float = 0.2f

/**
 * The manual bar on a phone that indexes only in the foreground, with its reason; `null` is *may
 * run*. ⚠ What *Index now* keeps (§34: battery-not-low), plus the foreground, the heat and Low
 * Power Mode (§3.3). ⚠ Unknown battery is allowed, as on Android (`AndroidIndexingConditions`):
 * the user asked, and a refusal on a signal the phone cannot produce would never clear.
 */
fun manualIndexingBlock(state: DeviceIndexingState): IndexingBlock? = when {
    !state.foreground -> IndexingBlock.NOT_IN_FOREGROUND
    state.thermal >= ThermalState.SERIOUS -> IndexingBlock.TOO_WARM
    state.lowPowerMode -> IndexingBlock.LOW_POWER_MODE
    state.charging != true && (state.batteryLevel ?: 1f) < LOW_BATTERY_LEVEL -> IndexingBlock.BATTERY_LOW
    else -> null
}

/**
 * [IndexingConditions] for a phone that indexes **only in the foreground** (iOS until a
 * processing wake exists, §3.3): read per image, as every platform's are.
 *
 * ⚠⚠ [allowIndexing] — the scheduled bar — is `false`, always: a scheduled pass runs only inside a
 * processing wake (§1.3), and none is built (no `BGProcessingTask`). Nothing on iOS
 * dispatches one either: the shared registry item is not registered there.
 */
class ForegroundIndexingConditions(private val read: () -> DeviceIndexingState) : IndexingConditions {

    override fun allowIndexing(): Boolean = false

    override fun allowManualIndexing(): Boolean = block() == null

    /** Why not, now. ⚠ A snapshot: the next read may differ. */
    fun block(): IndexingBlock? = manualIndexingBlock(read())
}

/**
 * Where the foreground pass a person asked for stands (§14.19). ⚠ The runner says whether a pass
 * runs and how far it got ([MediaIndexRunner.standing]); this says what was **asked**, and why
 * what was asked is not happening.
 */
sealed interface ForegroundIndexingState {
    /** Nothing is asked: never in this process, or what was asked ran to its end. */
    data object NotAsked : ForegroundIndexingState

    /** Asked, and starting or running: a pass numbered [fromPass] or later is this ask's. */
    data class Asked(val fromPass: Long) : ForegroundIndexingState

    /** The ask is being started again by this driver ([ForegroundIndexing]'s resume). */
    data object Resuming : ForegroundIndexingState

    /**
     * Asked, and stopped by the phone: [block]. Started again by itself while Photos stays in
     * front, once the block has cleared (and, for heat, [ForegroundIndexing]'s rest has passed).
     * @param sinceMs when it stopped, on the driver's clock.
     */
    data class Waiting(val block: IndexingBlock, val sinceMs: Long) : ForegroundIndexingState

    /** Asked, and stopped by [why]. The next press continues where it stopped (the store is the cursor). */
    data class Stopped(val why: Why) : ForegroundIndexingState {
        enum class Why {
            /** Photos left the foreground (§3.3: cancelled, never suspended mid-pass). */
            LEFT_PHOTOS,

            /** iOS warned that memory is low: the pass stopped and the towers were let go. */
            LOW_MEMORY,
        }
    }
}

/** What *Index now* answered, typed (M8). */
sealed interface IndexNowAnswer {
    /** A pass runs: started, or joined. */
    data object Started : IndexNowAnswer

    /** Refused by the phone now; started by itself once [block] clears, while Photos stays in front. */
    data class Waiting(val block: IndexingBlock) : IndexNowAnswer

    /** The op's own refusal, by its reason ([org.tenshu.inference.IndexPassRefused.Reasons]). */
    data class Refused(val reason: String) : IndexNowAnswer

    /** It failed for a reason nobody named. */
    data object Failed : IndexNowAnswer
}

/** What the start operation answered, before this driver reads it. */
sealed interface StartAnswer {
    data object Started : StartAnswer
    data class Refused(val reason: String) : StartAnswer
    data object Failed : StartAnswer
}

/**
 * Opens a window in which a process that has left the foreground may finish what it is doing
 * (`beginBackgroundTask` on iOS), and ends it. ⚠ [begin] answers a handle that [end] takes once.
 */
interface BackgroundWindow {
    fun begin(name: String): Any?
    fun end(handle: Any?)

    companion object {
        val None: BackgroundWindow = object : BackgroundWindow {
            override fun begin(name: String): Any? = null
            override fun end(handle: Any?) = Unit
        }
    }
}

/**
 * The **foreground** index pass (§14.19): what *Index now* asks for on a phone that indexes only
 * while the app is in front, and what stops and resumes it. One per owning graph.
 *
 * - *Index now* goes through the start operation ([startPass]), so every refusal the op checks —
 *   paused, no model, no consent, the manual bar — is checked once, there.
 * - ⚠⚠ **Leaving the foreground cancels, never suspends** (§3.3): [leftForeground] stops the pass
 *   and lets go of both towers inside a [BackgroundWindow], and the ask ends there: the next press
 *   continues from the store. A pass suspended mid-read of the shared replica is the 0xdead10cc case.
 * - ⚠ **The phone's blocks wait, and resume** (heat, Low Power Mode, a low battery): a pass that
 *   stopped for one is started again by itself when [changes] says the phone moved and the block
 *   has cleared, while Photos stays in front. After heat, not before [restAfterHeatMs] (§3.3; a
 *   placeholder until a duty-cycle measurement gives the rest a number, §4.5).
 * - A memory warning stops the pass and closes both towers ([memoryWarning]); nothing resumes it
 *   but a press.
 *
 * ⚠⚠ Every check-and-claim is one `update {}` with no side effect in it, and every ending is read
 * with the pass it is about ([ForegroundIndexingState.Asked.fromPass]): an ending published by a
 * pass that an earlier ask started is never taken for this one's.
 *
 * ⚠ [start] starts the work; nothing is started by constructing it.
 */
class ForegroundIndexing(
    private val scope: CoroutineScope,
    private val conditions: ForegroundIndexingConditions,
    /** The phone's conditions moved: heat, power, battery. ⚠ Never completes. */
    private val changes: Flow<Unit>,
    /** iOS warned that memory is low. ⚠ Never completes. */
    private val memoryWarnings: Flow<Unit>,
    /**
     * The runner's [MediaIndexRunner.standing]. ⚠ A provider, asked on the driver's scope and by
     * a press, never by [leftForeground]: the runner's construction opens the stores, which is
     * not work for the main thread at a scene change.
     */
    private val runnerStanding: () -> StateFlow<PassStanding>,
    /**
     * The start operation. Answers its failures as [StartAnswer.Failed]; ⚠ one it throws instead
     * is caught by [startOrFailed] and answered the same way.
     */
    private val startPass: suspend () -> StartAnswer,
    private val cancelPass: suspend () -> Unit,
    private val closeSessions: suspend () -> Unit,
    private val window: BackgroundWindow = BackgroundWindow.None,
    private val clock: () -> Long,
    private val restAfterHeatMs: Long = REST_AFTER_HEAT_MS,
    /**
     * The task that lets a pass a press started go on once Photos leaves the foreground
     * ([ContinuedIndexing], iOS 26+). [PassContinuation.None] everywhere else, where nothing here
     * changes. ⚠⚠ While it [holds][PassContinuation.holdsPass] the pass, [leftForeground] does not
     * cancel; once it lets go, [continuationReleased] does what the leave would have.
     */
    private val continuation: PassContinuation = PassContinuation.None,
) {
    private val passes: StateFlow<PassStanding> by lazy { runnerStanding() }

    private val held = MutableStateFlow(ForegroundIndexingStanding(ForegroundIndexingState.NotAsked, leftForegrounds = 0L))

    /**
     * What was asked, and why it is not running when it is not, with how many times Photos has
     * left the foreground since this driver started: the identity a press's answer is checked
     * against (a press that returns after Photos left must not say *asked*).
     */
    val standing: StateFlow<ForegroundIndexingStanding> = held.asStateFlow()

    /** The delayed re-check after heat, when one is pending. */
    private val later = MutableStateFlow<Job?>(null)

    /** The ask ([ForegroundIndexingState.Asked.fromPass]) [ended] last resumed for a cleared block: never twice. */
    private val resumedAsk = MutableStateFlow<Long?>(null)

    /** Starts following the runner, the phone and its memory warnings. Once. */
    fun start() {
        scope.launch { passes.collect { standing -> if (standing is PassStanding.Idle) standing.last?.let { ended(it) } } }
        scope.launch { changes.collect { resumeIfClear() } }
        scope.launch { memoryWarnings.collect { memoryWarning() } }
    }

    /**
     * *Index now*. ⚠ Returns when the pass has started, been refused, or is waiting for the phone;
     * never when it ends.
     */
    suspend fun indexNow(): IndexNowAnswer {
        // ⚠ Both read BEFORE the start: a pass already running is joined, and its ending is this
        // ask's; and a press that returns after Photos left the foreground asks for nothing.
        val before: PassStanding = passes.value
        val leaves: Long = held.value.leftForegrounds
        return when (val answer: StartAnswer = startOrFailed()) {
            StartAnswer.Started -> {
                val fromPass: Long = passOfThisAsk(before)
                val now: ForegroundIndexingStanding = held.updateAndGet { was ->
                    if (was.leftForegrounds != leaves) was else was.copy(state = ForegroundIndexingState.Asked(fromPass))
                }
                if (now.leftForegrounds != leaves) {
                    // ⚠ Its own bar stops it at its next photo; this makes sure.
                    cancelPass()
                } else {
                    // ⚠ The pass may have ended already (it found nothing to do), and the
                    // collector may have read that ending before the ask was set.
                    (passes.value as? PassStanding.Idle)?.last?.let { ended(it) }
                    // ⚠⚠ Offered only now, AFTER the pass started — the press has started
                    // work whatever the platform answers, and a refusal leaves this foreground
                    // pass as it is. A pass that already ended is not offered.
                    continuation.offer(fromPass)
                }
                IndexNowAnswer.Started
            }
            is StartAnswer.Refused -> refused(answer.reason, leaves)
            StartAnswer.Failed -> IndexNowAnswer.Failed
        }
    }

    private fun refused(reason: String, leaves: Long): IndexNowAnswer {
        if (reason != NOT_READY) return IndexNowAnswer.Refused(reason)
        // ⚠ The op's manual bar is these conditions: read again for the reason. One that has
        // cleared in between is not invented: the op's own answer is said.
        val block: IndexingBlock = conditions.block()?.takeUnless { it == IndexingBlock.NOT_IN_FOREGROUND }
            ?: return IndexNowAnswer.Refused(reason)
        val now: Long = clock()
        val set: ForegroundIndexingStanding = held.updateAndGet { was ->
            if (was.leftForegrounds != leaves) was else was.copy(state = ForegroundIndexingState.Waiting(block, now))
        }
        // ⚠ Answered after Photos left: it waits for nothing, and says the op's refusal.
        if (set.state !is ForegroundIndexingState.Waiting) return IndexNowAnswer.Refused(reason)
        resumeLater()
        return IndexNowAnswer.Waiting(block)
    }

    /**
     * Photos left the foreground: the ask ends, the pass is cancelled at its next photo and both
     * towers are let go, inside a background window that ends when they have. ⚠ On the caller's
     * thread only the state and the window: the rest waits for a prediction, and is launched.
     */
    fun leftForeground() {
        leave(afterRelease = false)
    }

    /**
     * [leftForeground], and the continued task's release in the background ([afterRelease]).
     * ⚠⚠ After a release only an ask whose pass was still running becomes *stopped when you left*;
     * an ending [continuationSettled] already read (done, waiting for the heat) stays what it is,
     * and a wait keeps its re-check.
     */
    // ⚠ `CoroutineStart.ATOMIC` is experimental: opted in here, where it is load-bearing.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun leave(afterRelease: Boolean) {
        when (continuation.standing()) {
            // ⚠⚠ §3.3: a pass a continued task owns is that task's. Nothing is cancelled,
            // nothing closed and the ask stands; the leave is not counted either, so a press
            // answered now still asks for the pass the task carries. When the task lets go,
            // [continuationReleased] stops it if Photos is still not in front.
            ContinuationStanding.LIVE -> {
                println("ForegroundIndexing -> left the foreground with a continued task: the pass goes on")
                return
            }
            // ⚠⚠ Submitted, not yet launched: the pass is not carried yet,
            // and an answer that comes late would leave it reading the store with nothing keeping
            // Photos running. A background window is held until the claim resolves: ended at the
            // launch, or after the release a drop makes, which does this leave in full.
            ContinuationStanding.SUBMITTED -> {
                val pending: Any? = window.begin(PENDING_WINDOW_NAME)
                if (continuation.holdUntilResolved { window.end(pending) }) {
                    println("ForegroundIndexing -> left the foreground with a task submitted: held until it is answered")
                    return
                }
                // Resolved meanwhile: read it again.
                window.end(pending)
                return leave(afterRelease)
            }
            ContinuationStanding.NONE -> Unit
        }
        val prior: ForegroundIndexingStanding = held.getAndUpdate { was ->
            ForegroundIndexingStanding(
                state = when (was.state) {
                    is ForegroundIndexingState.Asked, ForegroundIndexingState.Resuming ->
                        ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LEFT_PHOTOS)
                    is ForegroundIndexingState.Waiting ->
                        if (afterRelease) was.state else ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LEFT_PHOTOS)
                    ForegroundIndexingState.NotAsked, is ForegroundIndexingState.Stopped -> was.state
                },
                leftForegrounds = was.leftForegrounds + 1,
            )
        }
        if (!(afterRelease && prior.state is ForegroundIndexingState.Waiting)) later.value?.cancel()
        val handle: Any? = window.begin(WINDOW_NAME)
        // ⚠ ATOMIC: the body runs even on a scope cancelled meanwhile (a teardown), so the window
        // is ended once, whatever happens. Its waits then end at once.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                cancelPass()
            } finally {
                try {
                    closeSessions()
                } finally {
                    window.end(handle)
                }
            }
        }
    }

    /**
     * The continued task's pass ended and the task is about to let go: its ending is read **now**,
     * while the claim still counts as the foreground, so the ask becomes what the pass's ending
     * says (done; waiting for the heat) whichever of this and the standing's collector runs first.
     * ⚠ [ended] is idempotent for one ending. Must not block.
     */
    fun continuationSettled() {
        (passes.value as? PassStanding.Idle)?.last?.let { ended(it) }
    }

    /**
     * The continued task let go of the pass (it ended, expired, was refused, or never launched).
     * ⚠⚠ If Photos is not in front, what leaving the foreground would have done is done now
     * ([leftForeground]: the pass cancelled, the towers closed, inside a background window); in
     * front, nothing — the pass is an ordinary foreground pass again. ⚠ Reads the bar, which
     * counts a live task as the foreground: called after the claim has gone. Must not block.
     */
    fun continuationReleased() {
        if (conditions.block() == IndexingBlock.NOT_IN_FOREGROUND) leave(afterRelease = true)
    }

    /** iOS warned that memory is low: the pass stops, both towers go, and a press resumes. */
    suspend fun memoryWarning() {
        held.update { was ->
            when (was.state) {
                is ForegroundIndexingState.Asked, ForegroundIndexingState.Resuming, is ForegroundIndexingState.Waiting ->
                    was.copy(state = ForegroundIndexingState.Stopped(ForegroundIndexingState.Stopped.Why.LOW_MEMORY))
                ForegroundIndexingState.NotAsked, is ForegroundIndexingState.Stopped -> was
            }
        }
        later.value?.cancel()
        try {
            cancelPass()
        } finally {
            closeSessions()
        }
    }

    /**
     * A pass ended. Only an ending of this ask's pass moves the state.
     *
     * ⚠⚠ A **deferral whose block has already cleared** is resumed at once, through the same
     * [ForegroundIndexingState.Resuming] claim a change takes: the block is read here, after the
     * pass ended, so a short one (heat touching `.serious`, a charger unplugged and replugged) can
     * be gone by now, and the ask would otherwise fall to `NotAsked` with nothing to resume it. ⚠
     * Once per ask: the stores' gate defers with no block too, and can be told apart from a cleared
     * block by nothing here, so a resumed ask deferred again with none is let go — unbounded, a
     * refusing gate would be a pass per turn of this loop.
     *
     * ⚠⚠ **Never while a continued task holds the pass**: the bar
     * then reads the task as the foreground, so a resume launched here could start a second pass
     * in the background just as the task lets go and the leave's window ends — a pass reading the
     * store with nothing keeping Photos running. Such a deferral ends the ask (`NotAsked`).
     */
    private fun ended(ending: PassEnding) {
        // ⚠ Read outside the update: the lambda may run again, and reads nothing itself.
        val block: IndexingBlock? = conditions.block()
        val now: Long = clock()
        val mayResume: Boolean = continuation.standing() == ContinuationStanding.NONE
        val retried: Long? = resumedAsk.value
        val step: (ForegroundIndexingStanding) -> ForegroundIndexingStanding = { was ->
            val asked: ForegroundIndexingState.Asked? = was.state as? ForegroundIndexingState.Asked
            when {
                asked == null || ending.pass < asked.fromPass -> was
                mayResume && clearedBeforeItEnded(ending, block) && asked.fromPass != retried -> was.copy(state = ForegroundIndexingState.Resuming)
                else -> was.copy(state = afterEnding(ending, block, now))
            }
        }
        val prior: ForegroundIndexingStanding = held.getAndUpdate(step)
        val next: ForegroundIndexingStanding = step(prior)
        when {
            next.state is ForegroundIndexingState.Waiting -> resumeLater()
            // ⚠ This call's claim (the step is pure, so `next` is what it wrote): started off it.
            next.state == ForegroundIndexingState.Resuming && prior.state is ForegroundIndexingState.Asked ->
                scope.launch { resumeClaimed(now, retry = true) }
        }
    }

    /**
     * Photos is in front again (the scene became active): an ask still waiting for the phone is
     * read again now — a wait kept across a continued task's release has its re-check spent in
     * the background. ⚠ Returns at once.
     */
    fun enteredForeground() {
        scope.launch { resumeIfClear() }
    }

    /** The phone moved: an ask that waits for it starts again if nothing blocks it now. */
    private suspend fun resumeIfClear() {
        val block: IndexingBlock? = conditions.block()
        // ⚠⚠ Not in front is "not now", never a block of its own: only a wait kept across a
        // continued task's release is read in the background, and rewriting it to
        // `Waiting(NOT_IN_FOREGROUND)` would lose its block and its clock, arm no re-check, and
        // draw as *stopped when you left*. Left as it is; [enteredForeground] reads
        // it again.
        if (block == IndexingBlock.NOT_IN_FOREGROUND) return
        val now: Long = clock()
        // ⚠⚠ The check and the claim in one step: two changes in a row start one pass.
        val was: ForegroundIndexingState = held.getAndUpdate { standing ->
            val state: ForegroundIndexingState = standing.state
            when {
                state !is ForegroundIndexingState.Waiting -> standing
                // ⚠ Another block starts its own rest: the clock is the current block's.
                block != null -> standing.copy(state = ForegroundIndexingState.Waiting(block, if (block == state.block) state.sinceMs else now))
                !restedEnough(state, now) -> standing
                else -> standing.copy(state = ForegroundIndexingState.Resuming)
            }
        }.state
        if (was !is ForegroundIndexingState.Waiting || block != null) return
        if (!restedEnough(was, now)) return resumeLater()
        resumeClaimed(now, retry = false)
    }

    /**
     * Starts the ask this driver claimed ([ForegroundIndexingState.Resuming]) again.
     * @param retry the claim was a deferral's whose block had cleared ([ended]): the ask it
     *   starts is not resumed that way a second time.
     */
    private suspend fun resumeClaimed(now: Long, retry: Boolean) {
        val before: PassStanding = passes.value
        // ⚠⚠ Through [startOrFailed], never [startPass] bare: a throw here would kill the collector
        // (or the `later` job) and leave the ask `Resuming` for good, with nothing logged.
        val answer: StartAnswer = startOrFailed()
        val fromPass: Long = passOfThisAsk(before)
        // ⚠ Before the ask is set: an ending that lands between the two must already see it.
        if (retry) resumedAsk.value = fromPass
        val again: IndexingBlock? = conditions.block()
        val after: ForegroundIndexingState = held.updateAndGet { standing ->
            if (standing.state != ForegroundIndexingState.Resuming) return@updateAndGet standing
            standing.copy(state = afterResume(answer, fromPass, again, now))
        }.state
        when {
            // ⚠⚠ Left the foreground (or a memory warning) while it started: the pass it started
            // is stopped too. Its own bar stops it at the next photo; this makes sure.
            // ⚠ The cancel is of whatever pass runs then: a press made after the leave and before
            // this line had its pass cancelled with it. Rare (two scene changes inside one start),
            // and the press's own answer said *started*; the next press starts it again.
            answer == StartAnswer.Started && after != ForegroundIndexingState.Asked(fromPass) -> cancelPass()
            after is ForegroundIndexingState.Asked -> (passes.value as? PassStanding.Idle)?.last?.let { ended(it) }
            after is ForegroundIndexingState.Waiting -> resumeLater()
        }
    }

    /**
     * [startPass], with a throw that is not a cancellation answered as [StartAnswer.Failed] and
     * logged. ⚠⚠ The try lives here, in its own function, and its value is returned
     * whole: an in-place type test of an inline-try value whose default is a sibling class is the
     * Kotlin/Native third face (`tech-debt.md`), still open on 2.4.20.
     */
    private suspend fun startOrFailed(): StartAnswer {
        try {
            return startPass()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            println("ForegroundIndexing -> the start failed: $failure")
            return StartAnswer.Failed
        }
    }

    private fun restedEnough(state: ForegroundIndexingState.Waiting, now: Long): Boolean =
        state.block != IndexingBlock.TOO_WARM || now - state.sinceMs >= restAfterHeatMs

    /** One re-check once the rest after heat has passed: no change may come after it. */
    private fun resumeLater() {
        val waiting: ForegroundIndexingState.Waiting = held.value.state as? ForegroundIndexingState.Waiting ?: return
        val remaining: Long = if (waiting.block == IndexingBlock.TOO_WARM) restAfterHeatMs - (clock() - waiting.sinceMs) else 0L
        val next: Job = scope.launch {
            delay(remaining.coerceAtLeast(0L) + 1L)
            resumeIfClear()
        }
        later.getAndUpdate { next }?.cancel()
    }

    /**
     * The number of the first pass that answers an ask whose start found [before] and answered
     * *started*: read once the start has returned.
     *
     * ⚠⚠ Not [before] alone: the op reads the stores before it starts the
     * runner, so the pass [before] saw running can end in between and the op launch the next. The
     * ask then belongs to that next pass, and an ending of the one [before] saw — delivered after
     * the ask was set — must not end it. So the standing now wins when it names a later pass: a
     * join still gets the pass it joined, a fresh launch the one launched.
     */
    private fun passOfThisAsk(before: PassStanding): Long {
        val derived: Long = when (before) {
            // ⚠ Joined: the pass that was running is this ask's.
            is PassStanding.Running -> before.pass
            is PassStanding.Idle -> (before.last?.pass ?: 0L) + 1L
        }
        val current: Long? = when (val now: PassStanding = passes.value) {
            is PassStanding.Running -> now.pass
            is PassStanding.Idle -> now.last?.pass
        }
        return if (current != null && current > derived) current else derived
    }

    companion object {
        /** The op's refusal by the manual bar. */
        const val NOT_READY: String = org.tenshu.inference.IndexPassRefused.Reasons.NOT_READY

        /** ⚠ A placeholder (§3.3, §4.5): how long a pass stopped by heat rests before it starts again. */
        const val REST_AFTER_HEAT_MS: Long = 60_000L

        private const val WINDOW_NAME = "app.tenshu.photos.index-pass.stop"

        /** The window a leave holds while a continued task is submitted and not yet answered. */
        private const val PENDING_WINDOW_NAME = "app.tenshu.photos.index-pass.continuing"
    }
}

/** [ForegroundIndexing.standing]. */
data class ForegroundIndexingStanding(val state: ForegroundIndexingState, val leftForegrounds: Long)

/**
 * Whether [ending] was a deferral the phone no longer explains: its block cleared before the ending
 * was read, or the stores' gate refused it (the two look alike from here). [ForegroundIndexing]
 * resumes it once.
 */
internal fun clearedBeforeItEnded(ending: PassEnding, block: IndexingBlock?): Boolean =
    ending is PassEnding.Finished && ending.result.deferred && block == null

/**
 * What an ask is once its pass ended. ⚠ Deferred is stopped by the bar: waited out only for a block
 * the phone still says; a deferral nothing here can name (the stores' gate) is not, beyond the one
 * resume [ForegroundIndexing] gives a deferral with no block.
 * ⚠ Stopped and failed end the ask: leaving the foreground and a memory warning set their own
 * state before they stop it, and a model leaving `Ready` ends it.
 */
internal fun afterEnding(ending: PassEnding, block: IndexingBlock?, now: Long): ForegroundIndexingState = when (ending) {
    is PassEnding.Finished ->
        if (ending.result.deferred && block != null && block != IndexingBlock.NOT_IN_FOREGROUND) {
            ForegroundIndexingState.Waiting(block, now)
        } else {
            ForegroundIndexingState.NotAsked
        }
    is PassEnding.Stopped, is PassEnding.Failed -> ForegroundIndexingState.NotAsked
}

/** What a resume is once the start answered. ⚠ Refused by the bar again in between: still waiting. */
internal fun afterResume(answer: StartAnswer, fromPass: Long, block: IndexingBlock?, now: Long): ForegroundIndexingState = when (answer) {
    StartAnswer.Started -> ForegroundIndexingState.Asked(fromPass)
    is StartAnswer.Refused ->
        if (answer.reason == ForegroundIndexing.NOT_READY && block != null && block != IndexingBlock.NOT_IN_FOREGROUND) {
            ForegroundIndexingState.Waiting(block, now)
        } else {
            ForegroundIndexingState.NotAsked
        }
    StartAnswer.Failed -> ForegroundIndexingState.NotAsked
}
