package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.proxy.BackgroundLiveness

/**
 * What one sync-push job request asks the platform for (`control-background-work-off-fgs.md` §5.3).
 */
data class SyncPushRequest(
    /** Run as an expedited job (falls back to an ordinary one when the OS quota is spent). */
    val expedited: Boolean,
    /**
     * Queue it behind a job of the same name that is still running, rather than keep that one.
     * ⚠ Only for the follow-up a finishing job asks for; a fresh backlog never appends, or every
     * rising edge during a backup would queue one more run.
     */
    val appendToRunning: Boolean,
    /** Wait this long before running; 0 = as soon as the constraints allow. */
    val initialDelayMs: Long = 0,
    /**
     * The run for when the content plane's wait ends — under its own name
     * ([SyncPushScheduler.PLANE_WORK_NAME]), and not a stall retry: it stands down while parked.
     */
    val planeRetry: Boolean = false,
) {
    /**
     * The unique work name. ⚠⚠ A delayed retry gets **its own name**: queued under the push's name
     * it would sit there for its 15 minutes and `KEEP` would drop every fresh backlog behind it —
     * measured on the emulator, a 100-photo backup's push waited for an unrelated backstop.
     */
    val uniqueName: String
        get() = when {
            planeRetry -> SyncPushScheduler.PLANE_WORK_NAME
            initialDelayMs > 0 -> SyncPushScheduler.RETRY_WORK_NAME
            else -> SyncPushScheduler.WORK_NAME
        }
}

/**
 * Enqueues Control's own **sync-push job** whenever a pushable backlog appears — what keeps an
 * automatic push alive on Android, where the foreground service is for user-started work only
 * (`control-background-work-off-fgs.md` §5.3).
 *
 * ⚠⚠ Two shapes that fail, both measured (§3.4 there):
 * - firing on **every** rising edge of the *aggregate* liveness: the sync backlog empties after each
 *   uploaded file and refills with the next, so a 300-photo backup produced 746 expedited jobs.
 *   This reacts to [BackgroundLiveness.SYNC_DRAIN] only, and one job drains a whole burst
 *   ([SyncPushRun] lingers for more before it finishes);
 * - a job that runs one tick and returns, so the network goes away under whatever is still
 *   running.
 *
 * ⚠⚠ **A fresh backlog is always queued behind whatever push is there** (`APPEND_OR_REPLACE`,
 * [SyncPushRequest.appendToRunning]), and coalesced here instead: at most one request waits to start
 * ([waitingToStart]). `KEEP` loses edges — a rising edge between a worker's last check and WorkManager
 * marking it finished (the worker reports back in a `finally`, before `doWork` returns) is dropped
 * while the work still reads RUNNING, and the raised source never rises again, so the backlog waits
 * for the hourly periodic drain.
 *
 * Platform-free and JVM-tested; the WorkManager coupling is [enqueue] (Android) — other platforms
 * need no job, their proxy is a resident daemon or in-process.
 */
