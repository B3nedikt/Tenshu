package org.tenshu.inference.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.inference.InferenceCapability

/**
 * **One index pass at a time on this device**, however it was asked for (§34).
 *
 * ⚠⚠ **Single-flight, and that is a fix as much as a feature.** Both the scheduled pass and the
 * manual one dispatch the same work, and the schedule already fires from **two** drivers (the
 * in-process `ScheduleDrainLoop` and, on Android, WorkManager waking a closed Control — see
 * `MediaIndexSchedule`). *Index now* adds a third entry point, one the user can press while either
 * is working. Two live passes over one library would double the battery cost, write the same rows
 * twice and leave the progress bar reporting whichever tick landed last.
 *
 * ⚠ The scheduled caller **waits** ([runToCompletion]) and the manual one does **not** ([start]).
 * That asymmetry is the whole design: an OS-woken job holds its wakelock until the op returns, so
 * returning early would let Android stop the work mid-library; an IPC call from a screen cannot be
 * held open for the hours a first index takes.
 *
 * ⚠⚠ [liveness] is what keeps Control alive for a manual pass. The scheduled one is held up by the
 * job that woke it; a pass started from a screen has nothing holding it once the user leaves, and
 * Android reaps a proxy with no clients bound — the same source the model download raises.
 */
class MediaIndexRunner(
    private val indexer: MediaIndexer,
    private val source: MediaSource,
    /** ⚠ Process-lifetime, never a request scope: a manual pass outlives the call that started it. */
    private val scope: CoroutineScope,
    private val liveness: BackgroundLiveness? = null,
    /**
     * Where a running pass is published.
     *
     * ⚠⚠ **The runner publishes the pass, not [MediaIndexer], and that ordering is the whole point.**
     * `IndexProgress` is a *bounded* stream: an empty holder means "no pass is running", so it sends
     * one idle tick and ends. The screen subscribes the moment the op returns — while the library
     * walk is still running, long before the indexer reaches its own `start(total)` — so a holder
     * published by the indexer is published too late, and every subscriber is told nothing is
     * happening for the hours the pass then takes.
     *
     * ⚠ It must be the SAME instance handed to [indexer]: the runner publishes the pass, the indexer
     * ticks it, and two holders would give a bar that never moves beside a pass that is working.
     */
    private val progress: IndexProgressHolder? = null,
    private val capability: InferenceCapability = InferenceCapability.CONTENT_TAGGING,
    /**
     * Told what every completed pass did — how a screen learns "indexed N of M" after
     * the pass, when [progress] has already gone quiet.
     *
     * ⚠ A plain callback, not a type from here: the screen that shows it lives on the far side of
     * `control-shared-proxy`, which composes this module privately.
     */
    private val onPassFinished: (IndexPassResult) -> Unit = {},
    /**
     * Asked at the start of every pass, inside it; `false` ends the pass before the library walk
     * with a deferred result. Where the owner of a realm-stamped store checks the stamp
     * ([InferenceStoreCustody.ensureCurrentRealm]).
     *
     * ⚠ Inside the pass, so it must never cancel the running pass: that joins it.
     */
    private val beforePass: suspend () -> Boolean = { true },
    /**
     * What a pass holds for its whole length, around everything it does (`ios-on-device-inference.md`
     * §14.19). [PassExclusion.None] everywhere but iOS, where Photos' graph binds the process's:
     * a graph that replaces another in one process has a runner of its own, and two runners are
     * two single-flights, which is none, while the old pass unwinds.
     */
    private val exclusion: PassExclusion = PassExclusion.None,
) {

    /**
     * The one pass, and **whether a person asked for it**.
     *
     * ⚠⚠ [manual] is load-bearing because the two kinds are held to different bars
     * (`IndexingConditions`). "A pass is in flight" therefore does not mean "the thing the user
     * asked for is happening": a scheduled pass runs only while the device is idle and charging, so
     * a press that merely joined one would report success over work that stops a moment later.
     */
    private class Pass(
        val deferred: Deferred<IndexPassResult>,
        val manual: Boolean,
        /** ⚠ [PassBar.ARRIVALS] is automatic ([manual] `false`) and gives way to the other two. */
        val bar: PassBar = if (manual) PassBar.MANUAL else PassBar.SCHEDULED,
    )

    private val lock = Mutex()

    /**
     * ⚠⚠ Every pass of this runner that has not ended yet, **whoever cancelled it**: a pass leaves
     * this set only when its coroutine completes, never when [cancelRunningPass] claims it from
     * [inFlight]. (`ios-shared-store-sqlite.md` B.16 (249).)
     */
    private val unended = MutableStateFlow<Set<Deferred<IndexPassResult>>>(emptySet())
    private var inFlight: Pass? = null

    /** (B.16 (252)) Latched by [stopAndAwaitEnded] under [lock]: no pass starts after it began. */
    private var retired = false

    /** Which pass [launchPass] last started. Under [lock]. */
    private var serial: Long = 0

    private val standingNow = MutableStateFlow<PassStanding>(PassStanding.Idle(null))

    /**
     * Whether a pass runs, and how the last one ended, **each with the pass it is about**
     * (§14.19). ⚠ One value: a screen or a driver never joins a *running* from one source with an
     * *ended* from another. Moves to [PassStanding.Running] in the same step that publishes the
     * pass into [progress], and back to [PassStanding.Idle] only after [progress] is cleared.
     */
    val standing: StateFlow<PassStanding> = standingNow.asStateFlow()

    private val faceChangesNow = MutableStateFlow(0L)

    /**
     * Moves once for every pass that changed the face template store — ⚠⚠ however it ended:
     * finished, refused (a withdrawal's clear), stopped or thrown, judged by
     * [MediaIndexer.faceStoreWrites] across the pass, in the pass's own `finally`, never by its
     * report or by [standing]'s compare-and-set (which a pass superseded mid-unwind loses).
     * A pass that wrote nothing does not move it, so an iOS backgrounding that stops a pass
     * over a current library is no walk.
     *
     * ⚠ Errs toward moving: with two passes of this runner unwinding at once (a cancel and a
     * racing start), the later one may also count the earlier's last writes.
     */
    val faceChanges: StateFlow<Long> = faceChangesNow.asStateFlow()

    /**
     * Each recent pass's **terminal outcome**, by its number (`ios-on-device-inference.md` §15):
     * completed once in the same `finally` that clears the bar, on every path — a return, a
     * refusal, a cancellation and a throw. ⚠ The [progress] holder cannot say it (it publishes one
     * `null` for all of them), and [onPassFinished] is not reached from a throw.
     * The last [OUTCOMES_KEPT] passes only: an awaiter asks about a pass it just started.
     */
    private val outcomes = MutableStateFlow<List<Pair<Long, CompletableDeferred<PassOutcome>>>>(emptyList())

    /**
     * How pass [pass] of this runner ended, awaitable while it runs; `null` for a pass this runner
     * never started, or one too old to be kept. ⚠ Completes after the bar is cleared and
     * [standing] is `Idle`, so an awaiter that wakes reads both as ended.
     */
    fun outcomeOf(pass: Long): Deferred<PassOutcome>? = outcomes.value.lastOrNull { it.first == pass }?.second

    /** Whether a pass is running right now — what a manual start reports as "already indexing". */
    suspend fun isRunning(): Boolean = lock.withLock { inFlight?.deferred?.isActive == true }

    /**
     * Starts a pass if none is running and returns **at once**.
     *
     * @return `false` when a pass the caller would be satisfied by was already running — not a
     *   failure: they asked for indexing to be happening, and it is.
     */
    suspend fun start(executionContext: ExecutionContext, manual: Boolean = true): Boolean {
        lock.withLock {
            if (retired) return false
            val running = inFlight?.takeIf { it.deferred.isActive }
            if (running != null && (running.manual || !manual)) return false
            // ⚠⚠ A press REPLACES a scheduled pass rather than joining it, because the two are held
            // to different bars: the scheduled one stands down the moment the device stops being
            // idle — which is the moment someone picks it up to press this — and a press that joined
            // it would report "started" over work about to stop. ⚠ Cancel AND join: the outgoing
            // pass clears [progress] as it unwinds, so letting it overlap the new one would wipe the
            // bar the press just published.
            running?.deferred?.let { outgoing ->
                outgoing.cancel()
                outgoing.join()
            }
            inFlight = Pass(launchPass(executionContext, manual), manual)
            return true
        }
    }

    /**
     * A manual *Index now* pass, run to its end by its caller — Android's long-running worker
     * (`control-background-work-off-fgs.md` §5.6.1).
     *
     * Starts a pass the way [start] does — a running scheduled pass is REPLACED, a running manual one
     * is joined — and then waits for it.
     * ⚠⚠ The caller owns the pass: if it is cancelled (the notification's Stop, the OS stopping the
     * worker, the 6-hour type limit) the pass is cancelled too, and ends cleanly at its next photo.
     * The stamps keep what it did, so the next idle+charging pass continues from there.
     */
    suspend fun runManualToCompletion(executionContext: ExecutionContext): IndexPassResult {
        val pass = lock.withLock {
            val running = inFlight?.takeIf { it.deferred.isActive }
            if (running != null && running.manual) {
                running
            } else {
                running?.deferred?.let { outgoing ->
                    outgoing.cancel()
                    outgoing.join()
                }
                Pass(launchPass(executionContext, manual = true), manual = true).also { inFlight = it }
            }
        }
        return try {
            pass.deferred.await()
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) pass.deferred.cancel()
            throw cancellation
        }
    }

    /**
     * Runs a pass and waits for it, joining one already in flight rather than starting a second.
     *
     * ⚠ The scheduled path. Its result is the pass's own report — the counts the log line prints.
     */
    suspend fun runToCompletion(executionContext: ExecutionContext, manual: Boolean = false): IndexPassResult {
        val pass = lock.withLock {
            if (retired) return IndexPassResult(deferred = true)
            // ⚠⚠ A new-arrivals batch is not joined, it is REPLACED (IN-D6a): joining would hand the
            // caller the batch's capped report as if the library were done. Only once this pass's
            // own bar holds — a batch is never stopped for a pass that would not run.
            val running = inFlight?.takeIf { it.deferred.isActive }
            if (running?.bar == PassBar.ARRIVALS) {
                if (!indexer.allowed(manual)) {
                    return IndexPassResult(deferred = true, facesCleared = withdrawalDeleted(executionContext))
                }
                running.deferred.cancel()
                running.deferred.join()
            }
            running?.takeIf { it.bar != PassBar.ARRIVALS }
                ?: run {
                    // ⚠⚠ The bar is asked BEFORE the slot is taken, not inside the pass. A pass that
                    // cannot run must not occupy the single-flight slot — nor walk the whole realm
                    // tree first, which is what every Control startup did before deferring, since
                    // `ScheduleDrainLoop` dispatches without enforcing the charging/idle constraint.
                    // ⚠ And still deletes what a consent withdrawal owes, as the pass's own refusals do.
                    if (!indexer.allowed(manual)) {
                        return IndexPassResult(deferred = true, facesCleared = withdrawalDeleted(executionContext))
                    }
                    Pass(launchPass(executionContext, manual), manual).also { inFlight = it }
                }
        }
        return try {
            pass.deferred.await()
        } catch (cancellation: CancellationException) {
            // ⚠⚠ Two very different cancellations arrive here. If THIS caller is going away — an
            // OS-woken job told to stop — the work it woke for stops with it. If the caller is alive,
            // the pass was superseded by a press, which is not this caller's failure to report: the
            // work is happening, under someone else's pass.
            if (!currentCoroutineContext().isActive) {
                if (!pass.manual) pass.deferred.cancel()
                throw cancellation
            }
            IndexPassResult(deferred = true)
        }
    }

    /**
     * A batch over **named paths** (IN-D6a's arrivals; general — any caller holding a queue of paths,
     * iOS's foreground batch included), run to its end by its caller.
     *
     * ⚠⚠ **Never a walk.** It reads the photographs at [paths] through [MediaSource.mediaAt] — point
     * reads — and nothing else of the library, and it reconciles nothing (`wholeLibrary = null`): an
     * absence from a list of arrivals is no evidence about the rest of the library. Its bar counts
     * [paths], not the library.
     *
     * - `null` when **any** pass is already running, or the runner is retired: single-flight, and
     *   that pass is the one indexing. ⚠ Never joined: a short job waiting on an hours-long pass would
     *   be stopped at its 10-minute limit, and its stop would end that pass.
     * - Nothing read, every path kept, when no half may run (no model, no consent, paused —
     *   [MediaIndexer.anyHalfMayRun]), or deferred when [bar] says no. Both asked BEFORE the slot is
     *   taken. ⚠⚠ Both still delete what a consent withdrawal owes, as the pass's own refusals do.
     * - Otherwise one pass over those photographs, stopping after [cap] whose bytes were read
     *   ([IndexPassResult.batchCapReached]). ⚠⚠ Never chained: what is not reached stays the caller's.
     *
     * Automatic, like a scheduled pass: its liveness promotes nothing, and a press or the scheduled
     * pass REPLACES it. ⚠ A cancelled caller cancels its batch; a cancelled batch reports nothing.
     */
    suspend fun runPathsBatch(
        executionContext: ExecutionContext,
        paths: Collection<String>,
        cap: Int,
        bar: PassBar = PassBar.ARRIVALS,
    ): PathsBatch? {
        require(cap > 0) { "a batch of $cap" }
        val outcomes = mutableMapOf<String, PathOutcome>()
        val pass = lock.withLock {
            if (retired) return null
            if (inFlight?.deferred?.isActive == true) return null
            // ⚠ Not a deferral (§32.9): no model, no consent or a pause does not pass on its own.
            if (!indexer.anyHalfMayRun(executionContext)) {
                return PathsBatch(
                    IndexPassResult(facesCleared = withdrawalDeleted(executionContext)),
                    emptyMap(),
                    everyHalfWithdrawn = indexer.everyHalfWithdrawn(executionContext),
                )
            }
            if (!indexer.allowed(bar)) {
                return PathsBatch(
                    IndexPassResult(deferred = true, facesCleared = withdrawalDeleted(executionContext)),
                    emptyMap(),
                )
            }
            if (paths.isEmpty()) return PathsBatch(IndexPassResult(), emptyMap())
            val pathsPass = PathsPass(paths, cap) { path, outcome -> outcomes[path] = outcome }
            Pass(launchPass(executionContext, manual = false, bar = bar, pathsPass = pathsPass), manual = false, bar = bar)
                .also { inFlight = it }
        }
        val result = try {
            pass.deferred.await()
        } catch (cancellation: CancellationException) {
            // ⚠ As `runToCompletion`: the caller going away ends its batch; a batch superseded by a
            // press or the scheduled pass is not this caller's failure — that pass is indexing.
            if (!currentCoroutineContext().isActive) {
                pass.deferred.cancel()
                throw cancellation
            }
            return null
        }
        return PathsBatch(result, outcomes.toMap())
    }

    /**
     * [MediaIndexer.deleteTemplatesOnWithdrawal] for a refusal made before any pass: what a
     * withdrawal owes is deleted however the pass was refused. Counts toward [faceChanges].
     */
    private suspend fun withdrawalDeleted(executionContext: ExecutionContext): Boolean =
        indexer.deleteTemplatesOnWithdrawal(executionContext).also { cleared ->
            if (cleared) faceChangesNow.update { it + 1 }
        }

    /**
     * Stops the running pass and waits for it to let go.
     *
     * ⚠⚠ **What a local-store wipe must call before clearing derived state.** A pass suspended in the
     * model holds a list of paths from the realm being wiped; left running it resumes after the
     * clear, finds an unstamped store, writes a fresh header and repopulates it with the *old*
     * realm's vectors — the cross-realm answer the clear exists to prevent. Cancellation lands at the
     * pass's next suspension point and every store write goes through a `Mutex`, so a cancelled pass
     * cannot complete one afterwards.
     */
    suspend fun cancelRunningPass() {
        val pass = lock.withLock { inFlight.also { inFlight = null } } ?: return
        pass.deferred.cancel()
        // ⚠ `join`, never `await`: a cancelled `Deferred` always carries an exception, and it is not
        // this caller's to re-throw — the wipe must not fail because a pass was stopped on purpose.
        pass.deferred.join()
    }

    /**
     * [cancelRunningPass], but only for a **scheduled** pass (`ios-on-device-inference.md` §16): a
     * pass a person started is left running. ⚠⚠ What a processing wake's expiry stops: the wake
     * may have joined a press's pass (iOS 26's continued *Index now*, under its own task), and that
     * pass is not the wake's to end. The check and the claim are one step under the lock.
     *
     * ⚠⚠ Nor a new-arrivals batch ([PassBar.ARRIVALS]): it is not the wake's either, it is short, and
     * whatever started it owns its end — on iOS the foreground batch stops when Photos leaves the
     * foreground, on its own bar.
     */
    suspend fun cancelRunningScheduledPass() {
        val pass = lock.withLock {
            inFlight?.takeIf { !it.manual && it.bar != PassBar.ARRIVALS }.also { if (it != null) inFlight = null }
        } ?: return
        pass.deferred.cancel()
        pass.deferred.join()
    }

    /**
     * Stops the running pass and waits until **every** pass of this runner has ended (B.16).
     * Unlike [cancelRunningPass] alone, a caller that loses the race to another canceller (on iOS,
     * the graph's own unload, `AiReadiness.stopServing`, which claims [inFlight] first) still waits
     * for that pass's end, not just for its own claim, which found nothing. What a graph's teardown
     * waits on before another graph may open the same inference files.
     */
    suspend fun stopAndAwaitEnded() {
        // ⚠ Latched first, under the lock: no `start` or `runToCompletion`
        // from here on launches a pass this stop would have to chase.
        lock.withLock { retired = true }
        cancelRunningPass()
        afterCancelForTests?.invoke()
        // Every pass not ended, cancelled (not just the one in flight) and joined.
        val snapshot = unended.value.toList()
        snapshot.forEach { it.cancel() }
        snapshot.forEach { it.join() }
    }

    /** Test seam (252): run between the cancel and the snapshot. Null in production. */
    internal var afterCancelForTests: (suspend () -> Unit)? = null

    /** ⚠ Called under [lock]: the check and the launch must not interleave, or two passes start. */
    // ⚠ `CoroutineStart.ATOMIC` is experimental: opted in here, where it is load-bearing.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launchPass(
        executionContext: ExecutionContext,
        manual: Boolean,
        bar: PassBar = if (manual) PassBar.MANUAL else PassBar.SCHEDULED,
        pathsPass: PathsPass? = null,
    ): Deferred<IndexPassResult> {
        val pass: Long = ++serial
        // ⚠ Registered before the coroutine exists, so an awaiter that reads the standing as
        // `Running(pass)` always finds the outcome to await.
        val outcome = CompletableDeferred<PassOutcome>()
        outcomes.update { kept -> (kept + (pass to outcome)).takeLast(OUTCOMES_KEPT) }
        // ⚠⚠ BEFORE the coroutine, so the pass is observable the instant `start` returns — see
        // [progress]. `total = 0` renders as "no message" on every surface, so this publishes no
        // wrong number; the indexer replaces it with the real total once the walk is done.
        progress?.start(0)
        standingNow.value = PassStanding.Running(pass, manual)
        // ⚠⚠ ATOMIC: the body runs even when the pass is cancelled before it was dispatched, so its
        // `finally` clears the bar and publishes the ending on every path. With the default start,
        // a cancel before dispatch would leave `Running` and the bar standing for good. ⚠⚠ Only a
        // MANUAL pass holds the user-started key, which is what Android's foreground service counts
        // (control-background-work-off-fgs.md §5.2). A scheduled pass is automatic: it raises its
        // own key, which keeps the liveness bookkeeping and promotes nothing. Before this split, a
        // scheduled pass dispatched by the in-process drain loop with a client open promoted the
        // dataSync service for an hours-long automatic pass.
        val livenessKey = if (manual) BackgroundLiveness.MEDIA_INDEX_PASS else BackgroundLiveness.SCHEDULED_MEDIA_INDEX_PASS
        // ⚠ Read before the pass exists: every face write of this pass lands after it.
        val faceWritesBefore = indexer.faceStoreWrites.value
        val launched = scope.async(start = CoroutineStart.ATOMIC) {

            // ⚠ Set by every ending that returned or threw; a cancellation leaves it `null`, which is
            // [PassEnding.Stopped].
            var ending: PassEnding? = null
            try {
                // ⚠ ATOMIC runs the body of a pass cancelled before dispatch: it stops here, and
                // nothing of it runs but the `finally`.
                ensureActive()
                // ⚠ After `ensureActive`, so a pass cancelled before dispatch never raises the
                // liveness it then lowers (0 → 1 → 0). Lowered in `finally`, which is
                // idempotent for a source never raised.
                liveness?.setActive(true, livenessKey)
                exclusion.around {
                    // ⚠ Published again once the slot is this pass's: the
                    // pass it waited for unwound while holding it, and its indexer's own `finally`
                    // cleared the bar this pass published at launch. Without the slot (Android,
                    // desktop) it is the same value set twice, and nothing moves.
                    progress?.start(0)
                    if (!beforePass()) {
                        // ⚠⚠ A refused pass indexes nothing, and still deletes what a withdrawal owes.
                        val cleared = indexer.deleteTemplatesOnWithdrawal(executionContext)
                        return@around IndexPassResult(deferred = true, facesCleared = cleared)
                    }
                    // ⚠⚠ The bar once more, before the walk (§14.19): a pass that begins after the
                    // device stopped allowing it — on iOS, after Photos left the foreground —
                    // reads nothing of the library. Without it the walk and the reconcile ran
                    // first, a whole tree read of the shared replica in the background.
                    if (!indexer.allowed(bar)) {
                        // ⚠ And still deletes what a withdrawal owes, as the gate's refusal does.
                        val cleared = indexer.deleteTemplatesOnWithdrawal(executionContext)
                        return@around IndexPassResult(deferred = true, facesCleared = cleared)
                    }
                    // ⚠ `wholeLibrary` only for a walk that RETURNED and that the source vouches for as
                    // complete: a throwing walk never reaches the indexer, and a partial one — a subtree
                    // the file walk could not read — must not be taken for the library.
                    // ⚠⚠ A path batch reads its paths and nothing else: no walk, no reconcile.
                    val walk = if (pathsPass == null) {
                        source.walk(executionContext)
                    } else {
                        val found = source.mediaAt(executionContext, pathsPass.paths)
                        val live = found.mapTo(HashSet()) { it.filePath }
                        // ⚠⚠ Not "gone": a peer's modification row can be applied before its File row,
                        // so "no row yet" is retried a bounded number of times rather than dropped.
                        pathsPass.paths.filterNot { it in live }.forEach { pathsPass.onPath(it, PathOutcome.UNAVAILABLE) }
                        MediaWalk.partial(found)
                    }
                    indexer.index(
                        executionContext,
                        walk.media,
                        capability,
                        manual = manual,
                        wholeLibrary = walk.wholeLibrary,
                        bar = bar,
                        batchCap = pathsPass?.cap,
                        onPath = pathsPass?.onPath,
                    )
                        // ⚠⚠ Only a walk speaks for the library (the Models screen's coverage).
                        .copy(libraryWalk = pathsPass == null)
                        // ⚠ Here, where EVERY pass runs — the manual *Index now* path never reached the
                        // op's own line, so the pass a user actually watched was the one never reported.
                        .also { println(it.summaryLine()) }
                        .also(onPassFinished)
                }.also { result -> ending = PassEnding.Finished(pass, result) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                ending = PassEnding.Failed(pass, failure.toString())
                // ⚠⚠ Named line rather than silence. This `async` is never awaited on the manual
                // path, and an un-awaited `async` under a `SupervisorJob` reports its exception
                // NOWHERE — no handler, no executor catch-all, no log line. Before the runner existed
                // the pass ran inline and a throw reached `LocalTaskExecutor`; keeping that
                // visibility is the difference between a failed index and a feature that silently
                // does nothing.
                println("MediaIndexPass -> pass failed: $failure")
                throw failure
            } finally {
                // ⚠⚠ Both in `finally`, and the bar especially: `MediaIndexer` clears it on every
                // ordinary exit, but a throw unwinds past that, and a holder left non-null is a bar
                // that says "indexing" for the life of the process — with *Index now* disabled behind
                // it, because a pass appears to be running.
                // ⚠⚠ Only while the standing is still this pass's, as the standing itself is:
                // [cancelRunningPass] lets go of the lock before its join,
                // so a `start` in between launches the next pass while this one unwinds, and an
                // unguarded clear would wipe the bar that pass had just published. Every other path
                // (Android's and desktop's `start` joins the outgoing pass under the lock) still
                // finds its own `Running` here, so it clears.
                if (standingNow.value == PassStanding.Running(pass, manual)) progress?.finish()
                // ⚠ After the bar is cleared, and only over this pass's own standing: a later
                // pass already running is not ended by this one's unwinding.
                val ended: PassEnding = ending ?: PassEnding.Stopped(pass)
                standingNow.compareAndSet(PassStanding.Running(pass, manual), PassStanding.Idle(ended))
                // ⚠⚠ The terminal outcome, on every path (§15): after the bar and the standing, so
                // an awaiter woken by it reads both as ended. Unconditional, unlike the two above:
                // this value is this pass's alone, whatever pass replaced it.
                // ⚠⚠ Unconditional on the standing, like the outcome: whatever pass replaced
                // this one, what this one wrote changed People.
                if (indexer.faceStoreWrites.value != faceWritesBefore) faceChangesNow.update { it + 1 }
                outcome.complete(passOutcomeOf(ended))
                liveness?.setActive(false, livenessKey)
            }
        }
        // ⚠ (B.16 (249)) Registered before anyone can claim it, and gone only with its coroutine.
        unended.update { it + launched }
        launched.invokeOnCompletion { unended.update { it - launched } }
        return launched
    }
}

