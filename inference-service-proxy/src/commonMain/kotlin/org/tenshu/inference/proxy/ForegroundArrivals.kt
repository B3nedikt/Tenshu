package org.tenshu.inference.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * **New arrivals while Photos is in the foreground** (`inference-service.md` IN-D6a, the iOS half;
 * `ios-on-device-inference.md` PR-6b, §17): a small batch of photos no pass has seen is indexed
 * without a press, while Photos is in front, and never behind it.
 *
 * - ⚠⚠ **"Arrived" is a list of paths, never a walk.** [follow] takes the library watch's changes
 *   (on iOS `LibraryWatch.revision`, the one Collections reads) as the cue to [look]: which
 *   photographs arrived goes into the shared [ArrivalQueue] (on iOS from the modification log's newest
 *   rows, `ArrivedPhotoPaths`). ⚠⚠ The batch is over the queued paths alone ([runBatch] is
 *   `MediaIndexRunner.runArrivalsBatch` over that queue): no tree walk, no whole-library reconcile,
 *   and an arrival behind any backlog is still the one indexed. A look that leaves the queue empty
 *   runs no pass. A fresh process owes one look to its first foreground; a watch that does not vouch
 *   owes one to every foreground.
 * - ⚠ **What a batch did not process stays queued** — the queue's own rules (`ArrivalQueue.settle`):
 *   not reached (the cap, the bar, a leave) stays; not on this device is tried a few times.
 * - ⚠ **Debounced**: every change restarts [debounceMs]; a burst of arrivals is one batch.
 * - ⚠⚠ **Only in the foreground.** A change behind Photos is remembered, never acted on: iOS gives a
 *   suspended app no time, and a pass caught reading the shared replica is the `0xdead10cc` kill.
 *   [leftForeground] cancels the waiting batch and the running one (which ends at its next photo).
 *   It never asks for continued processing — that is *Index now*'s.
 * - ⚠⚠ **The ONE runner** ([runBatch] is `MediaIndexRunner.runPathsBatch`): capped, single-flight
 *   with *Index now* and the processing wake. A pass already running is not joined and not replaced
 *   (the answer is `null`): the batch is owed and tried once that pass has ended.
 * - ⚠⚠ **Never chained.** A batch that stopped at its cap ([IndexPassResult.batchCapReached]) ends
 *   batching for this foreground: what it left stays queued for the next foreground's batch, and
 *   anything else is PR-6's wake's or *Index now*'s.
 * - ⚠⚠ **Consent and model before the look** ([mayRun], `MediaIndexer.anyHalfMayRun`): with no half
 *   that may run, the log is not read and nothing is queued — the batch is still asked, so a refusal
 *   deletes what a withdrawal owes. The runner checks again before reading anything; the bar is the
 *   platform's (`ForegroundIndexingConditions.allowArrivalIndexing`).
 * - ⚠⚠ **A background window over the whole batch** ([window]): the look is a synchronous store read
 *   a cancellation cannot interrupt, so a leave mid-look must not let iOS suspend Photos with the
 *   shared replica's lock held (`0xdead10cc`). The window opens when the batch starts (in front) and
 *   ends when the batch's job does — after the look, or after a leave's cancellation took effect.
 *
 * ⚠ Every event goes through one queue, handled in order on [scope]: the scene hooks are called on
 * the main thread, the watch on its own lane, and the batch on the indexing scope.
 *
 * @param inForeground whether Photos is the app in front now. ⚠ Read when a batch would start.
 * @param look reads what arrived into the queue and answers whether anything is queued. ⚠ Called off
 *   the main thread, once per batch, after `prepare`.
 * @param runBatch the capped batch over the queue, which it settles; `null` when a pass is already
 *   running or the runner is retired. ⚠ Must cancel the batch when its caller is cancelled.
 * @param runnerStanding the runner's standing, to try an owed batch again once a busy pass ends. ⚠ A
 *   provider: the runner's construction opens the stores.
 * @param mayRun whether any half may run (consent, model, pause). ⚠ Asked before [look], off the main thread.
 * @param window the time a batch caught by a leave is given to end (iOS: `beginBackgroundTask`).
 * @param notAsked what a batch with no half that may run does instead of [look] — on iOS it marks where
 *   the log ends, so the first look after consent starts there and not before. ⚠ Reads no path.
 */
