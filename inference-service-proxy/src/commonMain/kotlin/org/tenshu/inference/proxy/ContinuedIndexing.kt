package org.tenshu.inference.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The platform's task for one continued pass: a `BGContinuedProcessingTask` on iOS 26+
 * (`ios-on-device-inference.md` §15). ⚠ The platform expires a task that reports no progress.
 */
interface ContinuedTask {
    /** Where the pass has got to. [total] is `0` while the library is walked. */
    fun progress(done: Long, total: Long)

    /** `setTaskCompleted(success:)`. ⚠ Called exactly once per launched task, by [ContinuedIndexing]. */
    fun complete(success: Boolean)
}

/**
 * Asks the platform to continue a pass once the app leaves the foreground: on iOS, registers a
 * launch handler for [identifier] — the **fully composed** identifier, suffix included — and
 * submits a `BGContinuedProcessingTaskRequest` with it, `strategy = .fail` (never the default
 * `.queue`: a queued request may not launch while the user is still in front, §15).
 *
 * The answer comes back through [ContinuedIndexing.answered], [ContinuedIndexing.launched] and
 * [ContinuedIndexing.expired], on any thread and in any order. ⚠ Must not block.
 */
fun interface ContinuedTaskScheduler {
    fun submit(identifier: String)
}

/** Where a continuation stands ([PassContinuation.standing]). */
enum class ContinuationStanding {
    /** No task: a leave cancels, as in a plain foreground pass. */
    NONE,

    /**
     * Submitted, not yet launched or refused. ⚠⚠ The pass is not yet carried: a leave opens a
     * background window and holds it until the claim resolves ([PassContinuation.holdUntilResolved]).
     */
    SUBMITTED,

    /** Launched: the task carries the pass, and a leave cancels nothing. */
    LIVE,
}

/**
 * What the foreground driver ([ForegroundIndexing]) asks of a continuation. [None] everywhere a
 * pass cannot continue in the background: Android, desktop, iOS before 26, a build without the
 * scheduler.
 */
interface PassContinuation {
    /** A press started (or joined) pass [pass]: ask the platform to let it continue. Returns at once. */
    fun offer(pass: Long)

    /**
     * Whether a continued task is submitted or live for the running pass: the pass's own bar
     * counts it as the foreground. ⚠ Not what a leave asks: [standing] is.
     */
    fun holdsPass(): Boolean

    /** [ContinuationStanding] now. */
    fun standing(): ContinuationStanding

    /**
     * While [ContinuationStanding.SUBMITTED]: [end] is called once the claim resolves — at the
     * launch, or after the release a drop or an expiry makes. `false`, and [end] never called,
     * when the claim is no longer submitted. ⚠ [end] must not block.
     */
    fun holdUntilResolved(end: () -> Unit): Boolean

    object None : PassContinuation {
        override fun offer(pass: Long) = Unit
        override fun holdsPass(): Boolean = false
        override fun standing(): ContinuationStanding = ContinuationStanding.NONE
        override fun holdUntilResolved(end: () -> Unit): Boolean = false
    }
}

/**
 * **The continued pass** (`ios-on-device-inference.md` §15): *Index now* goes on when the user
 * leaves Photos, as a `BGContinuedProcessingTask` the system shows with its progress.
 *
 * ⚠⚠ **The press starts the pass first, then offers it** ([offer]): the pass is already running
 * in the foreground when the request is submitted, so a press always starts work at once, and a
 * refused request (`.fail`) leaves exactly the foreground pass (§14), which a leave cancels. On
 * launch the task owns the pass ([ContinuationStanding.LIVE]): the leave does not cancel, and only
 * the task's end or its expiry lets go of it. ⚠⚠ Between submission and launch a leave holds a
 * background window open until the claim resolves ([holdUntilResolved]).
 *
 * ⚠⚠ **A progress and completion bridge.** The runner's `start` returns at once, so the task
 * waits on the pass's **terminal outcome** ([MediaIndexRunner.outcomeOf]), never on the bar:
 * [IndexProgressHolder] publishes one `null` for every ending. The bar is mirrored into
 * [ContinuedTask.progress] only. `complete(true)` on [PassOutcome.COMPLETED] alone.
 *
 * ⚠⚠ **No task is left uncompleted**: a launch nobody here asked for (a stale or refused
 * identifier, a graph that was replaced), a scope cancelled under a live task, and every outcome
 * each complete the task once.
 *
 * ⚠⚠ **When the claim ends** — the pass's outcome, an expiry, a refusal, a launch that never came
 * — [released] is told, and the owner does what leaving the foreground would have done if Photos
 * is not in front (`ForegroundIndexing.continuationReleased`): the pass stopped and the towers
 * let go inside a background window, **before** the task is completed, so the app is not
 * suspended with a pass still reading the shared replica.
 *
 * @param scheduler the platform's, or `null` when this build or this phone cannot continue a pass
 *   (asked at each offer).
 * @param outcomeOf the runner's [MediaIndexRunner.outcomeOf].
 * @param progress the runner's bar, the same holder the indexer ticks.
 * @param released the claim ended: in the background the owner does the leave's stop, which
 *   cancels the pass at its next photo (an expiry's stop, too). ⚠ Must not block.
 * @param identifierPrefix the permitted wildcard's base, with its trailing dot.
 * @param newSuffix a fresh suffix per request: a handler registered twice for one identifier is a
 *   kill on iOS.
 */
