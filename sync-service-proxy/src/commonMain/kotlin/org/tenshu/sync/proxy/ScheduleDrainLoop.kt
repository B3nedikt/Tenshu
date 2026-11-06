package org.tenshu.sync.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.trait.isHeadlessSafe
import org.tenshu.core.trait.isPunctual
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.ScheduledRunDeferred
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Phase 5 (docs/proposals/core-background-processing.md §3.2(b) / §8): the
 * in-process half of `ScheduleService` — drains the persisted [ScheduleItem]
 * registry while the proxy is alive, firing `PERIODIC` items honouring
 * `minPeriodMs` and `ONE_SHOT` items once after `initialDelayMs`. A
 * per-platform `PlatformScheduler` drains the *same* registry on OS wakes — one
 * store, two drivers (§3.2(b)). Android binds `WorkManagerScheduler`; desktop and
 * iOS bind `NoOpPlatformScheduler`.
 *
 * Deliberately a **new** loop, not a `SyncScheduler` repurpose: the
 * `SyncScheduler → BackgroundJobRegistry` extraction is Phase 1 (separate, and
 * touches a class another track has diverged). This loop only reads/writes the
 * `ScheduleItem` array and dispatches by op id.
 *
 * Cadence gating is **tick-accumulator based** (no wall clock): each tick adds
 * `tickIntervalMs` to a per-key accumulator and fires when it crosses the
 * threshold. This is dependency-free and deterministic under
 * `kotlinx-coroutines-test` virtual time (a monotonic/epoch clock would not
 * advance with `delay`).
 *
 * Constraints (`ConstraintSpec`) and retry/backoff (`RetryPolicy`) are stored
 * but not enforced in-process — best-effort per §5.6; real enforcement is
 * `PlatformScheduler` (Phase 6).
 *
 * ⚠⚠ **An item the [platformScheduler] owns is never dispatched here**
 * (`control-background-work-off-fgs.md` §5.6). On Android that is every item with a constraint
 * this loop cannot check (idle, charging, battery, storage, unmetered): WorkManager enforces them,
 * and its job is what gives the op a process and a network for as long as it runs. Before this, the
 * loop ran an idle+charging maintenance pass at Control's startup with the screen on and no job
 * behind it, so the pass lost the network the moment Control left the foreground. Desktop, iOS and
 * the hub bind [NoOpPlatformScheduler], which owns nothing, so there the loop stays the only driver.
 *
 * Media pipeline P4 (`media-processing-pipeline-sweep.md` F3.1 / F8):
 *
 *  - ⚠⚠ **Each due item runs in its own coroutine**, so one long item (a multi-hour index
 *    pass, a 250-file backfill) no longer stops every other item ticking. A tick only
 *    *starts* runs; it never waits for one.
 *  - ⚠⚠ **Per-key single flight**: an item whose run is still in flight is skipped, and
 *    its accumulator does not grow meanwhile — the period counts from the END of a run,
 *    exactly as it did when the loop blocked on it. One item never overlaps itself.
 *  - **Three outcomes.** A run that returns resets the period; one that throws keeps the
 *    pre-P4 behaviour (periodic: period reset, `attempts` +1; one-shot: `FAILED`); one that
 *    throws [ScheduledRunDeferred] fires again after its `retryAfterMs` instead of a full
 *    period, leaving the stored row untouched (still `PENDING`, `attempts` unchanged).
 *  - ⚠⚠ **A completion writes only the row its run started from** ([ScheduleRunState]). Every row
 *    for a key shares one `DataId`, so without the generation compare a `Schedule` for the key
 *    during its run was overwritten by the completion, and a `CancelScheduled` was undone by it.
 *    A completion also never *creates* a row: it updates the one that is there, in place.
 */