/**
 * How many photographs one new-arrivals batch reads and indexes (IN-D6a,
 * [MediaIndexRunner.runPathsBatch]). ⚠ A starting value, to measure on the floor-class phone:
 * at ~121 ms a vector and a few hundred ms for faces, 50 is well inside a short job's 10 minutes.
 */
const val NEW_ARRIVALS_BATCH_CAP: Int = 50

/** A path batch's paths, its cap and where each path's outcome goes. */
internal class PathsPass(val paths: Collection<String>, val cap: Int, val onPath: (String, PathOutcome) -> Unit)

/**
 * What [MediaIndexRunner.runPathsBatch] did: the pass's report, and what became of each path it
 * reached. ⚠ A path missing from [outcomes] was not reached — the caller keeps it.
 */
class PathsBatch(
    val result: IndexPassResult,
    val outcomes: Map<String, PathOutcome>,
    /** Refused because the user switched every half off — the paths themselves are owed deletion. */
    val everyHalfWithdrawn: Boolean = false,
)

/** How many passes' outcomes a runner keeps ([MediaIndexRunner.outcomeOf]). */
private const val OUTCOMES_KEPT = 8

/**
 * A pass's **terminal outcome** ([MediaIndexRunner.outcomeOf], `ios-on-device-inference.md` §15):
 * what a caller that only waits for the pass needs — iOS's continued-processing task completes
 * with success on [COMPLETED] alone.
 */
