package org.tenshu.core.common.proxy.db.sqlite

import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The kinds of handle on the shared database (§2.8's table). The registry opens and closes the
 * connection of every one, and [releasedAtQuiesce] is what quiesce does to it, stated here once.
 *
 * Every kind is created: [SNAPSHOT] last, by Control's snapshot of a corrupt store
 * ([ReplicaDamagedSnapshot], Appendix B.18), the one handle that skips §3.1 step 0.
 */
internal enum class ReplicaHandleKind(val readOnly: Boolean, val releasedAtQuiesce: Boolean) {
    /** One per live graph: at quiesce its connection is closed and the handle kept. */
    RUNTIME(readOnly = false, releasedAtQuiesce = false),

    /** The open's read-only classification. */
    CLASSIFY(readOnly = true, releasedAtQuiesce = true),

    /** One checkpoint and its rewind write. */
    CHECKPOINT(readOnly = false, releasedAtQuiesce = true),

    /** The first chunk of a wipe with no live store. */
    STORELESS_WIPE(readOnly = false, releasedAtQuiesce = true),

    /** One run of a chunked migration or wipe. */
    REPLACEMENT(readOnly = false, releasedAtQuiesce = true),

    /** Control's drain and copy of a corrupt store ([ReplicaDamagedSnapshot]): `BEGIN IMMEDIATE`, `ROLLBACK`, `VACUUM INTO`, the backup, nothing else. */
    SNAPSHOT(readOnly = false, releasedAtQuiesce = true),
}

/**
 * One registered handle: its connection, opened only by [ReplicaHandles.connectionFor], and the
 * statements prepared on it. The logical handle (`SharedReplicaStore`) holds a reference and
 * never opens, closes or drops the connection itself.
 */
internal class ReplicaHandle(val kind: ReplicaHandleKind, val label: String) {
    internal var connection: SqliteConnection? = null
    internal var released = false

    /**
     * A release was asked for and its close failed: the handle is owed the rest of the release
     * (marked released, removed) as soon as its connection closes. Until then it is neither
     * usable nor gone.
     */
    internal var releasePending = false
    internal val statements = HashMap<String, SqliteStatement>()

    /** Run on the connection the registry has just opened, before anyone else sees it. */
    internal var onOpened: (SqliteConnection) -> Unit = {}

    /** Run after the registry closed the connection (quiesce, a release). */
    internal var onClosed: () -> Unit = {}

    /** Run when the registry released the handle: it may never ask for a connection again. */
    internal var onReleased: () -> Unit = {}

    /**
     * §4.2.1's 30 s foreground trigger for the logical handle on this one (Appendix B.12): set by
     * a `SharedReplicaStore`, null on every other handle. Called by
     * [ReplicaProtection.foregroundCheckpointTick] with no lock held; true when a checkpoint ran.
     */
    internal var onForegroundTick: (() -> Boolean)? = null

    val isReleased: Boolean get() = released
    val hasConnection: Boolean get() = connection != null

    /** A statement prepared once per connection and kept until it closes. */
    fun prepared(sql: String): SqliteStatement {
        val open = connection ?: error("[replica] no connection on handle '$label'")
        return statements.getOrPut(sql) { open.prepare(sql) }
    }
}

/** How a quiesce's drain ended (lease draft §5.3 step 4, §5 step 5). */
internal enum class ReplicaDrain {
    /** No admission is left: connections may be closed. */
    QUIESCED,

    /** An admission outlived the deadline: nothing is closed, and the process is expected to be killed. */
    TIMED_OUT,

    /** Protection came back while the drain waited: the quiesce is abandoned, nothing is closed. */
    REOPENED,
}

/** One admission still held, for the `TIMED OUT` log: who, and for how long. */
internal data class ReplicaAdmission(val label: String, val heldMs: Long)

/**
 * What a gate asks when a thread is refused: [ReplicaProtection]. A gate with none
 * attached refuses with [ReplicaGateClosed], which is every test and every process that never
 * installs protection.
 */
internal interface ReplicaGateProtection {
    /**
     * Called by a thread the gate refused, holding nothing (no admission, no registry lock).
     * Returns when the thread should try again: protection was obtained, or (main thread) a
     * retry is due, or (any other thread) the gate opened while it was parked.
     */
    fun awaitAdmission(gate: ReplicaGate, label: String)

    /**
     * The protected time left, in ms, for §4.2.1's admission checks; null when there is no
     * deadline (the foreground: there the backgrounding token supplies the time, §4.2.1).
     */
    fun remainingMs(): Long?

    /**
     * True exactly when [remainingMs] is null, **answered from the protection's own state and
     * never by asking the platform** (B.11 (153)): it is asked under the registry's lock and the
     * write lock (a migration chunk), and every 100 ms from the watchdog's queue (`CKPT_MAX`).
     */
    val isForeground: Boolean

    /**
     * The protection has been installed (lease draft §5.3 step 6): its gates are under rule P from
     * then on. Asked by the production open (Appendix B.13, Q24): a store attached to a protection
     * that was never installed is not under rule P yet. ⚠ No default: a stand-in says what it is.
     */
    val isInstalled: Boolean
}

/**
 * The in-process gate every outermost access passes (§4.1, §4.2 step 1).
 *
 * An admission count, [closeAndDrain] (stop admissions, wait for the ones in flight), and a
 * quiesce in three steps ([beginQuiesce], [drain], [endQuiesce]) that a [reopen] during the
 * drain abandons. What decides when it closes and opens is
 * [ReplicaProtection] (rule P, §4.4), attached as [protection]; with none attached the gate is
 * open unless a caller closes it, and a refused access throws [ReplicaGateClosed].
 */
internal class ReplicaGate {
    private val condition = NSCondition()
    private var open = true
    private var admitted = 0
    private var serial = 0L
    private var quiescing = false
    private var reopenOwed = false

    /** Monotonic ms past which the current or next drain stops waiting ([shortenDrain]); null for none. */
    private var drainCapMs: Long? = null

    /** Run once, off this gate's lock, when the admission count next reaches zero ([whenIdle]). */
    private var idleCallback: (() -> Unit)? = null
    private val origin = TimeSource.Monotonic.markNow()
    /** Every admission held now: its label, when, and the thread that holds it. */
    private class Held(val label: String, val since: TimeMark, val thread: NSThread)
    private val held = ArrayList<Held>()

    /**
     * Tests only: an unmatched [leave] fails with a `check` instead of being logged and ignored.
     * Off in production, where a throw from a `finally` would replace the access's own failure.
     */
    @Volatile
    internal var failOnUnmatchedLeave = false

    /** Run, off this gate's lock, each time the gate goes from closed to open (set by protection). */
    @Volatile
    internal var onOpened: (() -> Unit)? = null

    /** Set once by [ReplicaProtection.attach]; null: no rule P, the gate closes only when a caller closes it. */
    @Volatile
    internal var protection: ReplicaGateProtection? = null

    val isOpen: Boolean get() = locked { open }

    /** Between [beginQuiesce] and [endQuiesce]. */
    val isQuiescing: Boolean get() = locked { quiescing }

    /** How many admissions are held now. */
    val admittedCount: Int get() = locked { admitted }

    /** Moves on every admission: an idle gate whose serial has not moved has admitted nobody since. */
    val admissionSerial: Long get() = locked { serial }

    /** The admissions held now, oldest first. */
    fun heldAdmissions(): List<ReplicaAdmission> = locked { held.map { ReplicaAdmission(it.label, it.since.elapsedNow().inWholeMilliseconds) } }

    /** True if the calling thread holds an admission now (exact: each thread's own entries). */
    fun callerHoldsAdmission(): Boolean = locked {
        val me = NSThread.currentThread
        held.any { it.thread == me }
    }

    /** Admits one access, or refuses it because the gate is closed. Pair with [leave]. */
    fun enter(label: String = "unlabelled"): Boolean = locked {
        if (!open) return@locked false
        admitted++
        serial++
        held += Held(label, TimeSource.Monotonic.markNow(), NSThread.currentThread)
        true
    }

    fun leave() {
        val idle = locked {
            // Within one thread admissions nest and end innermost first, so the entry given back
            // is this thread's last one; that keeps [callerHoldsAdmission] exact per thread.
            // ⚠⚠ Every caller enters and leaves on one thread: each pair is in one non-suspend
            // frame, under the registry's lock (an NSRecursiveLock, thread-owned) or in a
            // try/finally. A leave with no entry of its own is therefore a bug,
            // and it changes NOTHING: not the count (a decrement would let a quiesce see zero
            // under the owner's live access, and the owner's own leave then drive it negative),
            // not another thread's entry (that would turn its callerHoldsAdmission false).
            val me = NSThread.currentThread
            val index = held.indexOfLast { it.thread == me }
            if (index < 0) {
                val why = "[gate] leave() by a thread that holds no admission: a bug; ignored, the count stays $admitted"
                check(!failOnUnmatchedLeave) { why }
                println(why)
                return@locked null
            }
            held.removeAt(index)
            admitted--
            if (admitted == 0) {
                condition.broadcast()
                idleCallback.also { idleCallback = null }
            } else {
                null
            }
        }
        idle?.invoke()
    }

    /**
     * Runs [callback] once, on the thread whose [leave] next brings the count to zero (or at once,
     * here, if it is zero already), outside this gate's lock. A timed-out quiesce's follow-up.
     */
    fun whenIdle(callback: () -> Unit) {
        val now = locked {
            if (admitted == 0) {
                true
            } else {
                idleCallback = callback
                false
            }
        }
        if (now) callback()
    }

    /**
     * Closes the gate and waits up to [timeoutMs] for every admitted access to leave. True when
     * none is left: only then may a caller close connections. Not abandoned by [reopen]: that is
     * the quiesce's ([beginQuiesce]).
     */
    fun closeAndDrain(timeoutMs: Long): Boolean = locked {
        open = false
        val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
        while (admitted > 0) {
            if (!condition.waitUntilDate(deadline)) break
        }
        admitted == 0
    }

    /**
     * Quiesce step 4, first half: closed from this instant. A [reopen] from now until
     * [endQuiesce] is owed rather than applied, so the connections quiesce closes are never
     * closed under an access the reopened gate admitted.
     */
    fun beginQuiesce() = locked {
        open = false
        quiescing = true
        // Every caller has just seen the process unprotected under the state lock, so a
        // reopen owed now belongs to protection that is gone. One still valid is re-applied by the
        // shutdown's step-2 and step-4 re-checks.
        reopenOwed = false
        // N1: a cut left from an earlier shutdown never shortens this one. A cut for this
        // quiesce is applied after this call, by its owner ([shortenDrain]).
        drainCapMs = null
    }

    /**
     * Waits up to [timeoutMs] for the admissions to leave, or for a reopen to be owed, or until a
     * [shortenDrain] deadline passes, whichever comes first.
     */
    fun drain(timeoutMs: Long): ReplicaDrain = locked {
        val deadlineMs = nowMs() + timeoutMs
        while (admitted > 0 && !reopenOwed) {
            val until = drainCapMs?.let { minOf(it, deadlineMs) } ?: deadlineMs
            val left = until - nowMs()
            if (left <= 0) break
            condition.waitUntilDate(NSDate.dateWithTimeIntervalSinceNow(left / 1000.0))
        }
        when {
            reopenOwed -> ReplicaDrain.REOPENED
            admitted == 0 -> ReplicaDrain.QUIESCED
            else -> ReplicaDrain.TIMED_OUT
        }
    }

    /**
     * Cuts the drain of the quiesce under way to at most [ms] from now: an expiry arriving while
     * its own release's quiesce runs (B.5.2 (69)). Called only by that quiesce's owner, after its
     * [beginQuiesce]; cleared by the next [beginQuiesce] and by [endQuiesce].
     */
    fun shortenDrain(ms: Long) = locked {
        val at = nowMs() + ms
        drainCapMs = drainCapMs?.let { minOf(it, at) } ?: at
        condition.broadcast()
    }

    fun clearDrainCap() = locked { drainCapMs = null }

    /** The quiesce is over: an owed reopen is applied now. */
    fun endQuiesce() {
        val opened = locked {
            quiescing = false
            drainCapMs = null
            val was = open
            if (reopenOwed) {
                reopenOwed = false
                open = true
            }
            condition.broadcast()
            !was && open
        }
        if (opened) onOpened?.invoke()
    }

    fun reopen() {
        val opened = locked {
            val was = open
            if (quiescing) reopenOwed = true else open = true
            // A timed-out quiesce's follow-up is moot once the gate is protected again.
            idleCallback = null
            condition.broadcast()
            !was && open
        }
        if (opened) onOpened?.invoke()
    }

    private fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds

    /** Waits up to [timeoutMs] for the gate to be open. True if it is. */
    fun awaitOpen(timeoutMs: Long): Boolean = locked {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
        while (!open) {
            if (!condition.waitUntilDate(deadline)) break
        }
        open
    }

    /**
     * Parks the calling thread until the gate opens, holding nothing. After [logAfterMs] parked,
     * [onLongPark] is called once (`[gate] parked without protection`).
     */
    fun park(logAfterMs: Long, onLongPark: () -> Unit) = locked {
        var logged = false
        while (!open) {
            val deadline = NSDate.dateWithTimeIntervalSinceNow(logAfterMs / 1000.0)
            if (!condition.waitUntilDate(deadline) && !open && !logged) {
                logged = true
                onLongPark()
            }
        }
    }

    /**
     * For a thread [enter] refused: ask [protection] and return when it should try again, or,
     * with none attached, throw [ReplicaGateClosed]. ⚠ The caller holds nothing, the registry's
     * lock included (S7: a thread waiting holds nothing).
     */
    fun awaitAdmission(label: String) {
        val protection = protection ?: throw ReplicaGateClosed("[replica] $label: the gate is closed")
        protection.awaitAdmission(this, label)
    }

    /**
     * Runs [block] inside one admission (C6): an open, a store-less wipe. Refused, it waits for
     * protection as an access does ([awaitAdmission]); with no protection attached a closed gate
     * throws [ReplicaGateClosed], before [block] touches anything. ⚠ The caller holds no lock.
     */
    fun <T> admittedFor(label: String, operation: ReplicaOperation? = null, limits: ReplicaLimits? = null, block: () -> T): T {
        while (!enter(label)) awaitAdmission(label)
        try {
            if (operation != null && limits != null) refusal(operation, limits, opensConnection = true)?.let { throw ReplicaAdmissionDeferred("[replica] $label: $it") }
            return block()
        } finally {
            leave()
        }
    }

    /**
     * §4.2.1's admission check for [operation]: null when it may start, else why not. With no
     * protection attached, or no deadline (the foreground), every operation is admitted.
     */
    /** The protected time left, or null when there is no deadline (the foreground, or no protection attached). */
    fun remainingMs(): Long? = protection?.remainingMs()

    /**
     * ⚠⚠ The store's one statement of "in the foreground" (S13, §4.2.1): no deadline. Asked by a
     * migration chunk (Appendix B.11 (146)), by `CKPT_MAX` when it passes (B.10 (143)) and by the
     * end-of-job checkpoint (B.11 (148)). ⚠⚠ From the protection's state, never the platform's
     * `backgroundTimeRemaining` (B.11 (153)): it is asked under the registry's lock and the write
     * lock, so a platform call here could deadlock against a main thread waiting on either.
     *
     * ⚠⚠ **With no protection attached this is TRUE**, as `remainingMs` is null: every rule that
     * is lifted in the foreground is lifted for a process with no protection. So every process that
     * can reach the store in the background (an app extension included) must attach a
     * `ReplicaProtection`, or it gets no `CKPT_MAX`, no admission check and no refusal of a
     * migration chunk at all (a binding constraint, B.11.4).
     */
    fun inForeground(): Boolean = protection?.isForeground ?: true

    fun refusal(operation: ReplicaOperation, limits: ReplicaLimits, opensConnection: Boolean): String? {
        val remaining = protection?.remainingMs() ?: return null
        val required = ReplicaAdmissionBudget.requiredMs(operation, limits, opensConnection)
        return if (remaining >= required) null else "${operation.name} needs $required ms of protected time, $remaining ms are left: deferred"
    }

    private inline fun <T> locked(block: () -> T): T {
        condition.lock()
        try {
            return block()
        } finally {
            condition.unlock()
        }
    }
}

/**
 * ⚠⚠ **`replica/CORRUPT` is written inside a gate admission, or not now.** Quiesce waits for a
 * marker write as for any other operation (§4.2.1), and the only thing it waits for is the
 * admission count: a write begun outside one could still be at its `F_FULLFSYNC` after
 * `closeAndDrain` answered "drained". A caller already inside an admission (an access, a job's
 * transaction, the open's write transaction) says so and writes under it, also when the gate has
 * begun to close; any other takes one here, and with the gate closed the marker is **not
 * written**: [ReplicaFileException], which every caller already treats as "could not be
 * written", a retry, and the next process to meet the damage writes it.
 */
internal inline fun ReplicaHandles.admittedForMarker(alreadyAdmitted: Boolean, write: () -> Unit) {
    if (alreadyAdmitted) return write()
    if (!gate.enter()) throw ReplicaFileException("the gate is closed: replica/CORRUPT is not written now")
    try {
        write()
    } finally {
        gate.leave()
    }
}

/**
 * The registry's in-process recursive lock, counting how deep the **owning** thread holds it.
 * [holdDepth] is written only by the thread that holds the lock, so it is exact when read by that
 * thread and meaningless to any other. [acquire] asserts it is 1 before it releases the lock to
 * wait or sleep: a caller holding it twice would park with it still held and deadlock quiesce,
 * which now fails loudly instead (B.5.2 (75)).
 */
internal class ReplicaRegistryLock : NSRecursiveLock() {
    var holdDepth = 0
        private set

    override fun lock() {
        super.lock()
        holdDepth++
    }

    override fun tryLock(): Boolean {
        if (!super.tryLock()) return false
        holdDepth++
        return true
    }

    /** True if the calling thread holds this lock now (a recursive try succeeds only for its owner or a free lock). */
    fun isHeldByCurrentThread(): Boolean {
        if (!tryLock()) return false
        try {
            return holdDepth > 1
        } finally {
            unlock()
        }
    }

    override fun unlock() {
        holdDepth--
        super.unlock()
    }
}

/**
 * The process-wide registry of handles on one database path (§2.8, S18): every connection to the
 * shared database is opened and closed here, so none can outlive the graph that used it.
 *
 * - [lock] is the in-process recursive lock that serialises threads onto the path's connections.
 *   Every method here takes it.
 * - **Close the connection** ([closeConnection]): roll back if a transaction is open, finalize
 *   every statement, close. The handle stays registered and reopens on its next access.
 * - **Release the handle** ([release]): close its connection, mark it released (it can never ask
 *   for a connection again), remove it.
 * - A failed rollback or close latches the registry [isUnusable]: nothing is opened in this
 *   process until [retryUnusable] succeeds or the process restarts. ⚠ The latch remembers **what
 *   the failed close was for**: a close that belonged to a release leaves the handle
 *   [ReplicaHandle.releasePending], and whichever close of it next succeeds (the retry, a quiesce,
 *   a second release) finishes that release, not only the close.
 *
 * One instance per path per process, from [forPath]. A test builds a second instance on the same
 * path to stand in for another process: SQLite's locks, not this registry, are what excludes
 * processes from each other.
 */
internal class ReplicaHandles(val databasePath: String, val limits: ReplicaLimits) {

    val lock = ReplicaRegistryLock()
    val gate = ReplicaGate()

    /** `HOLD_MAX` on every write transaction of this registry's handles (§4.2.1, Appendix B.9). */
    val holdWatchdog = ReplicaHoldWatchdog()

    private val registered = ArrayList<ReplicaHandle>()

    /** Closes refused by a closed gate under protection, owed to the next one allowed. */
    private val deferredCloses = LinkedHashSet<ReplicaHandle>()

    /** True while [closeAllForQuiesce] runs: read and written only under [lock]. */
    private var closingForQuiesce = false

    /** The handles whose close is owed now (for tests). */
    fun deferredCloseCount(): Int = locked { deferredCloses.size }
    private val stuck = LinkedHashSet<ReplicaHandle>()

    /** Fault injection: called with the handle just before its `BEGIN IMMEDIATE`; a throw is that statement's failure. Null in production. */
    internal var beginFault: ((ReplicaHandle) -> Unit)? = null

    /** Test seam: between a close's refusal by the gate and its queueing as deferred. Null in production. */
    internal var beforeDeferQueued: (() -> Unit)? = null

    /** Fault injection for the latch's tests: called with the handle inside its close. Null in production. */
    internal var closeFault: ((ReplicaHandle) -> Unit)? = null

    /** Test seam: called in a confirmation walk's run, after its marker lookup and pause, before it reads ([ReplicaConfirmationWalk]). Null in production. */
    internal var beforeConfirm: (() -> Unit)? = null

    /** Fault injection: called inside a confirmation walk's reading; a throw is that reading's failure. Null in production. */
    internal var confirmFault: (() -> Unit)? = null

    /** Test seam: called at each hand-off of a corruption code to its walk ([ReplicaCorruptionConfirm]). Null in production. */
    internal var onHandOff: (() -> Unit)? = null

    /**
     * Called with a walk each time a code is handed to it, and when an open finds one owed: the
     * worker's [ReplicaWorker.schedule], set by [ReplicaWorker.attach]. ⚠ Called under this
     * registry's lock: it may only dispatch. Null while no worker is attached (the tests that run
     * a walk by hand).
     */
    @Volatile
    internal var onWalkRequested: ((ReplicaConfirmationWalk) -> Unit)? = null

    /**
     * Called with the job an open answered `Updating` with (Appendix B.11): the worker's
     * [ReplicaWorker.scheduleReplacement], set by [ReplicaWorker.attach]. ⚠ It may only dispatch:
     * the open still holds its admission. Null while no worker is attached (the host or a test runs
     * the job itself).
     */
    @Volatile
    internal var onReplacementRequested: ((ReplicaReplacement) -> Unit)? = null

    private val walks = HashMap<String, ReplicaConfirmationWalk>()

    /**
     * This process's confirmation walk for [appName] on this path: one per app, made on first use
     * (Appendix B.8), judging the store against [build] (Q16, Appendix B.10). ⚠ A process runs one
     * build, so the first caller's is kept: a later caller's is not looked at.
     */
    fun confirmationWalk(appName: String, files: ReplicaFiles, build: ReplicaBuild, protectedDataAvailable: () -> Boolean): ReplicaConfirmationWalk = locked {
        walks.getOrPut(appName) { ReplicaConfirmationWalk(this, files, appName, build, protectedDataAvailable) }
    }

    /** Milliseconds of a monotonic clock, for the second reading's deadline. A test moves it by hand. */
    internal var confirmClock: () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } }

    /**
     * How often the second reading asks whether to stop: every 1,000 steps, which in
     * `quick_check`'s walk is every 1,000 pages, about 21 ms cold. A test asks at every step.
     */
    internal var confirmAskEvery: Int = 1_000

    /**
     * Checkpoints of **this process**, in a row, that answered a corruption code (B.3 (52)): the
     * runtime handle's and every job's alike, since they all checkpoint through this registry.
     * A checkpoint that ran to its end without one starts the count again; one that did not run
     * (busy, an I/O error, the gate, protected data unavailable) neither counts nor resets. In
     * memory only: a new process starts at zero, and a sibling's checkpoints are never seen.
     */
    var checkpointCodesInARow: Int = 0
        private set

    /** A checkpoint answered a corruption code. Returns how many now have, in a row. */
    fun checkpointAnsweredCorruption(): Int = locked { ++checkpointCodesInARow }

    /** A checkpoint ran to its end and SQLite found nothing wrong. */
    fun checkpointRan() = locked {
        checkpointCodesInARow = 0
        checkpointCeilingStopsInARow = 0
    }

    /**
     * Checkpoints of this process, in a row, stopped at `CKPT_MAX` (Appendix B.10 (138)): each one
     * kept none of its copy, so a count that keeps rising is a log this device cannot reset in
     * the background. For the host's surface; reset by a checkpoint that runs to its end.
     */
    var checkpointCeilingStopsInARow: Int = 0
        private set

    /** A checkpoint was stopped at `CKPT_MAX`. Returns how many now have, in a row. */
    fun checkpointStoppedAtCeiling(): Int = locked { ++checkpointCeilingStopsInARow }

    /**
     * Opens of this process, in a row, refused at `HOLD_MAX` (their classification or their write
     * transaction; Appendix B.10 (140)). For the host's surface, as a stalled walk is ((115)): an
     * open that is a retry every time is otherwise silent. Reset by an open that is served.
     */
    var openHoldRefusalsInARow: Int = 0
        private set

    fun openRefusedAtHoldMax(): Int = locked { ++openHoldRefusalsInARow }

    fun openServed() = locked { openHoldRefusalsInARow = 0 }

    /** A registered handle whose connection has a transaction open now, or null: none of this process holds the write lock. */
    fun transactionHolder(): ReplicaHandle? = locked { registered.firstOrNull { it.connection?.inAutocommit() == false } }

    /** True once a rollback or a close has failed and not yet been retried successfully. */
    val isUnusable: Boolean get() = locked { stuck.isNotEmpty() }

    /** The handles registered now, for quiesce and for tests. */
    fun handles(): List<ReplicaHandle> = locked { registered.toList() }

    /**
     * Registers a handle. ⚠ At most one [ReplicaHandleKind.RUNTIME] handle per path: a rebuild
     * waits for the fenced one's release (§2.8), so a second is a programming error.
     */
    fun register(kind: ReplicaHandleKind, label: String): ReplicaHandle = locked {
        closeDeferred()
        if (kind == ReplicaHandleKind.RUNTIME) {
            // A runtime handle whose release is owed, its close still refused by a closed gate: a
            // retryable state, not a programming error.
            if (registered.any { it.kind == ReplicaHandleKind.RUNTIME && it.releasePending && it in deferredCloses }) {
                throw StoreUnavailable("[replica] the previous runtime handle's release is still owed: the gate is closed")
            }
            check(registered.none { it.kind == ReplicaHandleKind.RUNTIME }) {
                "[replica] a runtime handle is already registered on $databasePath"
            }
        }
        ReplicaHandle(kind, label).also { registered += it }
    }

    /**
     * The handle's connection, opened if it is closed: `sqlite3_open_v2` through the driver (which
     * suppresses the close-time checkpoint before any statement), then the per-connection settings
     * of §2.3.1, then the handle's own [ReplicaHandle.onOpened]. A connection that fails any of it
     * is closed again and the failure thrown.
     */
    fun connectionFor(handle: ReplicaHandle): SqliteConnection = locked {
        closeDeferred()
        check(!handle.released) { "[replica] handle '${handle.label}' was released" }
        if (stuck.isNotEmpty()) throw StoreUnavailable("[replica] the process is latched unusable after a failed close")
        check(!handle.releasePending) { "[replica] handle '${handle.label}' is being released" }
        handle.connection?.let { return@locked it }
        // ⚠ The SNAPSHOT handle never creates the file it is to preserve (B.18.8 (310)).
        val connection = SqliteConnection.open(databasePath, readOnly = handle.kind.readOnly, create = handle.kind != ReplicaHandleKind.SNAPSHOT)
        try {
            // Not persistent, so set on every open, the reopen after quiesce included.
            connection.exec("PRAGMA busy_timeout = ${limits.busyTimeoutMs}")
            connection.exec("PRAGMA cache_size = ${limits.cachePages}")
            if (!handle.kind.readOnly) {
                connection.exec("PRAGMA synchronous = FULL")
                connection.exec("PRAGMA wal_autocheckpoint = 0")
                connection.exec("PRAGMA journal_size_limit = ${limits.journalSizeLimitBytes}")
            }
            handle.connection = connection
            handle.onOpened(connection)
        } catch (t: Throwable) {
            handle.connection = null
            handle.statements.clear()
            connection.close()
            throw t
        }
        connection
    }

    /**
     * Closes the handle's connection. The handle is kept, unless a release is owed to it
     * ([ReplicaHandle.releasePending]), which this then finishes. A no-op on a closed connection
     * of a handle that is owed nothing.
     */
    fun closeConnection(handle: ReplicaHandle) = locked {
        val connection = handle.connection
        if (connection != null) {
            // ⚠⚠ A close is a touch of SQLite (a rollback if a transaction is open, the
            // statements, the handle), so it is counted by the gate like any access: under its
            // caller's admission when there is one (the count nests), under one of its own
            // otherwise, **whoever closes and why** (a fence, a release, the end of a job's run,
            // a store's `close()`, a failed acquisition). Only with the gate closed is there none
            // to take, and then the close belongs to quiesce, which closes every connection
            // itself once the gate has drained; a close met here in that state is that same
            // close, serialised with quiesce's by this registry's lock.
            val admitted = gate.enter()
            // ⚠ Only a caller holding NO admission is deferred. One inside an admission (a fence,
            // an opener's finally, acquire's cleanup) is counted already, by the gate the drain
            // waits on, so its close is made now: deferring it would leave a connection, perhaps
            // with its write transaction, open after the gate reopens.
            if (!admitted && gate.protection != null && !closingForQuiesce && !gate.callerHoldsAdmission()) {
                // ⚠ With protection attached, a closed gate with no quiesce
                // closing means quiesce timed out (its follow-up still owed) or has not run yet:
                // the assertion may already have ended. No SQLite call is made now. The close is
                // owed: to the follow-up or the next quiesce (which close every handle), or to the
                // next admitted call of this registry ([closeDeferred]). A release stays pending.
                beforeDeferQueued?.invoke()
                deferredCloses += handle
                println("[replica] the close of '${handle.label}' is deferred: the gate is closed and no quiesce is closing")
                return@locked
            }
            try {
                closeFault?.invoke(handle)
                if (!connection.inAutocommit()) connection.exec("ROLLBACK")
                handle.statements.values.forEach { it.close() }
                handle.statements.clear()
                connection.close()
                handle.connection = null
            } catch (t: Throwable) {
                // The handle stays registered with its connection, so nothing becomes unreachable.
                stuck += handle
                println("[replica] FAILED to close the connection of '${handle.label}': ${t.message}; the process is unusable")
                throw StoreUnavailable("[replica] could not close a connection", t)
            } finally {
                if (admitted) gate.leave()
            }
        }
        connectionIsClosed(handle, wasOpen = connection != null)
    }

    /**
     * ⚠⚠ The one place a handle goes from "closing" to "closed", whoever asked for the close (a
     * release, quiesce, a retry of the latch, a second release): it leaves the latch, and a
     * release that was owed is finished here. Every close path ends in [closeConnection], which
     * ends here, so none can close a handle and forget what the close was for.
     */
    private fun connectionIsClosed(handle: ReplicaHandle, wasOpen: Boolean) {
        stuck.remove(handle)
        if (wasOpen) handle.onClosed()
        if (handle.releasePending) {
            handle.releasePending = false
            handle.released = true
            registered.remove(handle)
            handle.onReleased()
        }
    }

    /**
     * Closes the handle's connection, marks it released and removes it. Idempotent. If the close
     * fails the release is not forgotten: the handle stays registered and
     * [ReplicaHandle.releasePending], and whichever close of it next succeeds finishes the release.
     */
    fun release(handle: ReplicaHandle) = locked {
        if (handle.released) return@locked
        handle.releasePending = true
        closeConnection(handle)
    }

    /**
     * Retries every close that latched the registry. True when none is left and the process is
     * usable again.
     */
    fun retryUnusable(): Boolean = locked {
        for (handle in stuck.toList()) {
            try {
                closeConnection(handle)
            } catch (_: StoreUnavailable) {
                // Still stuck; the others are tried all the same.
            }
        }
        stuck.isEmpty()
    }

    /**
     * §2.8's table applied to every registered handle: a runtime handle's connection is closed
     * and the handle kept, every job handle is released. ⚠ The caller has closed and drained
     * [gate] first, so no access is in flight: [ReplicaProtection]'s quiesce.
     */
    fun closeAllForQuiesce() = locked {
        // Every handle is attempted: one that fails to close must not leave the others open
        // across a suspension. The first failure is thrown once all have been tried.
        var failure: StoreUnavailable? = null
        // The owner of the closes, for closeConnection: the one closer a closed gate admits.
        closingForQuiesce = true
        try {
            for (handle in registered.toList()) {
                try {
                    if (handle.kind.releasedAtQuiesce) release(handle) else closeConnection(handle)
                } catch (e: StoreUnavailable) {
                    if (failure == null) failure = e
                }
            }
            deferredCloses.clear()
        } finally {
            closingForQuiesce = false
        }
        failure?.let { throw it }
    }

    /**
     * The closes [closeConnection] deferred, attempted again now: each is made if the gate admits
     * it, and stays owed otherwise. Run at the start of [register] and [connectionFor].
     */
    /** [closeDeferred] under the registry's lock: what the gate's [ReplicaGate.onOpened] dispatches. */
    fun closeDeferredNow() = locked { closeDeferred() }

    private fun closeDeferred() {
        if (deferredCloses.isEmpty()) return
        for (handle in deferredCloses.toList()) {
            deferredCloses.remove(handle)
            if (handle.released || handle !in registered) continue
            try {
                closeConnection(handle)
            } catch (_: StoreUnavailable) {
                // Latched and logged by closeConnection.
            }
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private val registriesLock = NSRecursiveLock()
        private val registries = HashMap<String, ReplicaHandles>()

        /** The process's registry for [databasePath], created on first use. */
        fun forPath(databasePath: String, limits: ReplicaLimits): ReplicaHandles {
            registriesLock.lock()
            try {
                return registries.getOrPut(databasePath) { ReplicaHandles(databasePath, limits) }
            } finally {
                registriesLock.unlock()
            }
        }
    }
}
