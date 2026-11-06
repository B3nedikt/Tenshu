@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_INTERRUPT
import platform.Foundation.NSLock
import platform.Foundation.NSRecursiveLock
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_time
import kotlin.time.TimeSource

/**
 * The process's one serial queue for the hold watchdog (§4.2.1): every deadline of every
 * transaction fires here, and so does every re-interrupt, the token-expiry interrupt's included
 * (Appendix B.15). The expiry's first interrupt is issued on the expiring thread (the platform's
 * expiration handler, on main), where it must land before the drain: `sqlite3_interrupt` is safe
 * from any thread, and every one is issued under the watch's lock (B.9 (124)).
 */
internal object ReplicaWatchdogQueue {
    private val queue = dispatch_queue_create("org.tenshu.replica.hold-watchdog", null)

    fun after(delayMs: Long, block: () -> Unit) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delayMs * 1_000_000), queue, block)
    }
}

/**
 * ⚠⚠ **The hold watchdog** (§4.2.1, S8; Appendix B.9): what enforces `HOLD_MAX` on one
 * transaction while protection is plentiful, where the chunk loops' stops and the budget are
 * asked only **between** statements.
 *
 * - **Armed when a transaction obtains its write lock** ([arm], right after `BEGIN IMMEDIATE`
 *   returned), with a deadline of `HOLD_MAX`.
 * - **Fired while a statement runs**: `sqlite3_interrupt` on that connection. The statement
 *   fails with `SQLITE_INTERRUPT`, and its caller rolls back and fences (rule (a)) or, for a job's
 *   chunk, rolls back and stops the run.
 * - **Fired between statements**: the hold is marked exceeded, and **the next statement, `COMMIT`
 *   included, is refused** with [HoldCeilingExceeded] before it reaches SQLite. An interrupt
 *   with no statement running would do nothing.
 * - **Disarmed once `COMMIT` is issued** ([ReplicaHold.beforeCommit]), or the rollback returns
 *   ([ReplicaHold.disarm]). The `COMMIT`'s sync is never interrupted (R2).
 *
 * One per registry ([ReplicaHandles.holdWatchdog]). Its timer cannot be cancelled: a deadline that
 * fires for a hold already disarmed does nothing.
 *
 * ⚠⚠ **And the token-expiry interrupt** (Appendix B.15): [expireAll] treats every hold armed now,
 * and every hold armed until [endExpiry], as past its deadline at once, whatever its deadline,
 * its exempt window or its `deferWhile` say: a running statement is interrupted (and again every
 * [REINTERRUPT_MS] while it runs), the next statement and the `COMMIT` are refused with
 * [ProtectionExpired]. A `COMMIT` already issued is not armed any more and finishes (its sync is
 * R2). A job's own token reaches only its own run's holds, through [ReplicaExpiryScope].
 */
internal class ReplicaHoldWatchdog {
    /** Runs a block once after a delay, on the watchdog's queue. A test runs them by hand. */
    internal var after: (Long, () -> Unit) -> Unit = ReplicaWatchdogQueue::after