enum class PassOutcome {
    /** It returned its report having walked the library: done for now. */
    COMPLETED,

    /**
     * It returned without doing what was asked: refused before the walk (the stores' gate, the
     * bar), stopped by the bar mid-library (heat, Low Power Mode, the foreground), or paused.
     */
    REFUSED,

    /** Cancelled: superseded, stopped on purpose, an expiry, or its scope ended. */
    CANCELLED,

    /** It threw. */
    FAILED,
}

/** [PassEnding] → [PassOutcome]. ⚠ A deferred or paused report is a refusal, not a completion. */
fun passOutcomeOf(ending: PassEnding): PassOutcome = when (ending) {
    is PassEnding.Finished -> if (ending.result.deferred || ending.result.paused) PassOutcome.REFUSED else PassOutcome.COMPLETED
    is PassEnding.Stopped -> PassOutcome.CANCELLED
    is PassEnding.Failed -> PassOutcome.FAILED
}

/**
 * [MediaIndexRunner.standing]: whether a pass runs, and how the last one ended. ⚠ Each carries the
 * pass it is about, numbered per runner.
 */
sealed interface PassStanding {
    data class Running(val pass: Long, val manual: Boolean) : PassStanding

    /** No pass runs. [last] is how the last one ended, or `null` when none has in this runner. */
    data class Idle(val last: PassEnding?) : PassStanding
}