class SyncPushScheduler(
    private val backgroundLiveness: BackgroundLiveness,
    private val scope: CoroutineScope,
    /**
     * Bytes of content a push could send **now** — 0 for content the Wi-Fi-only gate holds — or
     * `null` if unknown. Decides [SyncPushRequest.expedited]. ⚠ Not every pending byte: 200 MB held on
     * cellular would make a small metadata edit a regular job, which can wait hours in RARE or Doze.
     */
    private val pendingContentBytes: suspend () -> Long?,
    private val enqueue: (SyncPushRequest) -> Unit,
    /**
     * `true` while the Wi-Fi-only policy holds file content back on a metered network
     * (`SyncHealthFigures.deferred`). On its rising edge [enqueueDeferredContentPush] registers a job
     * that waits for an **unmetered** network, so held content uploads when Wi-Fi returns instead
     * of at the next hourly periodic drain — the process is cached by then, and nothing else wakes.
     */
    private val contentDeferred: Flow<Boolean>,
    private val enqueueDeferredContentPush: () -> Unit,
    /** Monotonic milliseconds, for the stall cool-down. */
    private val nowMs: () -> Long,
    private val expediteMaxBytes: Long = EXPEDITE_MAX_BYTES,
    /**
     * File-content emits completed so far, on any path (`SyncScheduler.contentProgressTotal`). Any
     * movement since a stall un-parks the push (`control-background-work-off-fgs.md` §5.7).
     * ⚠ Content only, never accepted metadata: with only content failing, metadata keeps landing and
     * would un-park the push into another failing run. Default: never moves, so only a push run
     * un-parks.
     */
    private val contentProgress: () -> Long = { 0L },
    /**
     * How long the content plane still waits out refused writes, in ms
     * (`SyncScheduler.contentPlaneWaitRemainingMs`). While it waits the content backlog is not
     * pushable, so a run ends IDLE; this is when to come back. Default: never waits.
     */
    private val planeWaitRemainingMs: () -> Long = { 0L },
) {
    private val lock = Mutex()
    private var running = 0

    /** Requests enqueued by this process and not yet started — the coalescing for [onBacklog]. */
    private var waitingToStart = 0

    /**
     * Until when a stalled push stays parked ([shouldRun]). ⚠⚠ Measured on the emulator with every
     * content PUT answered 500: the run ends STALLED after its 8 minutes, and 16 s later the backlog
     * source falls and rises again, so unparked a fresh job would start another 8 minutes — the retry
     * delay bypassed by the very edges the failing backlog keeps producing. `null` = not parked.
     *
     * ⚠ In memory: a new instance — a Control restart, a fresh Koin graph — starts un-parked, and the
     * first edge after it gets a job. The retry already queued under [RETRY_WORK_NAME] still runs.
     */
    private var stalledUntilMs: Long? = null

    /** [contentProgress] when the push parked; any completed content emit since ends the park. */
    private var progressAtStall = 0L

    /** The last `contentDeferred` value, for [watchThePark]. */
    @kotlin.concurrent.Volatile
    private var deferredNow = false

    /** Polls for the end of a park while one lasts — see [watchThePark]. */
    private var parkWatch: Job? = null

    /** Until when a [SyncPushRequest.planeRetry] is already queued; one is enough. */
    private var planeRetryQueuedUntilMs = 0L
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            backgroundLiveness.activeSources
                .map { BackgroundLiveness.SYNC_DRAIN in it }
                .distinctUntilChanged()
                .filter { it }
                .collect { onBacklog() }
        }.also { parent ->
            scope.launch(parent) {
                contentDeferred.distinctUntilChanged().onEach { deferredNow = it }.filter { it }.collect {
                    // ⚠⚠ Not while parked (§5.7). A blocked app sees no network, so `deferred`
                    // rises every time Control drops back to cached — including after each backstop
                    // job that stood down because of the park; registering on each would start and
                    // stand down a backstop job every ~16 s for the whole park (measured on the
                    // emulator). The stall retry owns the backlog until it runs.
                    if (lock.withLock { !parked() }) enqueueDeferredContentPush()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** A pushable backlog appeared. Internal for the test; [start] calls it on the rising edge. */
    internal suspend fun onBacklog() {
        val request = lock.withLock {
            // Parked after a stall: the delayed retry owns the backlog until it runs.
            if (parked()) return@withLock null
            // One queued request drains everything that arrives before it starts.
            if (waitingToStart > 0) return@withLock null
            waitingToStart++
            // ⚠ Appended even with nothing running: a worker that has reported back but not yet
            // returned still reads RUNNING to WorkManager, and KEEP would drop this request there.
            SyncPushRequest(expedited = expedite(), appendToRunning = true)
        }
        request?.let(enqueue)
    }

    /**
     * Whether a job that just started should drain. ⚠ A job other than the stall retry
     * ([isStallRetry]) — a request queued before the stall, or the unmetered backstop — stands down
     * while the push is parked, or it would restart the 8-minute failing run the retry delay exists
     * to space out.
     */
    suspend fun shouldRun(isStallRetry: Boolean): Boolean {
        // ⚠⚠ The retry is the park ending by time, and the backstop for held Wi-Fi-only content
        // must come back with it: the collector in [start] skips `deferred`'s edges while parked,
        // and `deferred` stays raised, so no new edge would ever register it — the held content
        // would wait for the hourly periodic drain.
        if (isStallRetry && deferredNow) enqueueDeferredContentPush()
        return lock.withLock { isStallRetry || !parked() }
    }

    // ⚠⚠ Parked only while the relay has accepted nothing since the stall. Once anything lands — the
    // in-process loop draining with Control in front, a client's inline upload — the plane is
    // answering, and a fresh backlog must get a job now rather than at the 15-minute retry (§5.7).
    private fun parked(): Boolean = stalledUntilMs?.let { nowMs() < it && contentProgress() == progressAtStall } ?: false

    /** A sync-push job began running. Pair with [workerFinished] in a `finally`. */
    suspend fun workerStarted() {
        lock.withLock {
            running++
            waitingToStart = (waitingToStart - 1).coerceAtLeast(0)
        }
    }

    /**
     * A sync-push job finished with [outcome]. Enqueues the follow-up when the run says work
     * remains, or when a backlog appeared while it ran.
     *
     * ⚠ Called for a job the OS stopped, too, with [SyncPushRun.Outcome.STOPPED]: WorkManager
     * re-runs that request itself. A backlog that rose meanwhile is already queued ([onBacklog]).
     */
    suspend fun workerFinished(outcome: SyncPushRun.Outcome) {
        val request = lock.withLock {
            running = (running - 1).coerceAtLeast(0)
            when (outcome) {
                SyncPushRun.Outcome.MORE -> {
                    stalledUntilMs = null
                    if (waitingToStart > 0) return@withLock null // a queued request continues it
                    waitingToStart++
                    SyncPushRequest(expedited = false, appendToRunning = true)
                }
                // Not appended: it goes under its own name, and a fresh backlog must not wait behind it.
                SyncPushRun.Outcome.STALLED -> {
                    stalledUntilMs = nowMs() + STALL_RETRY_DELAY_MS
                    progressAtStall = contentProgress()
                    watchThePark()
                    SyncPushRequest(expedited = false, appendToRunning = false, initialDelayMs = STALL_RETRY_DELAY_MS)
                }
                // A run that got through (or was stopped) un-parks: the backlog is moving again.
                SyncPushRun.Outcome.IDLE -> {
                    stalledUntilMs = null
                    planeRetryRequest()
                }
                else -> null
            }
        }
        request?.let(enqueue)
    }

    /**
     * ⚠⚠ While parked, look every [PARK_POLL_MS] for the relay accepting something, and when it does,
     * act on the backlog that is already waiting (`control-background-work-off-fgs.md` §5.7).
     *
     * The push is edge-triggered, and during a park the `SYNC_DRAIN` source stays raised, so ending
     * the park by progress alone would enqueue nothing: a fresh edge is never coming. Measured on the
     * emulator: once the plane heals, a background backup's upload lands its first chunks on the
     * client's call, the client's job ends, and Android may kill the cached Control for an
     * outstanding binder transaction ("failure to freeze binder"). With no Control job queued,
     * nothing brings Control back until the 15-minute retry. So the first landed progress queues the
     * push job, which is what restarts a Control the OS has killed.
     *
     * ⚠ In-process only, so it lives as long as this process: a dead Control's park is gone with it.
     */
    private fun watchThePark() {
        parkWatch?.cancel()
        // ⚠ Under [job] when started, so [stop] ends the watch with everything else.
        val parent = job
        val watch: suspend CoroutineScope.() -> Unit = watch@{
            while (true) {
                delay(PARK_POLL_MS)
                // Over by time: the retry is due and runs on its own. Over by progress: act now.
                val ended = lock.withLock {
                    val until = stalledUntilMs ?: return@withLock ParkEnd.CLEARED
                    when {
                        nowMs() >= until -> ParkEnd.TIME
                        contentProgress() != progressAtStall -> ParkEnd.PROGRESS
                        else -> null
                    }
                } ?: continue
                if (ended == ParkEnd.PROGRESS &&
                    BackgroundLiveness.SYNC_DRAIN in backgroundLiveness.activeSources.value
                ) {
                    onBacklog()
                }
                // ⚠⚠ On a time-ended park too: held Wi-Fi-only content gets its
                // backstop back, as the retry's own [shouldRun] does when this process is gone.
                if (ended != ParkEnd.CLEARED && deferredNow) enqueueDeferredContentPush()
                return@watch
            }
        }
        parkWatch = if (parent != null) scope.launch(parent, block = watch) else scope.launch(block = watch)
    }

    private enum class ParkEnd { TIME, PROGRESS, CLEARED }

    /**
     * ⚠⚠ The run for when the content plane's wait ends, if it waits. A
     * waiting plane takes the content out of the pushable backlog, so the run ends IDLE with files
     * still queued and no edge left to come — the backlog was never empty, so `SYNC_DRAIN` never
     * falls and rises. Appended rather than kept: the run asking may itself be the last plane retry,
     * still RUNNING to WorkManager. Under [lock].
     */
    private fun planeRetryRequest(): SyncPushRequest? {
        val remaining = planeWaitRemainingMs()
        if (remaining <= 0) return null
        val now = nowMs()
        if (planeRetryQueuedUntilMs > now) return null
        planeRetryQueuedUntilMs = now + remaining
        return SyncPushRequest(
            expedited = false,
            appendToRunning = true,
            initialDelayMs = remaining + PLANE_RETRY_SLACK_MS,
            planeRetry = true,
        )
    }

    private suspend fun expedite(): Boolean {
        val bytes = pendingContentBytes() ?: return true
        return bytes <= expediteMaxBytes
    }

    companion object {
        /**
         * Up to this much pending content runs as an expedited job; more runs as an ordinary one.
         * ⚠ An expedited job is guaranteed 3 min and spends a daily quota (30 min in the ACTIVE
         * bucket, 5 in RESTRICTED); an ordinary one gets 10 min and spends none of it.
         * ⚠ 32 MiB is about eight phone photos: a larger automatic backup's push becomes an ordinary,
         * deferrable job, which in RARE/RESTRICTED can wait for the bucket's window. Deliberate — an
         * automatic backup is deferrable work, and a client's own job lends Control network while it
         * runs (`control-background-work-off-fgs.md` §3.3).
         */
        const val EXPEDITE_MAX_BYTES: Long = 32L * 1024 * 1024

        /** The push's unique work name. */
        const val WORK_NAME: String = "sync-push"

        /** A stalled run's delayed retry — apart from [WORK_NAME], see [SyncPushRequest.uniqueName]. */
        const val RETRY_WORK_NAME: String = "sync-push-retry"

        /** The push for content the Wi-Fi-only policy holds, waiting for an unmetered network. */
        const val UNMETERED_WORK_NAME: String = "sync-push-unmetered"

        /** How long a run that made no progress waits before trying again (the relay is unreachable). */
        const val STALL_RETRY_DELAY_MS: Long = 15L * 60 * 1000

        /** How often a park looks for the relay answering again ([watchThePark]). */
        const val PARK_POLL_MS: Long = 10_000

        /** The run for when the content plane's wait ends ([planeRetryRequest]). */
        const val PLANE_WORK_NAME: String = "sync-push-plane"

        /** A plane retry starts this long after the wait ends, so it does not land a hair before it. */
        const val PLANE_RETRY_SLACK_MS: Long = 1_000
    }
}

/**
 * One drain cycle's verdict for [SyncPushRun]: [stalled] — the relay was not reached or a write was
 * refused; [sent] — the relay accepted at least one write (a block, a tombstone, a content chunk);
 * [completed] — at least one of those finished something: a block, a tombstone, a whole file.
 */
data class SyncPushDrain(val stalled: Boolean, val sent: Boolean, val completed: Boolean = sent)

/**
 * The body of one sync-push job: drain until nothing pushable is left, linger briefly for more,
 * and stop at a time budget (`control-background-work-off-fgs.md` §5.3).
 *
 * ⚠ The linger is the point. During a backup the backlog drains to zero after each file and the
 * next file's blocks arrive a moment later; finishing at the first zero produces one job per file.
 */
class SyncPushRun(
    /** One serialized drain/pull cycle (`SyncScheduler.drainNowStalled`) and its verdict. */
    private val drain: suspend () -> SyncPushDrain,
    /** What is pushable now (`SyncScheduler.pushableBacklog`). */
    private val backlog: suspend () -> Int,
    private val nowMs: () -> Long,
    /**
     * Whether a client request is still running in this proxy (`ProxyGateway.inFlightCount > 0`).
     * ⚠⚠ Measured on the API 37 emulator: an automatic backup uploads each file's content inside the
     * client's own call, so the push backlog reads 0 between files, and a run that trusted it alone
     * would finish mid-backup. The rest of the push would then wait for the next job once the
     * client's job had ended and taken Control's network with it.
     * ⚠ It extends the linger only while the backlog has been non-zero within [activityWindowMs]:
     * a long Background op that leaves nothing to push (a region download, a pairing wait) must not
     * hold an idle job for its whole budget.
     */
    private val clientWorkInFlight: () -> Boolean = { false },
    private val lingerMs: Long = LINGER_MS,
    private val pollMs: Long = POLL_MS,
    private val maxRunMs: Long = MAX_RUN_MS,
    private val maxStalls: Int = MAX_STALLS,
    private val stallBackoffMs: Long = STALL_BACKOFF_MS,
    private val noProgressPauseMs: Long = NO_PROGRESS_PAUSE_MS,
    private val activityWindowMs: Long = ACTIVITY_WINDOW_MS,
    private val noProgressLimitMs: Long = NO_PROGRESS_LIMIT_MS,
) {
    enum class Outcome {
        /** Nothing pushable left, and none arrived during the linger. */
        IDLE,

        /** The time budget ran out with work left and progress made: continue in a new job, now. */
        MORE,

        /** Drains stopped making progress (relay unreachable, refused): try again later. */
        STALLED,

        /** The OS stopped the job; WorkManager re-runs it. Never returned by [run]. */
        STOPPED,
    }

    /** When the backlog was last seen non-zero — what makes client work "recent" ([clientWorkInFlight]). */
    private var lastBacklogAtMs = 0L

    private suspend fun backlogNow(): Int = backlog().also { if (it > 0) lastBacklogAtMs = nowMs() }

    suspend fun run(): Outcome {
        val started = nowMs()
        lastBacklogAtMs = started
        var stalls = 0
        var everSent = false
        var everCompleted = false
        var lastSentAtMs = started
        while (true) {
            val before = backlogNow()
            if (before <= 0) {
                if (!lingerForMore()) return Outcome.IDLE
                if (nowMs() - started >= maxRunMs) return endOfBudget(everSent, everCompleted)
                continue
            }
            val verdict = drain()
            backlogNow()
            // ⚠⚠ The drain's own verdict, never "the backlog did not shrink": a backup adds while
            // this drains, and on the emulator that reading stalls a healthy run mid-backup and
            // parks the rest of the push behind the 15-minute retry. A real stall (relay down,
            // writes refused) must still not spin for 8 minutes.
            if (verdict.stalled) {
                stalls++
                if (stalls >= maxStalls) return Outcome.STALLED
                delay(stallBackoffMs)
            } else {
                stalls = 0
                if (verdict.completed) everCompleted = true
                if (verdict.sent) {
                    everSent = true
                    lastSentAtMs = nowMs()
                } else if (nowMs() - lastSentAtMs >= noProgressLimitMs) {
                    // ⚠ Two minutes of drains that sent nothing is a stall in all but name. Measured
                    // with every content PUT failing: the content emitter retries ~3,000 PUTs a minute
                    // for as long as this job holds the network, so the job must not hold it for 8.
                    return Outcome.STALLED
                } else {
                    // ⚠⚠ No stall, and nothing sent either: a content entry that fails without
                    // flagging the tick (a content-plane 500, "someone already emitting") leaves the
                    // backlog where it was. Looping straight back would run a full drain AND relay
                    // pull back to back for the whole budget. Pause instead — it stays the drain's call
                    // whether this is a stall.
                    delay(noProgressPauseMs)
                }
            }
            if (nowMs() - started >= maxRunMs) return endOfBudget(everSent, everCompleted)
        }
    }

    /**
     * Out of time. Work left and something finished → [Outcome.MORE]; work left but nothing ever
     * finished → [Outcome.STALLED] (its delayed retry), or a failing entry would chain an 8-minute
     * run forever.
     *
     * ⚠⚠ Finished, not merely sent: a file that changes under every attempt re-sends its chunks each
     * time and never completes, and counting the chunks would chain MORE runs for ever. A run of 8 minutes that completed nothing is a stall, whatever moved; a long video
     * that is genuinely still uploading inline ends the park the moment it completes ([SyncPushScheduler]
     * watches for exactly that).
     */
    private suspend fun endOfBudget(everSent: Boolean, everCompleted: Boolean): Outcome = when {
        backlog() <= 0 -> Outcome.IDLE
        everSent && everCompleted -> Outcome.MORE
        else -> Outcome.STALLED
    }

    private suspend fun lingerForMore(): Boolean {
        var waited = 0L
        while (waited < lingerMs) {
            delay(pollMs)
            if (backlogNow() > 0) return true
            // A client mid-request is about to leave work behind, but only if it has been leaving
            // some: returning true sends the loop round again, so the time budget still applies.
            if (clientWorkInFlight() && nowMs() - lastBacklogAtMs < activityWindowMs) return true
            waited += pollMs
        }
        return false
    }

    companion object {
        const val LINGER_MS: Long = 5_000
        const val POLL_MS: Long = 500

        /** Under an ordinary job's 10-minute guarantee, so the run ends itself before the OS does. */
        const val MAX_RUN_MS: Long = 8L * 60 * 1000
        const val MAX_STALLS: Int = 3
        const val STALL_BACKOFF_MS: Long = 2_000

        /** The pause after a drain that neither stalled nor shrank the backlog. */
        const val NO_PROGRESS_PAUSE_MS: Long = 5_000

        /** How long drains may go without the relay accepting anything before the run calls it a stall. */
        const val NO_PROGRESS_LIMIT_MS: Long = 2L * 60 * 1000

        /** How recently the backlog must have been non-zero for in-flight client work to extend the linger. */
        const val ACTIVITY_WINDOW_MS: Long = 30_000
    }
}
