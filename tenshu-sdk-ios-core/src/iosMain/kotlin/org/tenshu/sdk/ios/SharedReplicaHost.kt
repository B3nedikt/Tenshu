@file:OptIn(SharedReplicaPlatformApi::class, kotlin.experimental.ExperimentalObjCRefinement::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.StoreHandleFenced
import org.tenshu.core.common.proxy.db.sqlite.ReplicaFence
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaStore
import platform.Foundation.NSRecursiveLock
import kotlin.coroutines.CoroutineContext
import kotlin.native.HiddenFromObjC

/**
 * ⚠⚠ **What the host's surfaces show for the shared store** (`ios-shared-store-sqlite.md` §6,
 * Appendix B.16, B.17). Kotlin only: Swift reads it through [StorageStateSurface], which turns it
 * into finished copy ("Refreshing…", "Updating storage…", "Checking storage…", the update surface,
 * "Storage damaged", "Storage not ready") and a generation that re-creates the app's root.
 */
@SharedReplicaPlatformApi
@HiddenFromObjC
enum class SharedReplicaHostState {
    /** The graph serves the store. */
    READY,

    /** The graph's store was replaced or failed (§6 `REPLACED`, rule (a)'s `COMMIT_FAILED`): it is stopped, and rebuilt in the foreground. */
    REFRESHING,

    /** A wipe or a migration is in progress on the store (`UPDATING`, §6), or the open needs the foreground to start one. */
    UPDATING,

    /** This app's confirmation walk is owed after a corruption code (B.8.5: "Checking storage…"). */
    CHECKING,

    /** The store was written by a newer build: the update surface, naming this app (§7). */
    SKEW,

    /** `replica/CORRUPT`: never rebuilt, never repaired (§6). */
    CORRUPT,

    /** A retryable refusal ("not ready, try again"): retried at the next protected entry or foreground (Q35). */
    NOT_READY,

    /**
     * ⚠ B.17 (255): **no graph is installed**: before the process's first, or after a deprovision
     * or a choice that no longer allows the store ([SharedReplicaHost.forgetGraph]). Nothing to
     * cover: the app's own gate decides what it shows (the connect screen).
     */
    NO_GRAPH,
}

/**
 * The host's state with its reason. [willRetry]: the host rebuilds by itself at the next foreground
 * or protected entry; false for a store that is never rebuilt (`CORRUPT`, `SKEW` is judged again by
 * the next open), a process latched unusable, or a host with no graph to rebuild.
 */
@SharedReplicaPlatformApi
@HiddenFromObjC
class SharedReplicaHostStatus(
    val state: SharedReplicaHostState,
    val detail: String,
    val willRetry: Boolean,
    /** B.17 (256): for [SharedReplicaHostState.UPDATING], the job is a wipe ("Refreshing…"), not a migration ("Updating storage…"). */
    val wipe: Boolean = false,
    /** B.17 (259), Q42: the open was refused for `ProtectedDataUnavailable`: one retry at the protected-data notification. */
    val protectedDataUnavailable: Boolean = false,
    /**
     * B.17 (257), Q39: how many graphs after the process's first have reached [SharedReplicaHostState.READY].
     * Stamped by the host; a change is what re-creates the app's root over the rebuilt graph.
     */
    val generation: Long = 0,
    /**
     * B.17 (275): the graph waits for a `REPLACEMENT` job to end (every `UPDATING`, and the
     * `REFRESHING` of a forget whose wipe has not finished): what a job's finish, the stand-in's
     * re-open and the Q46 poll act on.
     */
    val awaitsJob: Boolean = state == SharedReplicaHostState.UPDATING,
) {
    internal fun withGeneration(value: Long): SharedReplicaHostStatus =
        if (value == generation) this else SharedReplicaHostStatus(state, detail, willRetry, wipe, protectedDataUnavailable, value, awaitsJob)

    /** A new instance of the same status, so a republish emits (B.17 (280)). */
    internal fun fresh(): SharedReplicaHostStatus =
        SharedReplicaHostStatus(state, detail, willRetry, wipe, protectedDataUnavailable, generation, awaitsJob)

    override fun toString(): String = "SharedReplicaHostStatus($state, retry=$willRetry, gen=$generation: $detail)"
}

/**
 * ⚠⚠ **The store a graph is given when the open answered "not ready, try again"** (Q35;
 * Appendix B.16 (214)). Not the file store: falling back would write `objects/`, which
 * turns the phone to `files` for good (B.14 (190)). It behaves as a fenced handle does (§6): reads
 * answer absent, a listener never fires, writes throw [StoreNotReady] (a [StoreHandleFenced], so
 * the forget's tolerance of a handle that no longer serves holds); the pagers answer null and
 * `isEmpty` false, never an empty store. The host rebuilds the graph over
 * the real store at the next protected entry or foreground.
 */
internal class NotReadyReplicaStore(
    val status: SharedReplicaHostStatus,
    /**
     * ⚠ B.17 (261), Q43: told at each read and each listener set, from whatever thread made it.
     * The host's (cheap, throttled) re-open of a store a sibling's job is replacing. Never throws.
     */
    private val onAccess: () -> Unit = {},
) : ObjectStore {
    override fun storeObject(dbEntry: DbEntry) = throw StoreNotReady(status)
    override fun getObject(dataIdHash: Long): DbEntry? {
        accessed()
        return null
    }
    override fun hasObject(dataIdHash: Long): Boolean = getObject(dataIdHash) != null
    override fun setListener(dataIdHash: Long, listener: (update: DbEntry) -> Unit) = accessed()

    private fun accessed() {
        try {
            onAccess()
        } catch (t: Throwable) {
            println("[replica-host] the stand-in's access hook threw: ${t.message}")
        }
    }
    override fun removeListener(dataIdHash: Long) = Unit
    override fun removeObject(dataIdHash: Long) = throw StoreNotReady(status)

    // ⚠ As a fenced handle answers (§6): `rowsOfTypes` and `scanRowsOfType` stay null ("do not ask
    // me", never an empty list) and `isEmpty` false, which would tell the sync cursor the data was
    // reset.

    /** No file to lock: an in-process lock, so `Database.transaction { }` still nests. */
    val databaseLock: DatabaseLock = object : DatabaseLock {
        private val lock = NSRecursiveLock()
        override fun <R> withLock(block: () -> R): R {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }
    }
}

/** A write reached the not-ready stand-in. Thrown before anything is touched. */
internal class StoreNotReady(status: SharedReplicaHostStatus) : StoreHandleFenced("the shared store is not ready: $status")

/** True when [store] is the not-ready stand-in. A function of its own: no type test on a try's value (K/N). */
internal fun isNotReadyStandIn(store: ObjectStore): Boolean = store is NotReadyReplicaStore


/** The monotonic origin of the hosts' clocks (B.17 (261)): a throttle, so an uptime clock is enough. */
private val hostClockOrigin = kotlin.time.TimeSource.Monotonic.markNow()

/**
 * ⚠⚠ **The fence lifecycle of §6, for the one runtime host of a process** (`FirstPartyLocalRuntime`
 * for a route-1 app, `ControlSurface` for Control; Appendix B.16, B.17). One per
 * [SharedReplicaBinding], so one per process: a process with no binding has no host.
 *
 * - **[graphInstalled]**: the runtime tells it each graph it built, with how to stop and rebuild it.
 *   Over the SQLite store it collects [SharedReplicaStore.fenced] (212); over the not-ready stand-in
 *   a rebuild is owed at once (214), unless the stand-in is for a store this process never serves
 *   (`SKEW`, `CORRUPT`: B.17 (254)), which gives the graph up.
 * - **A fence** (steps 1–3): the state moves ([SharedReplicaHostState.REFRESHING], or `CORRUPT`),
 *   the graph is stopped (its loops unbound, the runtime's own stop: `SyncManager.lock()` for an
 *   app), custody kept. If the process is in the foreground the rebuild is scheduled at once,
 *   within this foreground's one rebuild (213).
 * - **The rebuild** (step 2b and 4): ⚠⚠ in two turns (B.17 (258)): **off the main thread**
 *   the shipping-gate probe, the fenced handle's release (a failure latches the process: no
 *   rebuild, ever) and the open, through [preOpen]; **then on the main thread** the runtime's own
 *   reinstall, whose open finds the store already open. **At most once per foreground** (§6), and
 *   outside that budget at a protected entry (Q35), a job's finish (226), the user's "Try again"
 *   and the protected-data notification (Q42), a stand-in access that finds a sibling's job done
 *   (Q43), and when the runtime asks ([rebuildNow], [resumeAndRebuild]).
 * - **Held** ([hold]): while a caller that released the store itself (Control's forget) has not
 *   said what follows, nothing is rebuilt; [forgetGraph]: never again for that graph (an app's
 *   deprovision, a choice that no longer allows the store).
 *
 * Thread rules: [onForeground] and the reinstall run on the main thread ([mainContext] in
 * production); the collector, [preOpen] and step 2b of a scheduled rebuild off it
 * ([offMainContext]); every field is under [lock], and no callback is made holding it.
 */
@SharedReplicaPlatformApi
@HiddenFromObjC
class SharedReplicaHost internal constructor(
    private val isForegroundSource: () -> Boolean,
    /** Releases the process's fenced runtime handle; false when the release failed (the registry latched). */
    private val release: () -> Boolean,
    /**
     * Stops and forgets **the loops binding a graph was installed with** ([currentLoops]'s value
     * then), and nothing else: a late teardown never touches a successor's loops (B.16 (251)).
     */
    private val unbindLoops: (loops: Any?) -> Unit,
    /** The loops binding at a graph's install, handed back to [unbindLoops] at its stop. */
    private val currentLoops: () -> Any? = { null },
    /** Where a scheduled or an entry's reinstall runs: the main thread in production. */
    internal var mainContext: CoroutineContext = Dispatchers.Main,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val log: (String) -> Unit = ::println,
    /** Whether a protected entry is running now (B.16 (232)). A test seam. */
    internal var entryActive: () -> Boolean = { SharedReplicaEntries.activeEntries() > 0 },
    /** An entry's token for a background rebuild (B.16 (239)): the binding's. Null: none can be had. */
    internal var protectEntry: ((onExpired: () -> Unit) -> org.tenshu.core.common.proxy.db.sqlite.SharedReplicaEntryToken?)? = null,
    /**
     * ⚠⚠ B.17 (258), Q41: the binding's open, run **off the main thread** before a rebuild's
     * main-thread turn, so the reinstall's own open finds the store already open. [waitForJob]:
     * the bounded wait for a job the worker runs, for a background rebuild only (B.16 (227)).
     * ⚠⚠ B.17 (273): [stillCurrent] is re-checked by the binding **while it holds its open lock**,
     * before it opens: a hold or a forget since the prepare began (Control's forget, a
     * deprovision) and the open does nothing. The handle opened, or null. Never throws past here.
     */
    internal var preOpen: (waitForJob: Boolean, stillCurrent: () -> Boolean) -> Any? = { _, _ -> null },
    /**
     * B.17 (273): a handle [preOpen] opened for a rebuild that then bailed (held, or no graph):
     * released, if it is still the binding's cached one.
     */
    internal var releasePrepared: (handle: Any) -> Unit = {},
    /** Where [preOpen] and a scheduled rebuild's step 2b run: never the main thread. */
    internal var offMainContext: CoroutineContext = Dispatchers.Default,
    /** Milliseconds on a monotonic clock, for the stand-in's re-open throttle (B.17 (261)). A test seam. */
    internal var clockMs: () -> Long = { hostClockOrigin.elapsedNow().inWholeMilliseconds },
) {
    /** How a rebuild was asked for. */
    internal enum class Trigger { FOREGROUND, ENTRY, EXPLICIT, JOB_FINISHED, TRY_AGAIN, PROTECTED_DATA, SIBLING_FINISHED, POLL }

    /** One graph a runtime built. */
    private class Graph(
        val id: Long,
        val store: ObjectStore,
        val stop: () -> Unit,
        val rebuild: () -> Unit,
        val mayRebuild: () -> Boolean,
        val abandon: () -> Unit,
        /** B.17 (263): told at each foreground while this graph serves. */
        val foreground: () -> Unit,
        /** The loops binding this graph was installed with (251). */
        val loops: Any?,
    ) {
        /** The stop is claimed (one caller runs it). */
        var stopped = false

        /** ⚠⚠ (251) The stop has run to its end: what a rebuild waits for, never [stopped]. */
        val stopDone = kotlinx.coroutines.CompletableDeferred<Unit>()
        var abandoned = false
    }

    // ⚠ Every field above any function that reads it, and there is no `init` (K/N).
    private val lock = NSRecursiveLock()
    private val statusFlow = MutableStateFlow(SharedReplicaHostStatus(SharedReplicaHostState.NO_GRAPH, "no graph installed yet", willRetry = false))
    private var graph: Graph? = null
    private var nextGraphId = 0L
    private var collector: Job? = null

    /**
     * B.17 (274), (279): the store the store module last resolved, and how many job finishes had
     * been told before that open began. Read by [graphInstalled] **only for that same store**, so
     * a failed assembly's record never reaches another graph.
     */
    private var lastOpenStore: ObjectStore? = null
    private var lastOpenFinished = 0L

    /**
     * ⚠⚠ B.17 (273): moved by every [hold], [forgetGraph] and [graphInstalled]: a prepare whose
     * ticket has moved opens nothing.
     */
    private var prepareTicket = 0L

    /** B.17 (276), Q46: the surface's observers, and the poll that runs while one is attached. */
    private var observers = 0
    private var pollJob: Job? = null

    /** Test seam (B.17 (289)): overrides whether the process is in the foreground. Null in production. */
    @kotlin.concurrent.Volatile
    internal var foregroundForTests: (() -> Boolean)? = null

    private fun isForeground(): Boolean = foregroundForTests?.invoke() ?: isForegroundSource()

    /**
     * B.17 (287): the finish count [graphInstalled] has already replayed: a rebuild's own
     * missed-finish check does not schedule a second rebuild for the same finish.
     */
    private var replayedFinishes = -1L

    /** Test seam: `JOB_FINISHED` rebuilds scheduled. */
    internal var jobFinishedScheduled = 0
        private set

    /** B.17 (283): [rebuilding], as a flow an entry can wait on. */
    private val rebuildingFlow = MutableStateFlow(false)

    /** B.17 (277): a "Try again" between its tap and the end of its rebuild. */
    private var tryAgainInFlight = false
    private var owed = false
    private var held = false
    private var rebuilding = false
    private var unusable = false
    private var foregrounds = 0L

    /** B.17 (257): graphs after the process's first that reached READY; stamped on every status. */
    private var generation = 0L

    /** B.17 (259), Q42: the one automatic retry at the protected-data notification, per stand-in graph. */
    private var protectedDataRetried = false

    /** B.17 (261), Q43: the stand-in's re-open, one at a time and at most every [REOPEN_PROBE_INTERVAL_MS]. */
    private var probing = false
    private var lastProbeAtMs = 0L
    private var probedOnce = false

    /** `REPLACEMENT_FINISHED` outcomes told so far: latched, so a finish that lands while a rebuild runs is not lost (231). */
    private var finishedJobs = 0L

    /** A fence inside a background entry rebuilt once since the last foreground (232). */
    private var backgroundRebuildScheduled = false
    private var rebuiltInForeground = -1L

    /** Test seam: rebuilds started, by trigger. */
    internal val rebuilds = HashMap<Trigger, Int>()

    /** ⚠⚠ The state the host's surfaces show ([StorageStateSurface] bridges it to Swift). */
    val status: StateFlow<SharedReplicaHostStatus> = statusFlow.asStateFlow()

    /** Whether a rebuild is owed now. */
    internal val isRebuildOwed: Boolean get() = locked { owed && graph != null && !unusable }

    /** B.17 (260): the user's "Try again" can do something now: not ready, a graph to rebuild, not latched or held. */
    internal val canTryAgain: Boolean
        get() = locked { graph != null && !unusable && !held && !tryAgainInFlight && statusFlow.value.state in TRY_AGAIN_STATES }

    /**
     * Told by the store module when it resolves the graph's store ([store], the SQLite store or
     * the stand-in), with [finishedBefore], [finishedJobsNow] taken **before** that open began
     * (B.17 (274)): a job that finished in between is replayed by [graphInstalled].
     */
    internal fun recordOpen(store: ObjectStore, finishedBefore: Long) = locked {
        lastOpenStore = store
        lastOpenFinished = finishedBefore
    }

    /** The job finishes told so far: what the store module reads before an open (B.17 (274)). */
    internal fun finishedJobsNow(): Long = locked { finishedJobs }

    /**
     * The runtime's newly built graph over [store]. [stop] is step 2's runtime half (never throws
     * past here); [rebuild] reinstalls the runtime (on the main thread); [mayRebuild] is step 4's
     * shipping-gate probe. Replaces any earlier graph: its fence is no longer collected.
     */
    internal fun graphInstalled(
        store: ObjectStore,
        stop: () -> Unit,
        rebuild: () -> Unit,
        mayRebuild: () -> Boolean = { true },
        /** B.16 (238): this graph is given up with no rebuild to follow. Once, never throws past here. */
        abandon: () -> Unit = {},
        /** B.17 (263): at each foreground while this graph serves, on the main thread. Cheap; never throws past here. */
        foreground: () -> Unit = {},
    ) {
        val sqlite = sqliteStoreOf(store)
        val notReady = notReadyStatusOf(store)
        var missedFinish = false
        val made = locked {
            val made = Graph(++nextGraphId, store, stop, rebuild, mayRebuild, abandon, foreground, currentLoops())
            graph = made
            held = false
            protectedDataRetried = false
            prepareTicket++
            // ⚠⚠ B.17 (274): a job that finished between this graph's open
            // answering `Updating` and now was counted with no graph to rebuild: replayed below.
            val finishedBefore = if (lastOpenStore === store) lastOpenFinished else finishedJobs
            lastOpenStore = null
            missedFinish = notReady != null && notReady.willRetry && notReady.awaitsJob && finishedJobs != finishedBefore
            when {
                notReady != null -> {
                    owed = notReady.willRetry
                    publish(notReady)
                }
                sqlite != null -> {
                    owed = false
                    // ⚠ B.17 (257): the first graph's screens are built over it already; a later
                    // graph's READY is what re-creates the app's root.
                    if (made.id > 1) generation++
                    publish(SharedReplicaHostStatus(SharedReplicaHostState.READY, "the graph serves the shared store", willRetry = false))
                }
                else -> {
                    // Any other store: never a fallback for a failed open (B.17 (254), Q40).
                    owed = false
                    if (made.id > 1) generation++
                    // B.17 (279): never a failed open's status over a store that serves.
                    publish(SharedReplicaHostStatus(SharedReplicaHostState.READY, "the graph serves the file store", willRetry = false))
                }
            }
            collector?.cancel()
            collector = null
            made
        }
        // ⚠ A graph over the stand-in is stopped at once (§6 step 2): its loops would only spin
        // against a store that refuses every write.
        if (notReady != null) {
            stop(made)
            // ⚠⚠ B.17 (254), Q40: a stand-in for a store this process never serves (a newer build's,
            // or one marked corrupt) has no rebuild to follow in this process: given up (238).
            if (notReady.state == SharedReplicaHostState.SKEW || notReady.state == SharedReplicaHostState.CORRUPT) abandon(made)
        }
        if (sqlite != null) {
            val job = scope.launch {
                val fence = sqlite.fenced.first { it != null } ?: return@launch
                onFenced(made.id, fence)
            }
            locked { if (graph === made) collector = job else job.cancel() }
        }
        if (missedFinish && isForeground()) {
            log("[replica-host] a job finished while this graph's open waited on it: rebuilding")
            locked { replayedFinishes = finishedJobs }
            schedule(Trigger.JOB_FINISHED)
        }
    }

    /** §6 steps 1–3 for the graph [id], on the collector's thread; then, in the foreground, the rebuild scheduled. */
    internal fun onFenced(id: Long, fence: ReplicaFence) {
        val target = locked {
            val current = graph?.takeIf { it.id == id } ?: return
            if (fence == ReplicaFence.CORRUPT) {
                owed = false
                publish(SharedReplicaHostStatus(SharedReplicaHostState.CORRUPT, "replica/CORRUPT: the store is never rebuilt (§6)", willRetry = false))
            } else {
                owed = true
                publish(SharedReplicaHostStatus(SharedReplicaHostState.REFRESHING, "the store was fenced ($fence)", willRetry = !unusable))
            }
            current
        }
        log("[replica-host] the graph's store was fenced ($fence): stopped, custody kept")
        stop(target)
        // B.16 (238): a corrupt store, or a process already latched, is never rebuilt.
        if (fence == ReplicaFence.CORRUPT || locked { unusable }) {
            abandon(target)
            return
        }
        if (isForeground()) {
            schedule(Trigger.FOREGROUND)
        } else if (entryActive()) {
            // ⚠ B.16 (232): a fence inside a background entry (a Timeline location
            // burst, a refresh): rebuilt now, once until the next foreground, so the process does
            // not sit on a stopped graph, its capture detached, until the user opens the app.
            val first = locked { (!backgroundRebuildScheduled).also { backgroundRebuildScheduled = true } }
            if (first) scope.launch { rebuildInBackground(Trigger.ENTRY) }
        }
    }

    /**
     * ⚠⚠ B.17 (258), Q41: a rebuild in two turns: step 2b and the open off the main thread
     * ([prepareOffMain]), then the reinstall on it. Never waits on main.
     */
    private fun schedule(trigger: Trigger) {
        if (trigger == Trigger.JOB_FINISHED) locked { jobFinishedScheduled++ }
        scope.launch { rebuildScheduled(trigger) }
    }

    private suspend fun rebuildScheduled(trigger: Trigger, waitForJob: Boolean = false): Boolean {
        val prepared = prepareOffMain(waitForJob)
        return withContext(mainContext) { rebuild(trigger, prepared) }
    }

    /**
     * ⚠⚠ B.17 (258), Q41: **the slow half of a rebuild, off the main thread**: step 4's probe, step
     * 2b's release of the fenced handle (a failure latches the process here, as in [rebuild]) and
     * the open, through [preOpen]. The reinstall that follows on main then finds the store open
     * and only assembles its graph. Only for a graph a rebuild may claim now, and only once its
     * stop has ended (§6: step 2 before 2b; (251)); otherwise nothing, and [rebuild] decides as
     * before. ⚠⚠ B.17 (273): it holds a **ticket**, which [hold], [forgetGraph] and
     * [graphInstalled] move, and the binding re-checks it under its open lock, so a forget that
     * releases the store and starts its wipe never meets a handle this prepare opened before the
     * wipe. The handle opened, or null.
     */
    private suspend fun prepareOffMain(waitForJob: Boolean): Any? {
        var ticket = 0L
        val target = locked {
            ticket = prepareTicket
            graph?.takeIf { owed && !held && !unusable && !rebuilding && it.stopped && it.stopDone.isCompleted && !it.abandoned }
        } ?: return null
        if (sqliteStoreOf(target.store)?.fencedBy == ReplicaFence.CORRUPT) return null
        val stillCurrent = { locked { prepareTicket == ticket && graph === target && !held && !unusable } }
        return withContext(offMainContext) {
            val allowed = try {
                target.mayRebuild()
            } catch (t: Throwable) {
                log("[replica-host] the shipping-gate probe threw: ${t.message}")
                false
            }
            // Refused: the reinstall's turn repeats the probe and gives the graph up (238).
            if (!allowed) return@withContext null
            if (!stillCurrent()) return@withContext null
            if (!release()) {
                latch(target)
                return@withContext null
            }
            try {
                preOpen(waitForJob, stillCurrent)
            } catch (t: Throwable) {
                log("[replica-host] the open before the rebuild threw: ${t.message}")
                null
            }
        }
    }

    /**
     * ⚠⚠ B.16 (239): the background rebuild of (232) **under a token of
     * its own**: the entry that met the fence may be over by the time it runs (Timeline's detach
     * cancels its drain, and the release of its token may quiesce), and an open of the App Group
     * store with no protection, which may hold the shared lock into a suspension, is the
     * `0xdead10cc` case. No time granted: nothing is rebuilt (the next entry's retry is). Its open
     * keeps the bounded wait for a job (227), off the main thread (B.17 (258)).
     */
    private suspend fun rebuildInBackground(trigger: Trigger) {
        val acquire = protectEntry
        if (acquire == null) {
            log("[replica-host] a background rebuild with no way to take a token: not rebuilt")
            return
        }
        val ran = SharedReplicaEntries.protectedBy("rebuild", acquire) {
            rebuildScheduled(trigger, waitForJob = true)
        }
        if (ran == null) log("[replica-host] no protected time for the background rebuild: left to the next entry or foreground")
    }

    /**
     * ⚠⚠ B.16 (253): the rebuild a claimed-but-unfinished stop held back, once that stop has ended. The
     * claimer may schedule nothing after its stop (a fence collector that found the app in the
     * background with no entry, a `hold` racing a `resume`), so this is the one place the held-back
     * rebuild comes back from. In the foreground, scheduled, as asked. ⚠ In the background **never
     * unprotected**: under a token of its own (`entry:rebuild`, as (239)), and not at all when none
     * is granted (it stays owed for the next entry or foreground). The entry that asked may well be
     * over by now.
     */
    private fun retryAfterStop(trigger: Trigger) {
        if (isForeground()) {
            schedule(if (trigger == Trigger.ENTRY) Trigger.FOREGROUND else trigger)
        } else {
            scope.launch { rebuildInBackground(Trigger.ENTRY) }
        }
    }

    /**
     * At every foreground, on the main thread (the adapter's hook, after the gates reopened and
     * the worker was kicked): this foreground's one rebuild, if one is owed, ⚠⚠ **scheduled and
     * never run here** (B.16 (221)): a rebuild is a release, an open and the app's
     * whole `install()`, and inside `willEnterForeground` it would sit on the resume watchdog.
     * Then the serving graph's own foreground hook (B.17 (263)).
     */
    internal fun onForeground() {
        val (owedNow, serving) = locked {
            foregrounds++
            backgroundRebuildScheduled = false
            (owed && graph != null && !unusable) to graph?.takeIf { !it.stopped && statusFlow.value.state == SharedReplicaHostState.READY }
        }
        if (owedNow) schedule(Trigger.FOREGROUND)
        if (serving != null) {
            try {
                serving.foreground()
            } catch (t: Throwable) {
                log("[replica-host] the graph's foreground hook threw: ${t.message}")
            }
        }
    }

    /**
     * B.16 (226), Q43 in part: a `REPLACEMENT` job of the store finished (told on the worker's
     * queue). A graph waiting on the stand-in for `UPDATING` (a rebuild never waits for a job,
     * (227)) is rebuilt now, if the process is in the foreground.
     */
    internal fun onReplacementFinished() {
        val waiting = locked {
            finishedJobs++
            // ⚠ B.17 (287): not during a rebuild, which owns this finish (its install replays a
            // finish its open missed, (274), and its `finally` checks for one after (231)).
            owed && graph != null && !unusable && !held && !rebuilding && statusFlow.value.awaitsJob
        }
        if (waiting && isForeground()) schedule(Trigger.JOB_FINISHED)
    }

    /**
     * ⚠⚠ B.17 (292): the worker's run of the job this graph waits on ended
     * for good (`REPLACEMENT_SKEW`, `REPLACEMENT_CORRUPT`). Nothing would otherwise move the
     * spinner: the job's finish is never told, and a look's open answers a refusal, not a store. So
     * the host publishes that state at once and gives the graph up, as Q40 and Q48 have it for the
     * same answer at an open. During a rebuild nothing is done: its own open meets the same answer.
     */
    internal fun onReplacementEndedForGood(corrupt: Boolean) {
        val target = locked {
            val current = graph ?: return
            if (held || unusable || rebuilding || !statusFlow.value.awaitsJob) return
            owed = false
            publish(
                if (corrupt) {
                    SharedReplicaHostStatus(SharedReplicaHostState.CORRUPT, "the job's run found the store marked corrupt (§6)", willRetry = false)
                } else {
                    SharedReplicaHostStatus(SharedReplicaHostState.SKEW, "the job's run found the store written by a newer build (§7)", willRetry = false)
                },
            )
            current
        }
        log("[replica-host] the job this graph waited on cannot go on (${if (corrupt) "CORRUPT" else "SKEW"}): the graph is given up")
        stop(target)
        abandon(target)
    }

    /**
     * ⚠⚠ B.17 (260), Q42: **the user's "Try again"** on the not-ready surface. Outside the
     * per-foreground budget (one discrete event per press). True when a rebuild was scheduled.
     */
    internal fun tryAgain(): Boolean {
        var was: SharedReplicaHostStatus? = null
        val go = locked {
            if (graph == null || unusable || held || rebuilding || tryAgainInFlight) return@locked false
            if (statusFlow.value.state !in TRY_AGAIN_STATES) return@locked false
            // A non-retryable refusal left nothing owed (B.17 (254)): the user asks again.
            owed = true
            // ⚠ B.17 (277): one tap, one rebuild. The flag holds from the tap to the rebuild's
            // end, across its off-main turn, and the state says so (which hides the button).
            tryAgainInFlight = true
            val before = statusFlow.value
            was = before
            publish(SharedReplicaHostStatus(SharedReplicaHostState.REFRESHING, TRYING_AGAIN, willRetry = true, awaitsJob = before.awaitsJob))
            true
        }
        if (!go) return false
        log("[replica-host] Try again: rebuilding the graph")
        val foreground = isForeground()
        scope.launch {
            try {
                if (foreground) rebuildScheduled(Trigger.TRY_AGAIN) else rebuildInBackground(Trigger.TRY_AGAIN)
            } finally {
                locked {
                    tryAgainInFlight = false
                    // ⚠ B.17 (288): a "Try again" whose rebuild published nothing (the probe
                    // refused, no time in the background) leaves "Trying again…" up, a spinner
                    // for 15 s: said NOT_READY at once, so the button comes back.
                    if (statusFlow.value.detail == TRYING_AGAIN) {
                        // ⚠ B.17 (293): what the graph waited on before the tap is carried through,
                        // so the poll, a job's finish and the protected-data retry still act on it.
                        publish(
                            SharedReplicaHostStatus(
                                SharedReplicaHostState.NOT_READY,
                                "Try again did not rebuild the graph",
                                willRetry = true,
                                protectedDataUnavailable = was?.protectedDataUnavailable == true,
                                awaitsJob = was?.awaitsJob == true,
                            ),
                        )
                    } else {
                        statusFlow.value = statusFlow.value.fresh()
                    }
                }
            }
        }
        return true
    }

    /**
     * ⚠⚠ B.17 (259), Q42: `UIApplicationProtectedDataDidBecomeAvailable`, on the main thread. **One**
     * automatic retry per stand-in graph, and only when its open was refused for
     * `ProtectedDataUnavailable`: in the foreground scheduled as any other, in the background
     * under a token of its own (the first unlock is usually a background moment). Outside the
     * per-foreground budget.
     */
    internal fun onProtectedDataAvailable() {
        val go = locked {
            val eligible = graph != null && owed && !unusable && !held && !protectedDataRetried &&
                statusFlow.value.protectedDataUnavailable
            if (eligible) protectedDataRetried = true
            eligible
        }
        if (!go) return
        log("[replica-host] protected data is available again: one retry of the not-ready open")
        if (isForeground()) schedule(Trigger.PROTECTED_DATA) else scope.launch { rebuildInBackground(Trigger.PROTECTED_DATA) }
    }

    /**
     * ⚠⚠ B.17 (261), Q43: **a read of the stand-in while the store is being replaced** (`UPDATING`),
     * from any thread. A job a sibling runs moves the epoch when it finishes, and this process is
     * told nothing of it; so at the next access, in the foreground, at most every
     * [REOPEN_PROBE_INTERVAL_MS] and one at a time, the store is opened again off the main
     * thread, and only an open that serves is followed by a rebuild. Never blocks the caller.
     */
    internal fun onStandInAccess() = lookAgain(Trigger.SIBLING_FINISHED, throttled = true)

    /**
     * The look of (261) and of the Q46 poll (276): while the graph waits for a job, in the
     * foreground, one at a time, the store opened again off main; only an open that serves is
     * followed by a rebuild. [throttled]: at most every [REOPEN_PROBE_INTERVAL_MS].
     */
    private fun lookAgain(trigger: Trigger, throttled: Boolean) {
        if (!isForeground()) return
        val now = clockMs()
        val go = locked {
            val eligible = statusFlow.value.awaitsJob &&
                graph != null && owed && !unusable && !held && !rebuilding && !probing &&
                (!throttled || !probedOnce || now - lastProbeAtMs >= REOPEN_PROBE_INTERVAL_MS)
            if (eligible) {
                probing = true
                probedOnce = true
                lastProbeAtMs = now
            }
            eligible
        }
        if (!go) return
        scope.launch {
            try {
                val prepared = prepareOffMain(waitForJob = false)
                if (prepared != null) withContext(mainContext) { rebuild(trigger, prepared) }
            } finally {
                locked { probing = false }
            }
        }
    }

    /**
     * ⚠⚠ B.17 (276), Q46: a covered root reads nothing, so the stand-in's
     * look alone can wait for ever. While a [StorageStateSurface] observer is attached and the
     * graph waits for a job, a poll looks every [pollIntervalMs] (in the foreground only, see
     * [lookAgain]), and ends at any other state. Told by the surface.
     */
    internal fun observerAttached() {
        locked { observers++ }
        ensurePoll()
    }

    internal fun observerDetached() {
        locked { if (observers > 0) observers-- }
    }

    /** The poll's period. A test seam. */
    internal var pollIntervalMs: Long = REOPEN_PROBE_INTERVAL_MS

    /** Test seam: looks the poll has made. */
    internal var polls = 0
        private set

    private fun ensurePoll() {
        locked {
            if (observers == 0 || pollJob?.isActive == true || !statusFlow.value.awaitsJob) return
            pollJob = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(pollIntervalMs)
                    val still = locked { observers > 0 && statusFlow.value.awaitsJob }
                    if (!still) break
                    // B.17 (290): a tick in the background is skipped (a look is foreground-only).
                    if (!isForeground()) continue
                    locked { polls++ }
                    lookAgain(Trigger.POLL, throttled = false)
                }
            }
        }
    }

    /**
     * ⚠ Q35: at a protected entry, inside its token, before its work: a rebuild owed by a not-ready
     * open or a fence. Its open off the main thread with the bounded wait for a job (227, B.17
     * (258)), then the reinstall on main. Cancellable only before the reinstall starts: once
     * dispatched it runs to its end and leaves the host consistent (the guard released in a `finally`).
     */
    internal suspend fun retryAtEntry() {
        if (!isRebuildOwed) return
        val prepared = prepareOffMain(waitForJob = true)
        withContext(mainContext) { rebuild(Trigger.ENTRY, prepared) }
        // ⚠ B.17 (283): another trigger's rebuild was running (a job's finish, say), so this one
        // stood aside: the entry's work waits for that rebuild to end, bounded and suspended,
        // never over the graph it is replacing.
        if (locked { rebuilding }) {
            kotlinx.coroutines.withTimeoutOrNull(ENTRY_WAITS_FOR_REBUILD_MS) { rebuildingFlow.first { !it } }
        }
    }

    /**
     * Until [resume] or [forgetGraph]: a caller that releases the store itself (Control's forget,
     * B.14 (191)) stops the graph now and rebuilds nothing while it runs. The fence its release
     * makes is collected as any other, but never rebuilt from while held.
     */
    internal fun hold(why: String) {
        val target = locked {
            held = true
            prepareTicket++
            graph
        } ?: return
        // B.17 (280): what the surface offers depends on `held`; said again, so it is not stale.
        republish()
        log("[replica-host] held ($why): the graph is stopped, nothing is rebuilt until it is resumed")
        stop(target)
    }

    /** Ends a [hold]: the rebuild is owed from now, at once when [now] (on the calling thread), else at the next foreground or entry. */
    internal fun resume(now: Boolean): Boolean {
        if (!endHold()) return false
        if (!now) return false
        return rebuild(Trigger.EXPLICIT)
    }

    /**
     * [resume] for a suspending caller (Control's forget, B.17 (258)): when [now], the rebuild in
     * its two turns, off the main thread then on it. True when a graph serves afterwards.
     */
    internal suspend fun resumeAndRebuild(now: Boolean): Boolean {
        if (!endHold()) return false
        if (!now) return false
        return rebuildScheduled(Trigger.EXPLICIT)
    }

    private fun endHold(): Boolean = locked {
        if (!held) return@locked false
        held = false
        if (graph != null && !unusable) {
            owed = true
            // B.17 (275): it waits for the wipe's job: its finish, the stand-in's look and the
            // poll rebuild it, which a REFRESHING not marked so would never be (Control's
            // unfinished forget).
            publish(SharedReplicaHostStatus(SharedReplicaHostState.REFRESHING, "the store was released for a wipe", willRetry = true, awaitsJob = true))
        }
        true
    }

    /**
     * The graph is gone for good (an app's deprovision, B.13 (171); a choice that no longer allows
     * the store, B.14 (196)): stopped, never rebuilt. A later [graphInstalled] starts again.
     */
    internal fun forgetGraph(why: String) {
        val target = locked {
            val was = graph
            graph = null
            owed = false
            held = false
            prepareTicket++
            collector?.cancel()
            collector = null
            publish(SharedReplicaHostStatus(SharedReplicaHostState.NO_GRAPH, "no graph: $why", willRetry = false))
            was
        } ?: return
        stop(target)
        abandon(target)
    }

    /** [rebuild] for a caller that asks (tests). True when a graph is serving afterwards. */
    internal fun rebuildNow(): Boolean = rebuild(Trigger.EXPLICIT)

    /**
     * §6 step 2b and step 4, on the calling thread (the main thread in production). When a
     * scheduled rebuild's [prepareOffMain] ran first, step 2b finds nothing left to release and the
     * reinstall's open finds the store open. True when, afterwards, the new graph serves the store.
     */
    internal fun rebuild(trigger: Trigger, prepared: Any? = null): Boolean {
        // ⚠ B.17 (273): a handle a prepare opened for this rebuild, which then finds the graph
        // held or gone, is released here (on main, before anything can install over it).
        if (prepared != null && locked { graph == null || held }) {
            try {
                releasePrepared(prepared)
            } catch (t: Throwable) {
                log("[replica-host] releasing the prepared handle threw: ${t.message}")
            }
            return false
        }
        var finishedAtStart = -1L
        var corrupt: Graph? = null
        var stopPending: Graph? = null
        val claimed: Graph? = locked {
            val current = graph ?: return false
            if (!owed || held || unusable || rebuilding) return false
            // ⚠ B.17 (276): a look or a poll rebuilds only a graph still waiting for its job, never
            // one a fence has since left NOT_READY (the per-foreground budget is not theirs).
            if ((trigger == Trigger.POLL || trigger == Trigger.SIBLING_FINISHED) && !statusFlow.value.awaitsJob) return false
            if (sqliteStoreOf(current.store)?.fencedBy == ReplicaFence.CORRUPT) {
                owed = false
                publish(SharedReplicaHostStatus(SharedReplicaHostState.CORRUPT, "replica/CORRUPT: the store is never rebuilt (§6)", willRetry = false))
                corrupt = current
                return@locked null
            }
            if (current.stopped && !current.stopDone.isCompleted) {
                // Before the budget and the claim: nothing is spent on a rebuild that waits.
                stopPending = current
                return@locked null
            }
            if (trigger == Trigger.FOREGROUND) {
                // ⚠⚠ At most once per foreground (§6): a second failure in the same foreground waits
                // for the next one, so a full disk cannot loop rebuilds.
                if (rebuiltInForeground == foregrounds) {
                    publish(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "storage unavailable: already rebuilt in this foreground; again at the next", willRetry = true))
                    return false
                }
                rebuiltInForeground = foregrounds
            }
            rebuilding = true
            rebuildingFlow.value = true
            finishedAtStart = finishedJobs
            rebuilds[trigger] = (rebuilds[trigger] ?: 0) + 1
            // The release below fences the old handle RELEASED: not a fence to act on.
            collector?.cancel()
            collector = null
            current
        }
        corrupt?.let { abandon(it) }
        // ⚠⚠ B.16 (253): this check comes BEFORE the early return below, or no retry is registered.
        // ⚠⚠ B.16 (251): the stop is claimed by another caller (the fence
        // collector, off main) and still running: this rebuild must not release the handle or
        // install a successor before it ends, or its late steps would reach the successor. Never
        // a wait on main: the rebuild is given back and run again once the stop has ended.
        stopPending?.let { pending ->
            log("[replica-host] a rebuild ($trigger) waits for the graph's stop to end")
            pending.stopDone.invokeOnCompletion {
                // ⚠ B.17 (264): a completion handler's throw reaches the global handler on K/N.
                try {
                    retryAfterStop(trigger)
                } catch (t: Throwable) {
                    log("[replica-host] the retry after the stop threw: ${t.message}")
                }
            }
            return false
        }
        val target = claimed ?: return false
        try {
            if (!target.mayRebuild()) {
                log("[replica-host] the shipping-gate probe refused the rebuild ($trigger): left to the app's own gate")
                // B.16 (238): no successor follows a refused probe until the app's gate decides.
                abandon(target)
                return false
            }
            stop(target)
            // ⚠⚠ Step 2b: the fenced handle released through the registry BEFORE anything opens
            // anew. A failure latches the process: "storage unavailable", no step 4.
            if (!release()) {
                latch(target)
                return false
            }
            log("[replica-host] rebuilding the graph ($trigger)")
            target.rebuild()
            return locked {
                val after = graph
                if (after === target) {
                    // The reinstall installed nothing (it threw, or it took another route).
                    publish(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "the rebuild installed no graph", willRetry = true))
                    false
                } else {
                    statusFlow.value.state == SharedReplicaHostState.READY
                }
            }
        } catch (t: Throwable) {
            log("[replica-host] the rebuild threw: ${t::class.simpleName}: ${t.message}")
            locked {
                if (graph === target) publish(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "the rebuild failed: ${t.message}", willRetry = true))
            }
            return false
        } finally {
            locked {
                rebuilding = false
                rebuildingFlow.value = false
            }
            // ⚠⚠ B.16 (231): a job that finished while this rebuild ran
            // (between its open's `Updating` and its graph's install) was told to a host that was
            // still showing the old state, or was rebuilding: it is latched, and a graph left
            // waiting on the stand-in for it is rebuilt now.
            val missed = locked {
                owed && graph != null && !unusable && !held &&
                    statusFlow.value.awaitsJob && finishedJobs != finishedAtStart &&
                    // ⚠ B.17 (287): the graph's install already replayed this finish.
                    finishedJobs != replayedFinishes
            }
            if (missed && isForeground()) schedule(Trigger.JOB_FINISHED)
        }
    }

    /** §6 step 2b's failure (222): the process latched unusable, the graph given up. */
    private fun latch(target: Graph) {
        locked {
            unusable = true
            owed = false
            publish(SharedReplicaHostStatus(SharedReplicaHostState.NOT_READY, "storage unavailable: the fenced handle could not be released", willRetry = false))
        }
        log("[replica-host] ⚠⚠ the fenced handle could not be released: the process is latched, nothing is rebuilt")
        abandon(target)
    }

    /** B.16 (238): the graph's own "no successor" teardown, once. Never throws. */
    private fun abandon(target: Graph) {
        val first = locked { (!target.abandoned).also { target.abandoned = true } }
        if (!first) return
        try {
            target.abandon()
        } catch (t: Throwable) {
            log("[replica-host] the graph's abandon threw: ${t.message}")
        }
    }

    /** Step 2, once per graph: its loops unbound, then the runtime's own stop. Never throws. */
    private fun stop(target: Graph) {
        val first = locked {
            val was = target.stopped
            target.stopped = true
            !was
        }
        if (!first) return
        try {
            afterStopClaimed?.invoke()
            try {
                unbindLoops(target.loops)
            } catch (t: Throwable) {
                log("[replica-host] stopping the loops threw: ${t.message}")
            }
            try {
                target.stop()
            } catch (t: Throwable) {
                log("[replica-host] the runtime's stop threw: ${t.message}")
            }
        } finally {
            target.stopDone.complete(Unit)
        }
    }

    /** Test seam (251): run between a stop's claim and its steps. Null in production. */
    @kotlin.concurrent.Volatile
    internal var afterStopClaimed: (() -> Unit)? = null

    private fun publish(status: SharedReplicaHostStatus) {
        statusFlow.value = status.withGeneration(generation)
        ensurePoll()
    }

    /** The current status again, as a new instance, so observers re-read what depends on the host (B.17 (280)). */
    private fun republish() {
        locked { statusFlow.value = statusFlow.value.fresh() }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    internal companion object {
        /** B.17 (261): the stand-in's re-open at most this often, one at a time. */
        const val REOPEN_PROBE_INTERVAL_MS: Long = 2_000L

        /** The detail of the status a "Try again" publishes (the surface shows it as the title). */
        const val TRYING_AGAIN: String = "Trying again…"

        /** B.17 (283): how long an entry waits for another trigger's rebuild to end. */
        const val ENTRY_WAITS_FOR_REBUILD_MS: Long = 10_000L

        /**
         * B.17 (260), (278): where "Try again" may be offered: a not-ready graph at once, and a
         * graph waiting on the host (the surface offers it there only after a delay).
         */
        val TRY_AGAIN_STATES = setOf(
            SharedReplicaHostState.NOT_READY,
            SharedReplicaHostState.REFRESHING,
            SharedReplicaHostState.UPDATING,
            SharedReplicaHostState.CHECKING,
        )

        /** The SQLite store, or null. A function of its own (K/N: no type test on a try's value). */
        fun sqliteStoreOf(store: ObjectStore): SharedReplicaStore? = store as? SharedReplicaStore

        /** The stand-in's status, or null. A function of its own (K/N). */
        fun notReadyStatusOf(store: ObjectStore): SharedReplicaHostStatus? = (store as? NotReadyReplicaStore)?.status
    }
}