/**
 * One emission per change of [faceChanges] AFTER it was first read — what the iOS Collections tab
 * re-reads People for (`ios-face-grouping.md` §17.6). ⚠ The value at subscribe is a baseline: the
 * reader's own first read postdates it. ⚠⚠ A counter, never the standing: [MediaIndexRunner.standing]
 * is conflated (a pass's ending and the next pass's start, back to back, deliver only the start) and
 * its ending is published only over the pass's own `Running` — so a reader of endings lost passes.
 * A conflated counter loses nothing a reader needs: any later value still differs from the last seen.
 */
fun faceChangingPasses(faceChanges: Flow<Long>): Flow<Unit> = faceChanges.distinctUntilChanged().drop(1).map { }

/** How one pass ended. */
sealed interface PassEnding {
    val pass: Long

    /**
     * The pass returned its report. ⚠ Not *done*: [IndexPassResult.deferred] and
     * [IndexPassResult.paused] are passes that stopped early, and say so.
     */
    data class Finished(override val pass: Long, val result: IndexPassResult) : PassEnding

    /** Cancelled: superseded, stopped on purpose ([MediaIndexRunner.cancelRunningPass]), or its scope ended. */
    data class Stopped(override val pass: Long) : PassEnding

    /** It threw. [cause] is for the log line, never for a screen. */
    data class Failed(override val pass: Long, val cause: String) : PassEnding
}

/**
 * What a pass holds around everything it does ([MediaIndexRunner]'s `exclusion`).
 *
 * ⚠⚠ A wait for the slot is the pass's own, after `start` has returned: no caller waits on it, and
 * it is never bounded, because the holder is a pass already being stopped (a graph's teardown
 * cancels its pass) and ends at its next photo.
 */
interface PassExclusion {
    suspend fun <T> around(pass: suspend () -> T): T

    companion object {
        /** No exclusion beyond the runner's own single flight: Android and desktop. */
        val None: PassExclusion = object : PassExclusion {
            override suspend fun <T> around(pass: suspend () -> T): T = pass()
        }

        /** One pass at a time across every runner that shares this instance: a process's. */
        fun shared(): PassExclusion = object : PassExclusion {
            private val slot = Mutex()
            override suspend fun <T> around(pass: suspend () -> T): T = slot.withLock { pass() }
        }
    }
}