@OptIn(ExperimentalAtomicApi::class)
class ScheduleDrainLoop(
    private val executionContext: ExecutionContext,
    private val coroutineScope: CoroutineScope,
    private val tickIntervalMs: Long = 1000L,
    private val runState: ScheduleRunState = ScheduleRunState.Default,
    /** Whose items this loop must leave alone — see the class KDoc. */
    private val platformScheduler: PlatformScheduler,
) {

    private var job: Job? = null

    /**
     * Parent of every item run, and **the whole of the stop mechanism**. A supervisor so one run
     * cannot cancel its siblings, and a child of [coroutineScope] so the scope's owner still tears
     * everything down.
     *
     * ⚠⚠ **The invariant: a run is a child of the supervisor [stop] cancels, and a coroutine whose
     * job is cancelled before its body starts never runs its body.** So once [stop] has returned no
     * run can begin, whenever it is launched — `Job` itself is the one piece of state both sides go
     * through, and it decides attach-versus-cancel atomically:
     *
     *  - launched **before** the cancel: it is one of the supervisor's children, and the cancel
     *    reaches it before [stop] returns;
     *  - launched **after** the cancel, by a tick that was already mid-pass: it is born cancelled.
     *
     * There is deliberately nothing else. [stop] cannot take the loop's lock (it does not
     * suspend), and any flag or counter a tick *reads* on stop's behalf can be read on the wrong
     * side of the stop — a tick that read a stop count after the bump adopted the new value and
     * launched a run that passed its check. A tick reads nothing here; it only launches into a
     * parent.
     *
     * ⚠ **What "cannot begin" covers.** The line is the run's own body: an op is dispatched on the
     * body's first statement. A run whose body had already begun when [stop] arrived is cancelled,
     * not prevented — see [stop].
     *
     * A reference, because the loop is restartable and a cancelled job cannot be revived: [start]
     * swaps in a fresh supervisor if the current one is cancelled, and the loop it launches keeps
     * the one it was started with. So a tick of a stopped loop can never launch into the
     * supervisor of the loop that replaced it.
     */
    private val runs = AtomicReference<CompletableJob>(newRuns())

    private fun newRuns(): CompletableJob = SupervisorJob(coroutineScope.coroutineContext[Job])

    /**
     * ⚠ Guards every map below. Runs finish on whatever thread their op resumed on, concurrently
     * with the next tick, so the bookkeeping is no longer single-threaded by construction.
     */
    private val lock = Mutex()
    private val accumulatedMs = mutableMapOf<String, Long>()
    private val firstFired = mutableSetOf<String>()

    /**
     * Keys whose run is in flight, with that run — the per-key single flight.
     *
     * ⚠ An atomic, not under [lock], because a key is freed from the run's **completion handler**,
     * which cannot suspend. It has to be the handler and not a `finally` in the run's body: a run
     * launched across a [stop] never runs its body at all, and its key must be freed all the same,
     * or the item is skipped for the rest of the loop's life. Only a tick adds, under [lock].
     */
    private val inFlight = AtomicReference<Map<String, Job>>(emptyMap())

    /**
     * A key's next threshold after a [ScheduledRunDeferred], replacing `initialDelayMs` /
     * `minPeriodMs` once. Cleared by the next run that returns or throws anything else.
     */
    private val retryAfterMs = mutableMapOf<String, Long>()

    fun start() {
        if (job != null) return
        // The supervisor THIS loop launches into, fixed for its lifetime (see [runs]).
        val runs = liveRuns()
        job = coroutineScope.launch {
            while (isActive) {
                try {
                    tick(runs)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    e.printStackTrace()
                }
                delay(tickIntervalMs)
            }
        }
    }

    /** The current supervisor, replaced first if a [stop] cancelled it. */
    private fun liveRuns(): CompletableJob {
        val current = runs.load()
        if (current.isActive) return current
        val fresh = newRuns()
        if (runs.compareAndSet(current, fresh)) return fresh
        fresh.cancel()
        return runs.load()
    }

    /** A due item, with the generation its row was read under. */
    private class Due(val item: ScheduleItem, val key: String, val generation: Long)

    /**
     * One drain pass: decides which items are due and **starts** them, returning their jobs.
     *
     * ⚠ It does not wait for them — that is the P4 fix. `internal` so tests can step it
     * deterministically and join what it started.
     */
    internal suspend fun tick(): List<Job> = tick(runs.load())

    private suspend fun tick(runs: Job): List<Job> {
        val ref = scheduledItemsRef(executionContext)
        val started = mutableListOf<Job>()

        lock.withLock {
            // ⚠⚠ The rows are read HERE, under the lock a completion holds while it writes `DONE`
            // and forgets the key — never before it. A snapshot taken outside could still show a
            // one-shot `PENDING` after its run had finished and freed the key, and fire it twice.
            //
            // ⚠ Generations first, then the rows, then the generations again; a key whose
            // generation moved across the read is left for the next tick. That pairs each row with
            // the generation it was written under without a `Database.transaction`, which on a
            // durable store enqueues a commit barrier — not something a 1 Hz read may do.
            val before = runState.snapshot()
            val items = executionContext.database.getArrayData(ref)?.toList().orEmpty()
            val after = runState.snapshot()

            for (data in items) {
                val item = data as ScheduleItem
                if (item.state.get() != ScheduleItem.STATE_PENDING) continue
                val key = item.dedupKey.get()
                if (key in inFlight.load()) continue
                val generation = before[key] ?: 0L
                if ((after[key] ?: 0L) != generation) continue

                // Defence in depth (§6.4): the schedule op already rejected
                // non-HeadlessSafe ops, but a wire-deserialised / older-build item
                // could carry one. Fail it closed rather than dispatch.
                val schema = executionContext.schemaProvider
                    .getSchema(item.opId.get()) as? OperationSchema
                if (schema == null || !schema.isHeadlessSafe()) {
                    complete(ref, item, key, generation, ScheduleItem.STATE_FAILED)
                    forget(key)
                    continue
                }

                // ⚠⚠ The OS scheduler's item: it runs under that scheduler's job, with the
                // constraints this loop cannot check. Skipped before the accumulator, so nothing
                // here pretends to track its cadence.
                if (platformScheduler.ownsDispatch(item, punctual = schema.isPunctual())) continue

                val spec = item.scheduleSpec
                accumulatedMs[key] = (accumulatedMs[key] ?: 0L) + tickIntervalMs
                val threshold = retryAfterMs[key]
                    ?: if (key !in firstFired) {
                        spec.initialDelayMs?.get() ?: 0L
                    } else {
                        spec.minPeriodMs?.get() ?: tickIntervalMs
                    }
                if (accumulatedMs.getValue(key) < threshold) continue

                val due = Due(item, key, generation)
                // ⚠ Into `runs`, and nothing else decides whether it may run (see [runs]).
                val launched = coroutineScope.launch(runs) { run(ref, due) }
                claim(key, launched)
                started += launched
            }
        }
        return started
    }

    private suspend fun run(
        ref: org.tenshu.core.common.proxy.ArrayDatabaseReference,
        due: Due,
    ) {
        val item = due.item
        val key = due.key
        // Phase 4 of `docs/proposals/core-error-channel.md`: executeAsync now
        // returns `T` directly and throws TenshuException on typed failure.
        // For the drain loop, we just need the outcome (not the value):
        // catch any throwable so a single failed op doesn't break the loop.
        // `null` ⇒ ran (returned or threw); a value ⇒ deferred by that many ms.
        var deferral: Long? = null
        val succeeded = try {
            executionContext.executeAsync<Data>(
                operationId = item.opId.get(),
                caller = null,
                params = null
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ScheduledRunDeferred) {
            deferral = e.retryAfterMs
            false
        } catch (e: Throwable) {
            false
        }

        lock.withLock {
            val periodic = item.scheduleSpec.kind.get() == ScheduleSpec.KIND_PERIODIC

            // ⚠⚠ First, for EVERY outcome: is the row this run started from still the key's
            // row? A deferral writes nothing, but it must ask too — its retry delay belongs
            // to the registration that was deferred, not to whatever replaced it.
            val stillCurrent = if (deferral != null) {
                isCurrent(ref, key, due.generation)
            } else {
                val state = when {
                    periodic -> ScheduleItem.STATE_PENDING
                    succeeded -> ScheduleItem.STATE_DONE
                    else -> ScheduleItem.STATE_FAILED
                }
                try {
                    complete(ref, item, key, due.generation, state, item.attempts.get().toInt() + 1)
                } catch (e: Exception) {
                    // ⚠⚠ Must not escape: this coroutine is a child of a `SupervisorJob` with no
                    // handler, so a store write that throws would reach the thread's uncaught
                    // handler — which on Android kills Control. The write is in place, so the
                    // row is as it was (`PENDING`), and the item simply runs again.
                    e.printStackTrace()
                    true
                }
            }

            // ⚠⚠ Stood down: the key was re-registered or cancelled during the run, or its row
            // is gone. Nothing this run learned applies to what is there now — not its period,
            // not its retry delay, not even its KIND (a key can come back as a one-shot). Drop
            // all of it; a row that is there starts from its own `initialDelayMs`, as new.
            if (!stillCurrent) {
                forget(key)
                return@withLock
            }

            firstFired += key
            accumulatedMs[key] = 0L

            // ⚠ The row is left alone: a deferral is neither a run nor a failure, and a
            // one-shot must stay PENDING to be retried at all.
            if (deferral != null) {
                retryAfterMs[key] = deferral
                return@withLock
            }
            retryAfterMs.remove(key)

            // A one-shot is over.
            if (!periodic) forget(key)
        }
    }

    /** Marks [key] in flight until [run] completes — however it completes, body or no body. */
    private fun claim(key: String, run: Job) {
        while (true) {
            val current = inFlight.load()
            if (inFlight.compareAndSet(current, current + (key to run))) break
        }
        // Registered after the claim, so it cannot fire before there is something to release; on a
        // run that has already completed it fires at once.
        run.invokeOnCompletion {
            while (true) {
                val current = inFlight.load()
                if (current[key] !== run) break
                if (inFlight.compareAndSet(current, current - key)) break
            }
        }
    }

    private fun forget(key: String) {
        accumulatedMs.remove(key)
        firstFired.remove(key)
        retryAfterMs.remove(key)
    }

    /**
     * Whether [key]'s row is still the one a run started from: the key has a row, and nothing has
     * registered or cancelled the key since the tick read [generation] with it.
     *
     * ⚠ Reads only, so no transaction. A registration that lands just after is one that arrived
     * after the run — the same as re-registering an idle key, which keeps the key's timing.
     */
    private fun isCurrent(
        ref: org.tenshu.core.common.proxy.ArrayDatabaseReference,
        key: String,
        generation: Long,
    ): Boolean =
        runState.generationOf(key) == generation && scheduleItemFor(executionContext, ref, key) != null

    /**
     * Writes a run's outcome onto the row it started from, and returns whether it was still that
     * row — `false` means the completion stood down and wrote nothing.
     *
     * ⚠⚠ **Compare and write in one transaction, and stand down on a mismatch.** [generation] is
     * what the tick read with the row. If it has moved, a `Schedule` or a `CancelScheduled` for
     * this key landed during the run; the row now there — or its absence — is theirs, and this
     * completion writes nothing. The next tick fires a re-registered row in its own right.
     *
     * ⚠ It never adds a row: a key with no row is not resurrected, whatever the generation says.
     */
    private fun complete(
        ref: org.tenshu.core.common.proxy.ArrayDatabaseReference,
        old: ScheduleItem,
        key: String,
        generation: Long,
        state: String,
        attempts: Int = old.attempts.get().toInt()
    ): Boolean = executionContext.database.transaction {
        val moved = runState.generationOf(key) != generation
        if (scheduleItemFor(executionContext, ref, key) == null) return@transaction false
        if (moved) return@transaction false
        writeScheduleItem(
            executionContext,
            ref,
            ScheduleItem.create(
                context = executionContext.context,
                dedupKey = old.dedupKey.get(),
                opId = old.opId.get(),
                scheduleSpec = old.scheduleSpec,
                constraintSpec = old.constraintSpec,
                retryPolicy = old.retryPolicy,
                state = state,
                attempts = attempts
            )
        )
        true
    }

    /** Item runs that have not finished. For tests of [stop] / [stopAndAwait]. */
    internal val activeRuns: Int get() = inFlight.load().size

    /**
     * Stops the loop and cancels every item run. Does not wait.
     *
     * ⚠⚠ **Guaranteed when this returns: no run begins.** Not one already launched and waiting for
     * a thread, and not one a tick that is still mid-pass launches afterwards (see [runs]).
     *
     * ⚠⚠ **Not guaranteed: that nothing is still running.** A run whose op was already dispatched
     * is cancelled, not prevented, and what that cancels is the **run**, not the op:
     *
     *  - an op that suspends is started by `ExecutionContext.executeAsync` on the *session's*
     *    scope, and the run only awaits it. The run stops waiting and records nothing — the row
     *    keeps its state and `attempts` — but **the op carries on** until it ends or its session
     *    is cancelled. That was so before P4 too, when the loop awaited the op itself;
     *  - an op that never suspends runs inline on the run's coroutine and cannot be interrupted;
     *    it runs to its end, and its outcome may then still be written to its row. It did run.
     *
     * [stopAndAwait] waits for the runs. It does not wait for an op a cancelled run abandoned.
     *
     * ⚠ A tick that is mid-pass also finishes its pass: it dispatches nothing, but it may still
     * fail-close a row whose op is not `HeadlessSafe`.
     */
    fun stop() {
        job?.cancel()
        job = null
        runs.load().cancel()
    }

    /**
     * [stop], but returns only once the loop's own coroutine and every item run have finished. See
     * `SyncScheduler.stopAndAwait`.
     *
     * ⚠ The loop is joined first, the runs after: only once the loop's coroutine has ended is the
     * set of launched runs final. (A run launched in between never begins — [stop] has already
     * returned — but it still has to be waited for to be *finished*.)
     */
    suspend fun stopAndAwait() {
        val running = job
        stop()
        running?.cancelAndJoin()
        inFlight.load().values.joinAll()
    }
}