    /** Milliseconds of a monotonic clock. */
    internal var clock: () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } }

    private val lock = NSLock()
    private var interruptsIssued = 0
    private var armedNow = 0
    private var runningNow = 0

    /** Every hold armed and not yet disarmed: what [expireAll] reaches. */
    private val live = LinkedHashSet<ReplicaHold>()

    /** Set by [expireAll] until [endExpiry]: a hold armed meanwhile is expired as it is armed. */
    private var expiring: String? = null
    private var expiriesIssued = 0

    /** Test seam: every `sqlite3_interrupt` this watchdog has issued, re-interrupts included. */
    internal val interrupts: Int get() = locked { interruptsIssued }

    /** Test seam: holds armed and not yet disarmed. */
    internal val armed: Int get() = locked { armedNow }

    /** Test seam: statements marked running, over every hold. */
    internal val running: Int get() = locked { runningNow }

    /** Test seam: holds the token-expiry interrupt has reached, re-reaches not counted. */
    internal val expiries: Int get() = locked { expiriesIssued }

    /** True from [expireAll] until [endExpiry]. */
    internal val isExpiring: Boolean get() = locked { expiring != null }

    internal fun statementsRunning(delta: Int) = locked { runningNow += delta }

    /**
     * Arms `HOLD_MAX` ([limitMs]) for the transaction [label] that has just obtained its lock on
     * [connection]. [bound] and [holding] name the deadline and what it bounds in the log: the
     * same watch serves the open's read-only classification ("a read mark") and a checkpoint's
     * own `CKPT_MAX` (Appendix B.10), neither of which holds the write lock.
     */
    fun arm(
        label: String,
        connection: SqliteConnection,
        limitMs: Int,
        bound: String = "HOLD_MAX",
        holding: String = "the write lock",
        /**
         * Asked when the deadline passes (B.10 (143)): true defers it, and it is asked again every
         * [DEFERRED_RECHECK_MS]; the clock checks defer likewise. Null (every write transaction):
         * `HOLD_MAX` holds in every state. A checkpoint's `CKPT_MAX` passes "still in the
         * foreground", so the bound is decided when it passes, not when it was armed.
         */
        deferWhile: (() -> Boolean)? = null,
    ): ReplicaHold {
        val hold = ReplicaHold(this, label, connection, limitMs, clock, bound, holding, deferWhile)
        // ⚠ Registered and the expiry read in one step: an expireAll either sees this hold or has
        // set `expiring` first, so no hold armed during an expiry escapes it (B.15 (201)).
        val expiringNow = locked {
            armedNow++
            live += hold
            expiring
        }
        after(limitMs.toLong()) { hold.fire() }
        // A job's own token (B.15 (203)): the run's holds are its scope's, on the run's thread.
        ReplicaExpiryScope.current?.register(hold)
        expiringNow?.let(hold::expire)
        return hold
    }

    /**
     * ⚠⚠ **The token-expiry interrupt, for every hold of this store in this process** (Appendix
     * B.15 (200)): the protection has just ended with nothing left to cover a lock, so suspension
     * is at hand (`0xdead10cc`). Every hold armed now is expired ([ReplicaHold.expire]), and every
     * one armed until [endExpiry] is expired as it is armed. The count reached. Never blocks: one
     * `sqlite3_interrupt` per running statement, under each hold's own lock.
     */
    fun expireAll(why: String): Int {
        val holds = locked {
            expiring = why
            live.toList()
        }
        holds.forEach { it.expire(why) }
        return holds.size
    }

    /** The quiesce that issued [expireAll] has ended: holds armed from now on are ordinary again. */
    fun endExpiry() = locked { expiring = null }

    internal fun interrupted() = locked { interruptsIssued++ }

    internal fun expired() = locked { expiriesIssued++ }

    internal fun disarmed(hold: ReplicaHold) = locked {
        armedNow--
        live -= hold
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
        /**
         * ⚠ Placeholder: how soon an interrupt is issued again while the statement it was meant
         * for still runs. SQLite clears the flag when a statement starts with none active
         * (`sqlite3Step`, `sqlite3RunParser`), so an interrupt that lands between the store
         * marking a statement as running and SQLite starting it is lost; this repeats it until the
         * statement returns (B.9 (124)).
         */
        const val REINTERRUPT_MS = 50L

        /**
         * ⚠ Placeholder (B.10 (143)): how often a deadline deferred by `deferWhile` is asked again.
         * The longest a checkpoint that has passed `CKPT_MAX` runs on once the app is backgrounded.
         */
        const val DEFERRED_RECHECK_MS = 100L

        /** The long-hold log (§4.2.1): a transaction that held its lock this long is named. */
        const val LONG_HOLD_LOG_MS = 500L
    }
}

/**
 * ⚠⚠ **A job's own token reaching its own run** (Appendix B.15 (203); B.11 split it off): the
 * holds a job arms on its thread while [around] runs (its chunks' watches, the checkpointer's
 * `CKPT_MAX` watch, the end-of-job checkpoint's) are this scope's, and [expire], called from that
 * job token's expiration handler, expires each of them, and any armed afterwards inside the scope,
 * whether or not something else still protects the process. A job runs synchronously on one
 * thread (the worker's queue), so the thread is the attribution; an entry's coroutine hops threads
 * and so has no scope: its accesses are reached only when protection ends ([ReplicaHoldWatchdog.expireAll]).
 */
internal class ReplicaExpiryScope(private val why: String) {
    private val lock = NSLock()
    private val holds = ArrayList<ReplicaHold>()
    private var expired = false

    /** True once [expire] has run. */
    val isExpired: Boolean get() = locked { expired }

    fun register(hold: ReplicaHold) {
        val now = locked {
            if (!expired) {
                holds.removeAll { !it.isArmedNow }
                holds += hold
            }
            expired
        }
        if (now) hold.expire(why)
    }

    /** The job's token expired: every hold of the run, now and from now on. Never throws, never blocks. */
    fun expire() {
        val reached = locked {
            expired = true
            holds.toList().also { holds.clear() }
        }
        reached.forEach { it.expire(why) }
    }

