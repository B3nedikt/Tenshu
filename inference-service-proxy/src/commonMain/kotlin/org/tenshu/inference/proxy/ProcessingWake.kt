package org.tenshu.inference.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The platform's one launched processing wake: a `BGProcessingTask` on iOS
 * (`ios-on-device-inference.md` PR-6, §16).
 */
interface WakeTask {
    /** `setTaskCompleted(success:)`. ⚠ Called exactly once per launched wake, by [ProcessingWake]. */
    fun complete(success: Boolean)
}

/**
 * Asks the platform for the next wake, or withdraws it. ⚠ Neither may block.
 *
 * On iOS a `BGProcessingTaskRequest` under one fixed identifier, `requiresExternalPower = true`
 * (D5: never on battery) and `requiresNetworkConnectivity = false`. A submission replaces a pending
 * request of the same identifier, so submitting twice arms one wake.
 */
interface WakeScheduler {
    /** Arm (or replace) the next wake. */
    fun submit()

    /** Withdraw a pending request: nothing is left for a wake to do. */
    fun withdraw()
}

/** What a wake would find to do ([ProcessingWake]'s `work`). */
enum class WakeWork {
    /**
     * Nothing is asked of this device: no half's two switches are on (absence is NO, IN-D3), no
     * model serves a half that is, or indexing is paused here. ⚠ A wake does nothing and is not
     * re-armed.
     */
    NOT_ASKED,

    /** Asked, and the library was current when this process last looked: no wake is wanted. */
    NONE_LEFT,

    /** Asked, and photos may be left to index. */
    LEFT,
}

/**
 * **The processing wake** (`ios-on-device-inference.md` PR-6, §16): the library indexed overnight on
 * the charger, in a `BGProcessingTask` iOS grants when the phone is charging and idle.
 *
 * - ⚠ **Armed when Photos leaves the foreground** with something asked and photos left
 *   ([leftForeground]), and **re-armed at the start of every wake, before it works**: a grant is
 *   one-shot, and a wake that is expired or killed must not have been the last.
 * - ⚠⚠ **Inside a wake the scheduled bar may say yes** ([carriesPass], read by
 *   `ForegroundIndexingConditions.allowIndexing`); outside one it never does. The pass is the
 *   runner's scheduled pass ([runPass]), so the heat stop, Low Power Mode and the charger are read
 *   per photo as for any pass, and a pass that stops for one of them ends the wake.
 * - ⚠⚠ **An exactly-once completion bridge.** The task is completed from the pass's **terminal
 *   outcome**, never from the progress bar: `true` on [PassOutcome.COMPLETED] (and when nothing was
 *   asked or left), `false` on anything else. On an expiry ([expired]) the pass is cancelled and the
 *   completion waits for it to stop — at most [expiryStopMs] — before `false`. An expiry racing a
 *   normal finish completes the task once, whichever reached it first.
 * - ⚠⚠ **When the wake ends** [released] is told before the task is completed: the owner does what
 *   leaving the foreground does when Photos is not in front (the pass stopped and both models let go
 *   inside a background window), so the app is not suspended with a model resident or a pass reading
 *   the shared replica.
 * - ⚠ A completed pass withdraws the request it re-armed: a current library does not wake the phone
 *   every night.
 *
 * @param scheduler the platform's, or `null` when this build or this phone has none (asked each time).
 * @param work what a wake would find to do. ⚠ Asked per wake and per leave, never cached; any failure
 *   to read it is [WakeWork.NOT_ASKED].
 * @param runPass the runner's scheduled pass, run to its end, as its terminal outcome. ⚠ Must cancel
 *   the pass when its caller is cancelled.
 * @param stopPass stops the running pass and waits for it ([MediaIndexRunner.cancelRunningPass]).
 * @param released the wake let go: in the background the owner's stop. ⚠ Must not block.
 * @param window a background window held around the arm made on leaving the foreground.
 * @param clock milliseconds, for the wake's log line.
 */