class ContinuedIndexing(
    private val scope: CoroutineScope,
    private val scheduler: () -> ContinuedTaskScheduler?,
    private val outcomeOf: (Long) -> Deferred<PassOutcome>?,
    private val progress: () -> StateFlow<IndexProgressSnapshot?>,
    /**
     * The pass ended and the task is about to let go: the owner reads the ending **now, while the
     * claim still holds** (`ForegroundIndexing.continuationSettled`), so what the ask becomes —
     * done, or waiting for the heat — does not depend on whether its collector or [released] runs
     * first. ⚠ Must not block.
     */
    private val settled: () -> Unit,
    /** The claim ended. ⚠ Called on the thread that ended it; must not block. */
    private val released: () -> Unit,
    private val identifierPrefix: String,
    private val newSuffix: () -> String,
    private val launchGraceMs: Long = LAUNCH_GRACE_MS,
) : PassContinuation {

    private sealed interface Claim {
        data object None : Claim

        /**
         * Submitted, not yet launched: holds the pass for at most [launchGraceMs]. [held] are the
         * background windows a leave opened meanwhile, ended when the claim resolves.
         */
        data class Submitted(val identifier: String, val pass: Long, val held: List<() -> Unit> = emptyList()) : Claim

        /** Launched: the task owns the pass until its outcome or its expiry. */
        data class Live(val identifier: String, val pass: Long, val task: OnceTask) : Claim
    }

    private val claim = MutableStateFlow<Claim>(Claim.None)

    /** A test seam: run between an expiry's read of the claim and its swap. Null in production. */
    internal var betweenExpiryReadAndSwapForTests: (() -> Unit)? = null

    /**
     * One transition of [claim]: [step] answers the next claim, or `null` for *nothing to do*;
     * retried while another thread moved the claim in between. Returns the claim it replaced.
     */
    private inline fun swap(between: () -> Unit = {}, step: (Claim) -> Claim?): Claim? {
        while (true) {
            val now: Claim = claim.value
            val next: Claim = step(now) ?: return null
            between()
            if (claim.compareAndSet(now, next)) return now
        }
    }

    override fun holdsPass(): Boolean = claim.value != Claim.None

    override fun standing(): ContinuationStanding = standingOf(claim.value)

    /**
     * [standing] as it moves — what the Models screen's running line follows
     * (`ios-on-device-inference.md` §15.7 Q2): *Keep Photos open* is true only while no task
     * carries the pass. ⚠ Emits at once, and never completes.
     */
    val standings: Flow<ContinuationStanding> = claim.map { standingOf(it) }.distinctUntilChanged()

    private fun standingOf(now: Claim): ContinuationStanding = when (now) {
        Claim.None -> ContinuationStanding.NONE
        is Claim.Submitted -> ContinuationStanding.SUBMITTED
        is Claim.Live -> ContinuationStanding.LIVE
    }

    override fun holdUntilResolved(end: () -> Unit): Boolean =
        swap { now -> if (now is Claim.Submitted) now.copy(held = now.held + end) else null } != null

    /** The identifier submitted or launched now, or `null`. For a test and the log. */
    fun identifier(): String? = when (val now: Claim = claim.value) {
        Claim.None -> null
        is Claim.Submitted -> now.identifier
        is Claim.Live -> now.identifier
    }

    // ⚠ `CoroutineStart.ATOMIC` is experimental: opted in here, where it is load-bearing.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun offer(pass: Long) {
        val platform: ContinuedTaskScheduler = scheduler() ?: return
        // ⚠ A pass that has already ended (an empty library) has nothing to continue.
        val outcome: Deferred<PassOutcome> = outcomeOf(pass) ?: return
        if (outcome.isCompleted) return
        val identifier: String = identifierPrefix + newSuffix()
        // ⚠ One continued task at a time: a press that joined the running pass asks for nothing.
        if (!claim.compareAndSet(Claim.None, Claim.Submitted(identifier, pass))) return
        try {
            platform.submit(identifier)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            println("ContinuedIndexing -> the request could not be submitted: $failure")
            answered(identifier, accepted = false)
            return
        }
        // ⚠⚠ Bounded: a request answered *accepted* whose handler never runs must not keep a pass
        // running in the background with nothing to keep the app alive. ⚠ While it waits, a leave
        // holds a background window open, so this timer still gets to run.
        // ⚠ ATOMIC, and the drop in `finally`: a graph torn down while the
        // claim is submitted cancels this timer, and the windows a leave held must still end.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                delay(launchGraceMs)
            } finally {
                drop(identifier, "no launch within ${launchGraceMs}ms, or the graph went")
            }
        }
    }

    /**
     * The platform answered the submission of [identifier]. ⚠ May come before or after
     * [launched]; only a refusal moves anything: the pass stays the ordinary foreground pass.
     */
    fun answered(identifier: String, accepted: Boolean) {
        if (!accepted) drop(identifier, "the request was refused")
    }

    /**
     * The platform launched [identifier]'s task. ⚠ Every launch completes its [task] once: one
     * this bridge did not ask for (refused meanwhile, out of time, another graph's) at once, with
     * `false`.
     */
    // ⚠ `CoroutineStart.ATOMIC` is experimental: opted in here, where it is load-bearing.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun launched(identifier: String, task: ContinuedTask) {
        val once = OnceTask(task)
        var live: Claim.Live? = null
        val prior: Claim? = swap { now ->
            if (now is Claim.Submitted && now.identifier == identifier) Claim.Live(identifier, now.pass, once).also { live = it } else null
        }
        val owned: Claim.Live? = live
        if (prior !is Claim.Submitted || owned == null) {
            println("ContinuedIndexing -> a task this pass did not ask for ($identifier): completed at once")
            once.complete(false)
            return
        }
        // The task carries the pass now: the windows a leave held for it may end.
        prior.held.forEach { it() }
        // ⚠⚠ ATOMIC: the body runs even on a scope cancelled meanwhile (the graph's teardown), so
        // its `finally` completes the task whatever happens.
        scope.launch(start = CoroutineStart.ATOMIC) {
            var success = false
            try {
                success = awaitOutcome(owned.pass, once) == PassOutcome.COMPLETED
            } finally {
                // ⚠⚠ The ending read while the claim still holds, then released,
                // and only then the task completed: the owner's stop opens its background window
                // while the task still keeps the app running.
                if (claim.value == owned) settled()
                if (swap { now -> if (now == owned) Claim.None else null } != null) released()
                once.complete(success)
            }
        }
    }

    /**
     * The platform expired [identifier]'s task (or the user stopped it). The claim ends, in one
     * transition whether it was submitted or live (a launch racing the expiry is retried, never
     * lost), and the owner is told: in the background that is the leave's stop,
     * which cancels the pass at its next photo; ⚠⚠ in front, nothing — the pass goes on as an
     * ordinary foreground pass. The task is completed with `false` at once.
     */
    fun expired(identifier: String) {
        // ⚠ A live task's pass may have ended already, its `finally` not yet
        // run: its ending is read first, while the claim still holds, as the `finally` would.
        val current: Claim = claim.value
        if (current is Claim.Live && current.identifier == identifier) settled()
        val prior: Claim = swap(between = { betweenExpiryReadAndSwapForTests?.invoke() }) { now ->
            val ours: Boolean = when (now) {
                Claim.None -> false
                is Claim.Submitted -> now.identifier == identifier
                is Claim.Live -> now.identifier == identifier
            }
            if (ours) Claim.None else null
        } ?: return
        println("ContinuedIndexing -> the task expired ($identifier)")
        released()
        when (prior) {
            Claim.None -> Unit
            is Claim.Submitted -> prior.held.forEach { it() }
            is Claim.Live -> prior.task.complete(false)
        }
    }

    private fun drop(identifier: String, why: String) {
        val prior: Claim = swap { now -> if (now is Claim.Submitted && now.identifier == identifier) Claim.None else null } ?: return
        println("ContinuedIndexing -> not continued ($why): the pass stays a foreground pass")
        // ⚠ Released first: in the background that opens the leave's own window, so the windows
        // held while it was submitted may then end.
        released()
        (prior as? Claim.Submitted)?.held?.forEach { it() }
    }

    /** The pass's terminal outcome, its bar mirrored into [task] meanwhile. `null` for a pass the runner forgot. */
    private suspend fun awaitOutcome(pass: Long, task: OnceTask): PassOutcome? {
        val outcome: Deferred<PassOutcome> = outcomeOf(pass) ?: return null
        return coroutineScope {
            val mirror = launch {
                progress().collect { snapshot -> if (snapshot != null) task.progress(snapshot.done.toLong(), snapshot.total.toLong()) }
            }
            try {
                outcome.await()
            } finally {
                mirror.cancel()
            }
        }
    }

    /** [ContinuedTask] completed once, whoever asks first (the outcome or an expiry); no progress after it. */
    private class OnceTask(private val task: ContinuedTask) {
        private val done = MutableStateFlow(false)

        fun progress(done: Long, total: Long) {
            if (!this.done.value) task.progress(done, total)
        }

        fun complete(success: Boolean) {
            if (done.compareAndSet(expect = false, update = true)) task.complete(success)
        }
    }

    companion object {
        /** How long a submitted request holds the pass before its handler must have run. ⚠ A placeholder [I]. */
        const val LAUNCH_GRACE_MS: Long = 10_000L
    }
}