    /** [block] with this scope current on the calling thread; the previous one restored after. */
    fun <T> around(block: () -> T): T {
        val previous = currentScope
        currentScope = this
        try {
            return block()
        } finally {
            currentScope = previous
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
        /** The scope of the job running on the calling thread, or null. */
        val current: ReplicaExpiryScope? get() = currentScope
    }
}

@kotlin.native.concurrent.ThreadLocal
private var currentScope: ReplicaExpiryScope? = null

/** One transaction's watch (see [ReplicaHoldWatchdog]). Used by the transaction's own thread and the watchdog's queue. */
internal class ReplicaHold internal constructor(
    private val watchdog: ReplicaHoldWatchdog,
    val label: String,
    private val connection: SqliteConnection,
    val limitMs: Int,
    private val clock: () -> Long,
    private val bound: String = "HOLD_MAX",
    private val holding: String = "the write lock",
    private val deferWhile: (() -> Boolean)? = null,
) {
    // Recursive: [heldMs] is read inside this lock (the refusals' errors, the fire's log).
    private val lock = NSRecursiveLock()
    private val armedAt = clock()
    private var isArmed = true
    private var exceeded = false
    private var running = 0
    private var interruptedRunning = false

    /** Why the token-expiry interrupt reached this hold (B.15), or null: its refusals are then [ProtectionExpired]. */
    private var expiredBy: String? = null

    /** Inside an [exempt] window since this time, or null. */
    private var exemptSince: Long? = null

    /** Every closed [exempt] window's time, which the hold does not count. */
    private var exemptTotal = 0L

    /** Exempt time the timer has not yet been pushed back by. */
    private var extensionOwed = 0L

    /** The timer fired inside an [exempt] window: it is armed again when the window ends. */
    private var firedWhileExempt = false

    /** How long the lock has been held, less the [exempt] windows. */
    val heldMs: Long get() = locked { clock() - armedAt - exemptTotal - (exemptSince?.let { clock() - it } ?: 0L) }

    /** True once the watchdog interrupted a statement of this hold: a `SQLITE_INTERRUPT` from it is the watchdog's. */
    val interruptedAStatement: Boolean get() = locked { interruptedRunning }

    /** True while armed: not yet disarmed by its `COMMIT` or its rollback. */
    val isArmedNow: Boolean get() = locked { isArmed }

    /**
     * The refusal, with [cause] when SQLite's own `SQLITE_INTERRUPT` was the way it showed:
     * [ProtectionExpired] once the token-expiry interrupt reached this hold, else `HOLD_MAX`'s.
     */
    fun exceededError(cause: Throwable? = null): HoldCeilingExceeded = locked {
        expiredBy?.let { ProtectionExpired(it, heldMs, limitMs, cause) } ?: HoldCeilingExceeded(heldMs, limitMs, cause)
    }

    /**
     * A statement of this transaction begins. ⚠⚠ **Refused once the deadline has passed**, by the
     * timer (it fired between statements) or by the clock (a late timer is not trusted to be on
     * time): the between-statements half. From here until [exitStatement] the watchdog interrupts
     * it. ⚠ Always paired with [exitStatement] in a `finally`.
     */
    fun enterStatement() {
        locked {
            if (exceeded) throw exceededError()
            running++
        }
        watchdog.statementsRunning(1)
        if (pastDeadline()) {
            exitStatement()
            throw exceededError()
        }
    }

    fun exitStatement() {
        locked { running-- }
        watchdog.statementsRunning(-1)
    }

    /**
     * ⚠ [block] is file-system work done under the lock that is **not a statement**: a
     * directory's `F_FULLFSYNC`, a marker lookup that may sync (§4.2.1's watchdog table: "a marker
     * or sentinel file: exempt", its syncs residual R2; Appendix B.10 (140)). Its time does not
     * count against [limitMs]: the clock check subtracts it, and a deadline that falls inside it,
     * or before its time was added, is pushed back by it. Never around a statement.
     */
    inline fun <T> exempt(block: () -> T): T {
        beginExempt()
        try {
            return block()
        } finally {
            endExempt()
        }
    }

    /** Past the deadline by the clock, and not deferred (B.10 (143)). */
    private fun pastDeadline(): Boolean = heldMs >= limitMs && deferWhile?.invoke() != true

    fun beginExempt() = locked {
        // Nothing nests an exempt window; a nested one would lose the outer one's start.
        check(exemptSince == null) { "[replica] an exempt window inside another" }
        exemptSince = clock()
    }

    fun endExempt() {
        val rearmIn = locked {
            val since = exemptSince ?: return
            exemptSince = null
            val spent = clock() - since
            exemptTotal += spent
            if (!isArmed) return
            if (firedWhileExempt) {
                firedWhileExempt = false
                // ⚠ heldMs already leaves out EVERY window, earlier ones included: what an earlier
                // window owed is in this re-arm, and must not push it back again (B.10 (144)).
                extensionOwed = 0
                (limitMs - heldMs).coerceAtLeast(1L)
            } else {
                extensionOwed += spent
                null
            }
        }
        rearmIn?.let { delay -> watchdog.after(delay) { fire() } }
    }

    /**
     * [block] as one statement of this hold (or a few, as a reading of the marker): refused once
     * the deadline has passed, interrupted if it fires while [block] runs, and a `SQLITE_INTERRUPT`
     * this watch caused is thrown as [HoldCeilingExceeded] with SQLite's as its cause. ⚠⚠ The
     * statement is marked ended (the `finally`) **before** the failure reaches the caller, whose
     * handling rolls back: a watch still counting a statement could interrupt that `ROLLBACK`
     * (B.9 (128)). [block] makes SQLite calls only, on this hold's connection.
     */
    inline fun <T> statement(block: () -> T): T {
        enterStatement()
        try {
            return block()
        } catch (e: SqliteException) {
            if (e.primaryCode == SQLITE_INTERRUPT && interruptedAStatement) throw exceededError(e)
            throw e
        } finally {
            exitStatement()
        }
    }

    /**
     * The transaction is about to issue `COMMIT`: refused if the deadline has passed, and
     * otherwise **disarmed in the same step**, so the watchdog can never interrupt the commit or
     * refuse it afterwards.
     */
    fun beforeCommit() {
        locked {
            if (exceeded) throw exceededError()
            if (!isArmed) return
            isArmed = false
        }
        afterDisarm()
        if (pastDeadline()) throw exceededError()
    }

    /** The transaction ended without a `COMMIT` (a rollback returned, or it was never begun). Idempotent. */
    fun disarm() {
        val was = locked { isArmed.also { isArmed = false } }
        if (was) afterDisarm()
    }

    private fun afterDisarm() {
        watchdog.disarmed(this)
        val held = heldMs
        if (held >= ReplicaHoldWatchdog.LONG_HOLD_LOG_MS) println("[replica] long hold: '$label' held $holding for $held ms")
    }

    /** The deadline, on the watchdog's queue. */
    internal fun fire() {
        // Deferred (a checkpoint still in the foreground, B.10 (143)): asked again shortly, never
        // marked exceeded. Asked outside the lock: it may ask the platform.
        if (deferWhile?.invoke() == true) {
            if (locked { isArmed }) watchdog.after(ReplicaHoldWatchdog.DEFERRED_RECHECK_MS) { fire() }
            return
        }
        // An exempt window: inside one, the deadline waits for its end; after one, it is pushed
        // back by the window's time (B.10 (140)).
        val pushedBack = locked {
            if (!isArmed) return
            if (exemptSince != null) {
                firedWhileExempt = true
                return
            }
            extensionOwed.takeIf { it > 0 }?.also { extensionOwed = 0 }
        }
        if (pushedBack != null) {
            watchdog.after(pushedBack) { fire() }
            return
        }
        val interrupt = locked {
            if (!isArmed) return
            val first = !exceeded
            exceeded = true
            if (first) println("[replica] the hold watchdog fired: '$label' has held $holding for $heldMs ms, past $bound ($limitMs ms)")
            if (running > 0) {
                interruptedRunning = true
                // Under this lock, so it cannot land after the statement was marked ended.
                connection.interrupt()
                true
            } else {
                false
            }
        }
        if (interrupt) {
            watchdog.interrupted()
            // Repeated while the statement still runs: one landing before SQLite started it is lost.
            watchdog.after(ReplicaHoldWatchdog.REINTERRUPT_MS) { fire() }
        }
    }

    /**
     * ⚠⚠ **The token-expiry interrupt for this hold** (Appendix B.15 (200), (202)): past its deadline
     * now, whatever the deadline, an exempt window or `deferWhile` say. A statement running is
     * interrupted (and again every [ReplicaHoldWatchdog.REINTERRUPT_MS] while it still runs, on the
     * watchdog's queue: one landing before SQLite started it is lost, B.9 (124)); the next statement
     * and the `COMMIT` are refused with [ProtectionExpired]. ⚠ A hold already disarmed (its `COMMIT`
     * issued, or rolled back) is left alone: the `COMMIT` finishes. Never blocks.
     */
    internal fun expire(why: String) {
        var first = false
        val interrupt = locked {
            if (!isArmed) return
            if (expiredBy == null) {
                // HOLD_MAX may have fired first; the refusal still says why it is final now.
                first = true
                expiredBy = why
                exceeded = true
                println("[replica] the token-expiry interrupt reached '$label' after $heldMs ms holding $holding ($why)")
            }
            if (running > 0) {
                interruptedRunning = true
                // Under this lock, so it cannot land after the statement was marked ended.
                connection.interrupt()
                true
            } else {
                false
            }
        }
        if (first) watchdog.expired()
        if (interrupt) {
            watchdog.interrupted()
            watchdog.after(ReplicaHoldWatchdog.REINTERRUPT_MS) { expire(why) }
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
}