class ProcessingWake(
    private val scope: CoroutineScope,
    private val scheduler: () -> WakeScheduler?,
    private val work: suspend () -> WakeWork,
    private val runPass: suspend () -> PassOutcome,
    private val stopPass: suspend () -> Unit,
    private val released: () -> Unit,
    private val window: BackgroundWindow = BackgroundWindow.None,
    private val clock: () -> Long = { 0L },
    private val expiryStopMs: Long = EXPIRY_STOP_MS,
) {
    /** One launched wake. [job] is cancelled by its expiry, whichever of the two is set first. */
    private class Wake(val task: OnceWakeTask) {
        val expiring = MutableStateFlow(false)
        val job = MutableStateFlow<Job?>(null)

        /** Between steps: an expiry the job did not hear of yet (it was not known then) stops here. */
        fun stopIfExpired() {
            if (expiring.value) throw CancellationException("the processing wake expired")
        }

        fun cancelIfExpiring() {
            if (expiring.value) job.value?.cancel(CancellationException("the processing wake expired"))
        }
    }

    private val current = MutableStateFlow<Wake?>(null)

    /**
     * Whether a wake carries the pass now: the scheduled bar's "inside a wake", and what keeps
     * Photos' leave from cancelling the wake's pass. ⚠ `false` from the moment the wake expires, so
     * the pass's bar says no at its next photo even before the cancel lands.
     */
    fun carriesPass(): Boolean = current.value?.let { !it.expiring.value } ?: false

    /**
     * Photos left the foreground: the next wake is armed if something is asked and photos are left.
     * ⚠ Returns at once; the read runs inside a background window. Nothing while a wake runs: it
     * re-armed itself when it began.
     */
    // ⚠ `CoroutineStart.ATOMIC` is experimental: opted in here, where it is load-bearing.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun leftForeground() {
        val platform: WakeScheduler = scheduler() ?: return
        if (current.value != null) return
        val held: Any? = window.begin(ARM_WINDOW_NAME)
        // ⚠ ATOMIC: a teardown meanwhile still ends the window.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                if (workOrNotAsked() == WakeWork.LEFT) {
                    platform.submit()
                    println("ProcessingWake -> armed on leaving the foreground")
                }
            } finally {
                window.end(held)
            }
        }
    }

    /**
     * The platform launched a wake. ⚠⚠ Every [task] handed here is completed exactly once. A wake
     * launched while another runs (the platform does not do this) is completed at once with `false`.
     *
     * @param prepare what must finish before the wake asks what is asked of it (on iOS, the owner's
     *   store check, without which no backend serves). Inside the wake, so an expiry ends it too.
     */
    // ⚠ `CoroutineStart.ATOMIC` is experimental: opted in here, where it is load-bearing.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun launched(task: WakeTask, prepare: suspend () -> Unit = {}) {
        val once = OnceWakeTask(task)
        val wake = Wake(once)
        if (!current.compareAndSet(null, wake)) {
            println("ProcessingWake -> a wake while another runs: completed at once")
            once.complete(false)
            return
        }
        val began: Long = clock()
        println("[photos-wake] launched")
        // ⚠⚠ ATOMIC: the body runs even on a scope cancelled meanwhile (the graph's teardown), so its
        // `finally` completes the task whatever happens.
        val job: Job = scope.launch(start = CoroutineStart.ATOMIC) {
            var success = false
            var outcome: PassOutcome? = null
            try {
                ensureActive()
                prepare()
                wake.stopIfExpired()
                when (workOrNotAsked()) {
                    WakeWork.NOT_ASKED -> {
                        // ⚠⚠ Two switches, absence is NO: nothing runs, and nothing is re-armed.
                        println("ProcessingWake -> nothing is asked of this device: no pass, not re-armed")
                        success = true
                    }
                    WakeWork.NONE_LEFT -> {
                        scheduler()?.withdraw()
                        success = true
                    }
                    WakeWork.LEFT -> {
                        // ⚠⚠ Re-armed BEFORE it works: a grant is one-shot, and this wake may be
                        // expired or killed before its pass ends.
                        scheduler()?.submit()
                        wake.stopIfExpired()
                        val ended: PassOutcome = runPass()
                        outcome = ended
                        success = ended == PassOutcome.COMPLETED
                        // ⚠ The library is current: the request just re-armed is withdrawn.
                        if (ended == PassOutcome.COMPLETED) scheduler()?.withdraw()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // ⚠⚠ Never out of the coroutine: an uncaught throw fails the graph's scope, and on
                // Kotlin/Native ends the process. A pass that throws is FAILED.
                println("ProcessingWake -> the wake failed: $failure")
                if (outcome == null) outcome = PassOutcome.FAILED
                success = false
            } finally {
                withContext(NonCancellable) {
                    // ⚠⚠ The expiry path: the pass is stopped, and waited for, before the task is
                    // completed. Bounded: a pass whose next store access met the closed gate parks
                    // its thread until the foreground, and must not keep the task open.
                    if (wake.expiring.value) {
                        if (withTimeoutOrNull(expiryStopMs) { stopPass() } == null) {
                            println("ProcessingWake -> the pass did not stop within ${expiryStopMs}ms of the expiry")
                        }
                    }
                }
                val expired: Boolean = wake.expiring.value
                if (current.compareAndSet(wake, null)) released()
                println("[photos-wake] ended outcome=${outcome ?: "none"} expired=$expired success=$success ms=${clock() - began}")
                once.complete(success)
            }
        }
        // ⚠ An expiry that came before the job was known cancels it now.
        wake.job.value = job
        wake.cancelIfExpiring()
    }

    /**
     * The platform expired the wake. The scheduled bar says no at once ([carriesPass]); the pass is
     * cancelled at its next photo and the task completed with `false` once it has stopped. ⚠ Returns
     * at once. ⚠⚠ Bounded twice: the wake's own `finally` waits for the stop at most [expiryStopMs],
     * and a backstop completes the task even if the wake's coroutine is held where it cannot be
     * cancelled (a thread parked at the store's closed gate).
     */
    // ⚠ `CoroutineStart.ATOMIC` is experimental: opted in here, where it is load-bearing.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun expired() {
        val wake: Wake = current.value ?: run {
            println("ProcessingWake -> an expiry with no wake running")
            return
        }
        if (!wake.expiring.compareAndSet(expect = false, update = true)) return
        println("ProcessingWake -> the wake expired")
        wake.cancelIfExpiring()
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                delay(expiryStopMs + EXPIRY_BACKSTOP_MARGIN_MS)
            } finally {
                if (current.compareAndSet(wake, null)) released()
                wake.task.complete(false)
            }
        }
    }

    private suspend fun workOrNotAsked(): WakeWork = try {
        work()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        println("ProcessingWake -> what is asked could not be read, so nothing is assumed: $failure")
        WakeWork.NOT_ASKED
    }

    /** [WakeTask] completed once, whoever asks first (the wake's end, or the expiry's backstop). */
    private class OnceWakeTask(private val task: WakeTask) {
        private val done = MutableStateFlow(false)

        fun complete(success: Boolean) {
            if (done.compareAndSet(expect = false, update = true)) task.complete(success)
        }
    }

    companion object {
        /**
         * How long an expired wake waits for its pass to stop before it completes the task anyway.
         * ⚠ A placeholder [I]: what iOS allows after a `BGProcessingTask`'s expiry is unstated, and
         * the store's own drain is 2 s (`ios-shared-store-sqlite.md` B.23 (441)). 📱 §16.6.
         */
        const val EXPIRY_STOP_MS: Long = 3_000L

        /** The backstop's margin past [EXPIRY_STOP_MS]. */
        const val EXPIRY_BACKSTOP_MARGIN_MS: Long = 1_000L

        private const val ARM_WINDOW_NAME = "app.tenshu.photos.processing.arm"
    }
}