class ForegroundArrivals(
    private val scope: CoroutineScope,
    private val inForeground: () -> Boolean,
    private val look: suspend () -> Boolean,
    private val runBatch: suspend () -> PathsBatch?,
    private val runnerStanding: () -> StateFlow<PassStanding>,
    private val debounceMs: Long = ARRIVALS_DEBOUNCE_MS,
    private val mayRun: suspend () -> Boolean = { true },
    private val window: BackgroundWindow = BackgroundWindow.None,
    private val notAsked: suspend () -> Unit = {},
) {
    private sealed interface Event {
        data object Arrived : Event
        data object Entered : Event
        data object Left : Event
        data class Followed(val vouches: () -> Boolean, val prepare: suspend () -> Unit) : Event
        data class Fire(val timer: Long) : Event
        data class Ended(val batch: Long, val result: Ending) : Event
        data object RunnerIdle : Event
    }

    /** How one batch ended, as this class reads it. */
    private sealed interface Ending {
        data class Ran(val result: IndexPassResult) : Ending
        data object Busy : Ending
        data object Failed : Ending

        /** Nothing had arrived: no pass ran. */
        data object Nothing : Ending
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    // ⚠ All of the below is touched only by [handle], one event at a time.
    private var following = false
    private var vouches: () -> Boolean = { false }
    private var prepare: suspend () -> Unit = {}

    /** ⚠ `true` from the start: a fresh process does not know what arrived while it was not running. */
    private var owed = true

    /** A batch stopped at its cap in this foreground: no other batch until the next one. */
    private var capped = false
    private var timer: Job? = null
    private var timerSerial = 0L
    private var batch: Job? = null
    private var batchSerial = 0L
    private var waitingForIdle: Job? = null

    /** A busy answer from an idle runner was retried once already: a second is a retired runner, not a race. */
    private var retriedIdleBusy = false

    /** Batches started, for a test and the log line. */
    var batchesStarted: Int = 0
        private set

    /** Starts handling events. Once. ⚠ Nothing is started before [follow]. */
    fun start() {
        scope.launch { for (event in events) handle(event) }
    }

    /**
     * The library is installed and watched: [changes] emits whenever the library may have changed
     * (⚠ its current value is not a change), [vouches] says whether the watch is watching, and
     * [prepare] is awaited before a batch asks anything (on iOS the launch's store check: until it
     * ends no backend serves). Once per graph.
     */
    fun follow(changes: Flow<Unit>, vouches: () -> Boolean, prepare: suspend () -> Unit = {}) {
        events.trySend(Event.Followed(vouches, prepare))
        scope.launch { changes.collect { events.trySend(Event.Arrived) } }
    }

    /** The scene became active. ⚠ Returns at once. */
    fun enteredForeground() {
        events.trySend(Event.Entered)
    }

    /** The scene left. ⚠ Returns at once; the running batch is cancelled at its next photo. */
    fun leftForeground() {
        events.trySend(Event.Left)
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Followed -> {
                following = true
                vouches = event.vouches
                prepare = event.prepare
                armIfFront()
            }
            Event.Arrived -> {
                owed = true
                armIfFront()
            }
            Event.Entered -> {
                // ⚠ A new foreground: a batch capped in the last one does not hold this one back.
                capped = false
                // ⚠⚠ A watch that does not vouch saw nothing: anything may have arrived.
                if (following && !vouches()) owed = true
                armIfFront()
            }
            Event.Left -> {
                timer?.cancel()
                timer = null
                waitingForIdle?.cancel()
                waitingForIdle = null
                batch?.let { running ->
                    // ⚠ Stopped before it finished: what it did not reach is still owed.
                    running.cancel(CancellationException("Photos left the foreground"))
                    batch = null
                    owed = true
                }
            }
            is Event.Fire -> {
                if (event.timer != timerSerial) return
                timer = null
                fire()
            }
            is Event.Ended -> {
                if (event.batch != batchSerial || batch == null) return
                batch = null
                ended(event.result)
            }
            Event.RunnerIdle -> {
                waitingForIdle = null
                armIfFront()
            }
        }
    }

    /** (Re)starts the debounce, when something is owed and Photos is in front. */
    private fun armIfFront() {
        if (!following || !owed || capped || !inForeground()) return
        timer?.cancel()
        val serial = ++timerSerial
        timer = scope.launch {
            delay(debounceMs)
            events.trySend(Event.Fire(serial))
        }
    }

    private fun fire() {
        // ⚠⚠ Read again now: a leave queued behind this event has not been handled yet.
        if (!owed || capped || batch != null || !inForeground()) return
        // ⚠ Taken at the start: a change during the batch owes the next one.
        owed = false
        val serial = ++batchSerial
        batchesStarted++
        val waitFor: suspend () -> Unit = prepare
        // ⚠⚠ Opened here, in front, and ended with the job however it ends (ATOMIC: a scope cancelled
        // before the job starts still runs the `finally`).
        val held: Any? = window.begin(WINDOW_NAME)
        batch = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                val ending: Ending = try {
                    waitFor()
                    // ⚠⚠ No half may run: the log is not read, nothing queued — the batch still runs
                    // the runner's refusal, which deletes what a withdrawal owes.
                    if (!mayRun()) {
                        notAsked()
                        runBatch()?.let { Ending.Ran(it.result) } ?: Ending.Busy
                    } else {
                        // ⚠ The look queues what arrived before the pass starts: a batch a leave cancels
                        // loses none of it, and the queue keeps what the pass did not reach.
                        val queued: Boolean = look()
                        // ⚠ A leave during the look (which cannot be interrupted) stops here, before the pass.
                        currentCoroutineContext().ensureActive()
                        if (!queued) Ending.Nothing else runBatch()?.let { Ending.Ran(it.result) } ?: Ending.Busy
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    println("ForegroundArrivals -> the batch failed: $failure")
                    Ending.Failed
                }
                events.trySend(Event.Ended(serial, ending))
            } finally {
                window.end(held)
            }
        }
    }

    private fun ended(ending: Ending) {
        if (ending != Ending.Busy) retriedIdleBusy = false
        when (ending) {
            Ending.Nothing -> Unit
            is Ending.Ran -> {
                val result: IndexPassResult = ending.result
                println("ForegroundArrivals -> batch ${summaryOf(result)}")
                when {
                    // ⚠⚠ Never chained: the rest is the wake's or *Index now*'s.
                    result.batchCapReached -> capped = true
                    // ⚠ The bar said no (heat, Low Power Mode, the battery, the foreground): still
                    // owed, to the next change or the next foreground — never a retry loop here.
                    result.deferred || result.paused -> owed = true
                    // Arrivals during the batch: one more, debounced. ⚠ What it left queued (a
                    // photograph not here yet) waits for the next change, never a loop.
                    else -> armIfFront()
                }
            }
            Ending.Busy -> {
                owed = true
                // ⚠ Once the pass that holds the runner ends — never a poll. A retired runner is
                // idle and answers `null` too: that waits for the next change, not a loop.
                if (runnerStanding().value is PassStanding.Running) {
                    if (waitingForIdle == null) {
                        waitingForIdle = scope.launch {
                            runnerStanding().first { it is PassStanding.Idle }
                            events.trySend(Event.RunnerIdle)
                        }
                    }
                } else if (!retriedIdleBusy) {
                    // ⚠⚠ The busy pass ended before this was handled: nothing will say Idle again, so
                    // the owed batch is tried once more now. ⚠ Once: a retired runner answers `null`
                    // from Idle for ever, and a second busy-from-Idle waits for the next change.
                    retriedIdleBusy = true
                    armIfFront()
                }
            }
            // ⚠⚠ A throw (the store check, the look, the batch) is still owed — or a vouching watch
            // with no further change would never ask again — but NOT armed now: the next change or
            // foreground retries it, never a loop.
            Ending.Failed -> owed = true
        }
    }

    private fun summaryOf(result: IndexPassResult): String =
        "visited=${result.imagesVisited} embedded=${result.embedded} faces=${result.faceRecordsWritten} " +
            "capped=${result.batchCapReached} deferred=${result.deferred} paused=${result.paused}"
}

/**
 * How long after the last change a batch starts (`ios-on-device-inference.md` §17). ⚠ A placeholder:
 * long enough that a sync's burst of rows is one batch, short enough to read as "moments after Photos
 * opens". Android's job waits two minutes because it runs with nobody watching.
 */
const val ARRIVALS_DEBOUNCE_MS: Long = 3_000L

/** The background window a new-arrivals batch holds while it runs. */
private const val WINDOW_NAME: String = "app.tenshu.photos.arrivals.batch"