/**
 * A wake pass's report as its terminal outcome: a deferred or paused report (the bar refused it, or
 * stopped it mid-library — heat, Low Power Mode, the charger gone, the wake's own end) is
 * [PassOutcome.REFUSED], anything else ran to its end. ⚠ A throw and a cancellation never reach a
 * report: the wake answers them itself.
 */
fun wakeOutcomeOf(result: IndexPassResult): PassOutcome =
    if (result.deferred || result.paused) PassOutcome.REFUSED else PassOutcome.COMPLETED

/**
 * A wake's pass over [runner]: its **scheduled** pass run to its end, as its terminal outcome
 * (`ios-on-device-inference.md` §16). ⚠⚠ A pass a person started that is running now is left alone
 * and the wake does no pass ([PassOutcome.REFUSED], its request still armed): joining it would make
 * the wake's expiry the end of the user's pass. A press that starts after this read supersedes the
 * wake's pass as the runner's rule says, and the expiry's stop
 * ([MediaIndexRunner.cancelRunningScheduledPass]) never ends a manual one.
 *
 * @param reported the pass's report, for the wake's log line.
 */
suspend fun runWakePassOver(
    runner: MediaIndexRunner,
    executionContext: org.tenshu.core.common.proxy.ExecutionContext,
    reported: (IndexPassResult) -> Unit = {},
): PassOutcome {
    val now: PassStanding = runner.standing.value
    if (now is PassStanding.Running && now.manual) {
        println("ProcessingWake -> a pass the user started is running: the wake leaves it alone")
        return PassOutcome.REFUSED
    }
    val result: IndexPassResult = runner.runToCompletion(executionContext, manual = false)
    reported(result)
    return wakeOutcomeOf(result)
}
