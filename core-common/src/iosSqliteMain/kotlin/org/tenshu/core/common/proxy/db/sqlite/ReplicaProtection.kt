package org.tenshu.core.common.proxy.db.sqlite

import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_time
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.posix.usleep
import kotlin.time.TimeSource

/**
 * One background-time assertion of the platform: `beginBackgroundTask(expirationHandler:)` on a
 * device. A seam, so the predicate is tested with fake assertions and a fake clock; on a device it
 * is the host's `SharedReplicaBackgroundTime`.
 */
internal interface ProtectionAssertions {
    /**
     * Begins one assertion and returns its id, or null when the platform grants no time.
     * [onExpired] is that assertion's own expiration handler, called on any thread. A throw is
     * treated as null.
     */
    fun begin(reason: String, onExpired: () -> Unit): Long?

    /** Ends it. Called exactly once per id begun. */
    fun end(id: Long)

    /**
     * `backgroundTimeRemaining` in ms, for §4.2.1's admission checks: asked only while the app is
     * in the background. On a device, `UIApplication.backgroundTimeRemaining`.
     */
    fun backgroundTimeRemainingMs(): Long
}

/**
 * The work rule P stops and the foreground starts (lease draft §5.3 steps 2 and 3): the loops.
 * Every call is made under [ReplicaProtection]'s state lock, so [stop] and [start] must not block:
 * `stop()`, never `stopAndAwait()`.
 */
internal interface ProtectedWork {
    fun start()
    fun stop()

    /** Waits up to [timeoutMs] for what [stop] cancelled to stop. Called with no lock held. */
    fun awaitStopped(timeoutMs: Long)

    object None : ProtectedWork {
        override fun start() = Unit
        override fun stop() = Unit
        override fun awaitStopped(timeoutMs: Long) = Unit
    }
}

/** The clock the implicit token's idle release and the main thread's retry run on. */
internal interface ProtectionTimer {
    /** Runs [block] once, on another thread, after [delayMs]. */
    fun after(delayMs: Long, block: () -> Unit)

    /** Sleeps the calling thread. */
    fun sleep(ms: Long)

    /** Runs [block] once, soon, on a thread that is not the main one. */
    fun dispatch(block: () -> Unit)
}

internal object DispatchProtectionTimer : ProtectionTimer {
    override fun after(delayMs: Long, block: () -> Unit) {
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, delayMs * 1_000_000),
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
            block,
        )
    }

    override fun sleep(ms: Long) {
        usleep((ms * 1_000).toUInt())
    }

    override fun dispatch(block: () -> Unit) {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u), block)
    }
}

/** How one shutdown ended, for the log and the tests. */
internal enum class ReplicaQuiesceOutcome {
    /** Every gate drained and every connection was closed as §2.8's table says. */
    QUIESCED,

    /** An admission outlived the drain: `[quiesce] TIMED OUT`, nothing closed (lease draft §5.3 step 5). */
    TIMED_OUT,

    /** Protection came back before the gate closed or while it drained: nothing closed. */
    REGAINED,
}

/** A protection token: one assertion, released once. Idempotent. */
internal interface ReplicaProtectionToken {
    val reason: String
    fun release()
}

/**
 * ⚠⚠ **A task's token** (`ios-shared-store-sqlite.md` Appendix B.23): a token whose assertion is a
 * `BGTask` the platform already runs (a `BGContinuedProcessingTask`, a `BGProcessingTask`), not a
 * `beginBackgroundTask` of its own. Nothing is begun or ended on the platform for it: the task
 * keeps the process from being suspended until the task is completed, so its owner [release]s (or
 * [expire]s) it **before** completing the task. [expire] is the task's `expirationHandler`:
 * B.15.3's first row when it ends protection. Both are idempotent, in any order.
 */
internal interface ReplicaTaskToken : ReplicaProtectionToken {
    /** The task's `expirationHandler`: returns once the token has ended (its quiesce run, if it was the last). */
    fun expire()
}

/**
 * ⚠⚠ **Rule P as a predicate (§4.4, S6), with the draft's tokens and quiesce (lease draft §5.3,
 * §4.1 here).** `protected = isForeground || liveTokens ≠ ∅`. Every event (a token acquired,
 * released or expired; the foreground entered or left) updates its input under one lock and
 * re-evaluates it. Only a change to false shuts down (stop the loops, wait up to 1 s, close and
 * drain every attached gate, close connections by §2.8's table, then end the assertion: Q2); only
 * a change to true opens the gates.
 *
 * - **An expiry ends tokens, never the foreground's protection.** A wake's assertion that
 *   expires while the app is in the foreground, or while another token is live, cancels that
 *   wake's job (the token's `onExpired`) and ends that assertion; nothing else changes (W7).
 *   Only the expiry that makes `protected` false shuts down, with a 2 s drain and no 1 s wait,
 *   and sets the **post-expiry latch**: [protect] then refuses every thread but the main one
 *   until an explicit [acquire] succeeds or the app enters the foreground.
 * - **The loops follow `isForeground` alone**: started on entering it, stopped on leaving it; a
 *   token neither starts nor stops them.
 * - **A thread the gate refuses** ([awaitAdmission], holding nothing): [protect] takes an
 *   implicit token, released once every gate has admitted nobody for a 250 ms tick; refused, a
 *   thread off the main thread parks until the gate opens (logged after 1 s), and the main thread
 *   sleeps 250 ms and asks again, never parking (it would wait for a signal only it can
 *   deliver).
 * - **A shutdown is abandoned if protection comes back** before the gates close or while they
 *   drain ([ReplicaGate.beginQuiesce]): nothing is closed, the gate reopens.
 *
 * One per process, made by `SharedReplicaLifecycle.create`.
 */
internal class ReplicaProtection(
    private val assertions: ProtectionAssertions,
    private val timer: ProtectionTimer = DispatchProtectionTimer,
    private val isMainThread: () -> Boolean = { NSThread.isMainThread },
    private val log: (String) -> Unit = ::println,
) : ReplicaGateProtection {

    private val state = NSRecursiveLock()
    private val shutdowns = NSRecursiveLock()
    private val registries = ArrayList<ReplicaHandles>()
    private val live = LinkedHashSet<Token>()
    private var work: ProtectedWork = ProtectedWork.None
    private var installed = false
    private var foreground = false
    private var latched = false
    private var foregroundsEntered = 0L

    /** The token whose release runs the shutdown under way, if a release does (N1). */
    private var quiesceOwner: Token? = null

    /**
     * True from the step-4 close of a shutdown's gates until that quiesce has ended its expiry
     * (B.15 (201)): the window in which an expiry of its owner interrupts at once, rather than at
     * the close. Read and written under the state lock.
     */
    private var quiesceClosed = false

    /** Every token-expiry interrupt issued (B.15): why, and how many holds it reached. Test seam. */
    private val interrupts = ArrayList<Pair<String, Int>>()
    private val clock = TimeSource.Monotonic.markNow()

    /** Test seams: between `acquire` and the flip in [enteredBackground]; between RELEASING and the shutdown in a release. Null in production. */
    internal var beforeBackgroundFlip: (() -> Unit)? = null
    internal var beforeReleaseShutdown: (() -> Unit)? = null
    internal var afterReleaseDead: (() -> Unit)? = null

    /** Test seam: in a quiesce's `finally`, after its expiry ended and before its gates reopen (B.15 (201)). Null in production. */
    internal var betweenExpiryEndAndReopen: (() -> Unit)? = null
    private val outcomes = ArrayList<ReplicaQuiesceOutcome>()

    /**
     * NEW until its assertion is begun and registered; LIVE while it protects; RELEASING from a
     * release until its assertion has ended (its quiesce may be running); DEAD after.
     */
    private enum class TokenState { NEW, LIVE, RELEASING, DEAD }

    private inner class Token(
        override val reason: String,
        val isImplicit: Boolean,
        val drainMs: Long,
        val onExpired: (() -> Unit)?,
        /** A task's token (B.23): its assertion is the platform's task, so none is begun or ended for it. */
        val isTask: Boolean = false,
    ) : ReplicaTaskToken {
        var assertion = 0L
        var tokenState = TokenState.NEW
        var expiredEarly = false
        var ended = false

        /** Its release made `protected` false: a quiesce runs (or ran) under it. */
        var quiescesOnRelease = false

        /** An expiry while RELEASING asked its quiesce's drain to end by then (monotonic ms). */
        var cutAtMs: Long? = null

        /** Signalled when its release has finished its shutdown ((82)): what an expiry waits on. */
        val releaseDone = NSCondition()
        var released = false

        /** Its release began (it went RELEASING): an expiry after DEAD waits for [released] too. */
        var releaseStarted = false

        /**
         * An expiry while RELEASING with nothing else protecting (B.15 (201)): its own quiesce
         * interrupts every hold when it closes its gates, as (81) applies the drain cut then.
         */
        var interruptAtClose = false

        /**
         * An expiry that took it from LIVE is still running (its cancel, its shutdown, its end):
         * set under the state lock in the same step that marks it DEAD, cleared when [expired]
         * has finished. What a task token's end waits for (B.23.6).
         */
        val expiryDone = NSCondition()
        var expiryRunning = false
        var expiryThread: NSThread? = null

        override fun release() {
            // ⚠ B.23: a registry-lock holder's release hands its shutdown to
            // another thread and returns at once (N-B), which only an assertion's end can wait for.
            // A task's token has no assertion to end, so its owner would complete the task with the
            // drain still running: refused, as a job's token is (86).
            if (isTask) check(!holdsARegistryLock()) { "[protection] task '$reason': a task's token is never released holding a registry's lock" }
            this@ReplicaProtection.release(this)
            if (isTask) awaitExpiry(this)
        }

        override fun expire() {
            check(isTask) { "[protection] only a task's token is expired by its owner" }
            check(!holdsARegistryLock()) { "[protection] task '$reason': a task's token is never expired holding a registry's lock" }
            this@ReplicaProtection.expired(this)
            awaitExpiry(this)
        }
    }

    /** True while the app is in the foreground or a token is live (S6). */
    val isProtected: Boolean get() = locked { protectedNow() }

    override val isForeground: Boolean get() = locked { foreground }

    override val isInstalled: Boolean get() = locked { installed }

    /** True after an expiry that ended protection, until the foreground or an explicit token. */
    val isLatched: Boolean get() = locked { latched }

    /**
     * How many times the app has entered the foreground since [install] (a launch in it not
     * counted). The worker's "not again before the next foreground" compares it (Appendix B.11 (147)).
     */
    val foregroundEntries: Long get() = locked { foregroundsEntered }

    val liveTokenCount: Int get() = locked { live.size }

    /** Every shutdown's outcome so far, oldest first. */
    val quiesceOutcomes: List<ReplicaQuiesceOutcome> get() = locked { outcomes.toList() }

    /** Every token-expiry interrupt issued so far (B.15): why, and how many holds it reached. */
    val expiryInterrupts: List<Pair<String, Int>> get() = locked { interrupts.toList() }

    /**
     * Puts [registry]'s gate under this protection. Before [install] it changes nothing; after,
     * the gate is closed now if the process is unprotected.
     */
    fun attach(registry: ReplicaHandles) = locked {
        check(registry.gate.protection == null) { "[protection] the gate of ${registry.databasePath} already has a protection" }
        // Attach before install: attaching later, unprotected, would close
        // the gate over connections nobody then closes, and closing them here would take the
        // registry's lock under the state lock, against the order.
        check(!installed) { "[protection] attach every registry before install" }
        registry.gate.protection = this
        // A deferred close is retried the moment the gate opens again, on another thread (never
        // in the reopen's caller, which may hold the state lock).
        // ⚠ Unconditional: a pre-check of `hasDeferredCloses` here could read
        // false while a refused close is between its refusal and its queueing, and strand it. The
        // dispatched retry takes the registry's lock, which that closer holds until the close is
        // queued, so it always sees it: correct by construction, at the cost of a no-op dispatch.
        registry.gate.onOpened = { timer.dispatch { registry.closeDeferredNow() } }
        registries += registry
    }

    /**
     * At launch (lease draft §5.3 step 6): a process launched in the foreground opens its gates
     * and starts the loops; one launched in the background starts with its gates closed and its
     * loops stopped, and its first access gets protection from a token or from [protect].
     */
    fun install(isForeground: Boolean, work: ProtectedWork = ProtectedWork.None) = locked {
        check(!installed) { "[protection] installed twice" }
        installed = true
        this.work = work
        foreground = isForeground
        if (isForeground) work.start()
        // A token taken before install (or the foreground) keeps the gates open (R5).
        if (protectedNow()) {
            registries.forEach { it.gate.reopen() }
        } else {
            registries.forEach {
                it.gate.beginQuiesce()
                it.gate.endQuiesce()
            }
        }
    }

    /**
     * Begins one assertion and adds a token for it, which opens the gates. Null, with nothing
     * changed, when the platform grants no time (or the call throws): the caller does no store
     * work. [onExpired] cancels the caller's job when this assertion expires (§4.4). [drainMs] is
     * how long the quiesce its release may run waits (2 s at the end of a wake, 10 s on entering
     * the background).
     */
    fun acquire(reason: String, drainMs: Long = WAKE_DRAIN_MS, onExpired: (() -> Unit)? = null): ReplicaProtectionToken? =
        begin(reason, isImplicit = false, drainMs = drainMs, onExpired = onExpired)

    /**
     * ⚠⚠ **A task's token** (Appendix B.23): explicit, like [acquire], but its assertion is the
     * platform's `BGTask` that is already running, so nothing is begun on the platform and it is
     * never refused for want of time. While it is live the admission checks read the task's time,
     * not `backgroundTimeRemaining` ([remainingMs]). Its release quiesces as any last token's
     * (Q2, a 2 s drain); its [ReplicaTaskToken.expire], from the task's `expirationHandler`, is
     * an expiry (§4.4's table, B.15.3). Null only if it is gone before it is handed out.
     */
    fun acquireTask(reason: String, onExpired: (() -> Unit)? = null): ReplicaTaskToken? =
        begin(reason, isImplicit = false, drainMs = WAKE_DRAIN_MS, onExpired = onExpired, isTask = true)

    private fun begin(reason: String, isImplicit: Boolean, drainMs: Long, onExpired: (() -> Unit)?, isTask: Boolean = false): Token? {
        val token = Token(reason, isImplicit, drainMs, onExpired, isTask)
        val id = if (isTask) TASK_ASSERTION else try {
            assertions.begin(reason) { expired(token) }
        } catch (t: Throwable) {
            log("[protection] beginning an assertion threw ($reason): ${t.message}")
            null
        } ?: return null
        var refused = false
        val (expiredAlready, gates) = locked {
            token.assertion = id
            // ⚠ The latch may have been set since protect() read it (an expiry in between): an
            // implicit token off the main thread is then refused here, under the same lock.
            if (isImplicit && latched && !isMainThread()) {
                refused = true
                token.tokenState = TokenState.DEAD
                return@locked false to emptyList()
            }
            token.tokenState = TokenState.LIVE
            live += token
            if (!isImplicit) latched = false
            // Idempotent while protected; during a quiesce it is owed, which abandons the drain.
            registries.forEach { it.gate.reopen() }
            token.expiredEarly to registries.toList()
        }
        if (refused) {
            endOnce(token)
            return null
        }
        if (expiredAlready) {
            expired(token)
            return null
        }
        // ⚠ A reopen owed to a quiesce applies only when its drain gives up, an instant later.
        // The token is handed out once the gates are open, so a job holding it does not meet
        // the closed gate and stop (C1). Waited for here, with no lock held.
        //
        // ⚠ C3: how long. Off the main thread, with no registry lock held, **until the gate is
        // open** (or the token is gone): the closes a quiesce is in the middle of are bounded only
        // by the registry's lock, and a job handed a token at a closed gate would stop. On the main
        // thread, or by a thread holding a registry's lock (the closes may be waiting for it), at
        // most `OWED_REOPEN_WAIT_MS` (1 s) per gate, then logged and handed out anyway: such a
        // caller's next access waits at the gate itself.
        val bounded = isMainThread() || gates.any { it.lock.isHeldByCurrentThread() }
        for (registry in gates) {
            val gate = registry.gate
            if (bounded) {
                if (!gate.awaitOpen(OWED_REOPEN_WAIT_MS)) log("[protection] '$reason': the gate did not reopen within $OWED_REOPEN_WAIT_MS ms")
                continue
            }
            var logged = false
            while (!gate.awaitOpen(OWED_REOPEN_WAIT_MS)) {
                val repairOrGone = locked {
                    when {
                        token.tokenState != TokenState.LIVE -> true
                        // Protected, closed, and no quiesce to apply the reopen (R5): repaired.
                        !gate.isQuiescing -> true.also { gate.reopen() }
                        else -> false
                    }
                }
                if (repairOrGone && (gate.isOpen || locked { token.tokenState != TokenState.LIVE })) break
                if (!logged) {
                    logged = true
                    log("[protection] '$reason': still waiting for the gate to reopen")
                }
            }
        }
        // (83): an expiry may have won while this waited; a dead token is never handed out (its
        // expiry has already shut down or ended it).
        if (locked { token.tokenState != TokenState.LIVE }) return null
        return token
    }

    /** True if the calling thread holds any attached registry's lock. */
    private fun holdsARegistryLock(): Boolean = locked { registries.toList() }.any { it.lock.isHeldByCurrentThread() }

    /**
     * Closes every gate in one state-locked step, if nothing protects (a hand-off's first step).
     * Its `beginQuiesce` may meet a gate another shutdown is closing past its drain, and resets
     * that gate's owed reopen and drain cut. Benign: nothing protects (checked under the same
     * lock), so an owed reopen belonged to protection that is gone, and a cut
     * only shortens a drain that is already over; the shutdown handed off here then drains the
     * gate itself.
     */
    private fun closeGatesIfUnprotected() = locked {
        if (!protectedNow()) registries.forEach { it.gate.beginQuiesce() }
    }

    /**
     * ⚠ (86) enforced: the token of a **job** (a `REPLACEMENT` run, a wipe call, their
     * checkpoints). A job's token is taken off the main thread and with no registry lock held, so
     * `acquire`'s wait for an owed reopen is never the bounded one (71) and the job never meets
     * the gate closed with its token live. Called on the main thread or by a registry-lock holder
     * it fails with `IllegalStateException`, before any assertion is begun.
     */
    fun acquireForJob(reason: String, drainMs: Long = WAKE_DRAIN_MS, onExpired: (() -> Unit)? = null): ReplicaProtectionToken? {
        check(!isMainThread()) { "[protection] job '$reason': a job's token is never taken on the main thread (86)" }
        check(!holdsARegistryLock()) { "[protection] job '$reason': a job's token is never taken holding a registry's lock (86)" }
        return acquire(reason, drainMs, onExpired)
    }

    /** Ends [token]'s assertion exactly once, whoever gets there first. Never throws. */
    private fun endOnce(token: Token) {
        val first = locked {
            if (token.ended) {
                false
            } else {
                token.ended = true
                true
            }
        }
        // A task's assertion is the task: its owner completes it, after this (B.23).
        if (!first || token.isTask) return
        try {
            assertions.end(token.assertion)
        } catch (t: Throwable) {
            log("[protection] ending the assertion of '${token.reason}' threw: ${t.message}")
        }
    }

    private fun release(token: Token) {
        val last = locked {
            if (token.tokenState != TokenState.LIVE) return
            token.tokenState = TokenState.RELEASING
            token.releaseStarted = true
            live -= token
            token.quiescesOnRelease = !protectedNow()
            token.quiescesOnRelease
        }
        if (last && holdsARegistryLock()) {
            // ⚠ N-B: a thread holding a registry's lock cannot run the shutdown (the closes need
            // that lock; shutdown() refuses it). The gates close now, in one state-locked step,
            // the shutdown is handed to another thread, and the assertion ends only when that
            // shutdown has returned (Q2).
            closeGatesIfUnprotected()
            // No step 3 here: the gates are already closed, so a second of waiting would only
            // keep the loops' last runs out.
            timer.dispatch { finishRelease(token, last = true, awaitWork = false) }
            return
        }
        finishRelease(token, last)
    }

    private fun finishRelease(token: Token, last: Boolean, awaitWork: Boolean = true) {
        try {
            beforeReleaseShutdown?.invoke()
            // Q2: the last token's assertion ends only once the gates have closed and drained.
            if (last) shutdown(token.drainMs, awaitWork = awaitWork, why = "release of '${token.reason}'", owner = token)
        } finally {
            locked { token.tokenState = TokenState.DEAD }
            afterReleaseDead?.invoke()
            // (82): an expiry that met RELEASING waits for this, not for the shutdown lock, which
            // this release may not even have taken yet.
            token.releaseDone.lock()
            token.released = true
            token.releaseDone.broadcast()
            token.releaseDone.unlock()
            endOnce(token)
        }
    }

    /**
     * The assertion's own expiration handler (§4.4's table). ⚠ It never returns with its
     * assertion alive and never throws. On the main thread it blocks for at most the cancel,
     * plus `EXPIRY_DRAIN_MS` (2 s) of drain and the closes when it ends protection, or plus
     * `RELEASING_EXPIRY_WAIT_MS` (3 s) when its own release's quiesce is already running.
     */
    private fun expired(token: Token) {
        var ranFromLive = false
        var cancel: (() -> Unit)? = null
        var releasing = false
        var handedOff = false
        val last = locked {
            when (token.tokenState) {
                TokenState.NEW -> {
                    token.expiredEarly = true
                    return
                }
                TokenState.DEAD -> {
                    // A release that has set DEAD but not yet ended the assertion: wait for it
                    // like RELEASING. A token that died any other way has ended.
                    if (!token.releaseStarted) return
                    releasing = true
                    return@locked false
                }
                TokenState.RELEASING -> {
                    // Its release is quiescing: cut that drain to the expiry's 2 s, latch if
                    // nothing else protects, and wait for it below (R1).
                    releasing = true
                    if (!protectedNow()) latched = true
                    // Only the drain of ITS OWN quiesce is cut: now if that quiesce is draining,
                    // else when it begins (a release queued behind another shutdown, N1).
                    if (token.quiescesOnRelease) {
                        token.cutAtMs = nowMs() + EXPIRY_DRAIN_MS
                        if (quiesceOwner === token) registries.forEach { if (it.gate.isQuiescing) it.gate.shortenDrain(EXPIRY_DRAIN_MS) }
                        // ⚠⚠ B.15 (201): with nothing else protecting, the holds its drain waits
                        // for are interrupted: now if its quiesce has closed the gates, else when
                        // it does. Under this lock, so it never lands after that quiesce ended.
                        if (!protectedNow()) {
                            token.interruptAtClose = true
                            if (quiesceOwner === token && quiesceClosed) interruptAll(registries.toList(), "expiry of '${token.reason}' during its release")
                        }
                    }
                    return@locked false
                }
                TokenState.LIVE -> Unit
            }
            token.tokenState = TokenState.DEAD
            live -= token
            // ⚠ In this same step: a release or a second expiry that now finds
            // the token DEAD can wait for this expiry, rather than return while it drains.
            token.expiryDone.lock()
            token.expiryRunning = true
            token.expiryThread = NSThread.currentThread
            token.expiryDone.unlock()
            ranFromLive = true
            cancel = token.onExpired
            val nowUnprotected = !protectedNow()
            // Only the expiry that ended protection latches (§4.4); one with the foreground or
            // another token still there leaves everything else as it was (W7).
            if (nowUnprotected) latched = true
            nowUnprotected
        }
        try {
            if (releasing) {
                // Waits for the release itself to finish (its quiesce, now cut to 2 s), however far
                // it had got: before, inside or after taking the shutdown lock ((82)).
                token.releaseDone.lock()
                try {
                    val deadline = NSDate.dateWithTimeIntervalSinceNow(RELEASING_EXPIRY_WAIT_MS / 1000.0)
                    while (!token.released) {
                        if (!token.releaseDone.waitUntilDate(deadline)) break
                    }
                    if (!token.released) log("[protection] '${token.reason}' expired while its release was still quiescing; ending it anyway")
                } finally {
                    token.releaseDone.unlock()
                }
                return
            }
            try {
                cancel?.invoke()
            } catch (t: Throwable) {
                log("[protection] the expiry handler of '${token.reason}' threw: ${t.message}")
            }
            if (last && holdsARegistryLock()) {
                // N-B, the same hand-off as a release's: reached through `expiredEarly` on a
                // registry-lock holder's acquire, never from the platform's own handler (which
                // runs from the run loop, not inside an access).
                closeGatesIfUnprotected()
                handedOff = true
                timer.dispatch {
                    try {
                        shutdown(EXPIRY_DRAIN_MS, awaitWork = false, why = "expiry of '${token.reason}'", interrupt = true)
                    } catch (t: Throwable) {
                        log("[protection] the shutdown on the expiry of '${token.reason}' failed: ${t.message}")
                    } finally {
                        endOnce(token)
                        expiryFinished(token)
                    }
                }
            } else if (last) {
                try {
                    // ⚠⚠ B.15 (200): the expiry that ends protection interrupts every hold.
                    shutdown(EXPIRY_DRAIN_MS, awaitWork = false, why = "expiry of '${token.reason}'", interrupt = true)
                } catch (t: Throwable) {
                    // (70): an expiration handler never throws; the lock-order check is the one
                    // way shutdown can.
                    log("[protection] the shutdown on the expiry of '${token.reason}' failed: ${t.message}")
                }
            }
        } finally {
            if (!handedOff) {
                endOnce(token)
                if (ranFromLive) expiryFinished(token)
            }
        }
    }

    private fun expiryFinished(token: Token) {
        token.expiryDone.lock()
        token.expiryRunning = false
        token.expiryThread = null
        token.expiryDone.broadcast()
        token.expiryDone.unlock()
    }

    /**
     * ⚠⚠ **A task token's end returns only once its expiry has finished** (B.23.6).
     * [expired] marks the token DEAD before its shutdown runs, so a [release] (or a second
     * expiry) arriving in between would return at once, and a task token's owner then completes
     * the task with the interrupt and the drain still running: `0xdead10cc`. An entry's token does
     * not need it: its platform assertion, which is what keeps the process running, ends only in
     * [expired]'s own `endOnce`. Not on the expiry's own thread (its `onExpired` could release),
     * and bounded, logged past it.
     */
    private fun awaitExpiry(token: Token) {
        token.expiryDone.lock()
        try {
            if (!token.expiryRunning || token.expiryThread == NSThread.currentThread) return
            val deadline = NSDate.dateWithTimeIntervalSinceNow(TASK_EXPIRY_WAIT_MS / 1000.0)
            while (token.expiryRunning) {
                if (!token.expiryDone.waitUntilDate(deadline)) break
            }
            if (token.expiryRunning) log("[protection] '${token.reason}': its expiry was still running after $TASK_EXPIRY_WAIT_MS ms; ending anyway")
        } finally {
            token.expiryDone.unlock()
        }
    }

    /** Idempotent: a second call while in the foreground changes nothing and starts nothing. */
    fun enteredForeground() = locked {
        if (foreground) return@locked
        val was = protectedNow()
        foreground = true
        foregroundsEntered++
        latched = false
        if (!was) registries.forEach { it.gate.reopen() }
        work.start()
    }

    /**
     * Leaving the foreground, called on the main thread from the notification (B.5.2 (72)): the
     * loops stop (they follow `isForeground` alone) and a `background` token is taken **here,
     * synchronously**, so the process is protected before the call returns; its release, which
     * quiesces with a 10 s drain, runs **off the main thread** ([ProtectionTimer.dispatch]).
     * If the platform grants no time and nothing else protects, the shutdown runs here at once,
     * with a 2 s drain and no 1 s wait: there is no assertion to cover a gate left open (R3), and
     * nothing to keep the process running if it were handed to another thread. That path blocks
     * the main thread for at most the 2 s drain plus the closes.
     */
    fun enteredBackground() {
        if (!isForeground) return
        // ⚠ C4: the assertion is taken while the foreground still protects, and only then is the
        // foreground given up, so there is no instant with the gate open and nothing protecting.
        val token = acquire("background", drainMs = BACKGROUND_DRAIN_MS)
        beforeBackgroundFlip?.invoke()
        val closedHere = locked {
            if (!foreground) return@locked false
            foreground = false
            safely("stopping the loops") { work.stop() }
            // With no time granted and nothing else protecting, the gates close in the same
            // critical section as the flip; the shutdown below drains them.
            if (!protectedNow()) {
                registries.forEach { it.gate.beginQuiesce() }
                true
            } else {
                false
            }
        }
        // ⚠ (78): decided by what the flip found under the lock, not by whether a token came back:
        // the token can expire between acquire() and the flip, and then this flip closed the gates
        // and only this shutdown ends that quiesce. A live token implies protected, so the two
        // branches never both apply.
        if (closedHere) {
            // ⚠⚠ B.15 (200): no assertion covers this drain either, so it interrupts as an expiry does.
            shutdown(EXPIRY_DRAIN_MS, awaitWork = false, why = "background with no time protecting", interrupt = true)
        } else if (token != null) {
            timer.dispatch { token.release() }
        }
    }

    /**
     * §4.2.1's 30 s foreground trigger (Appendix B.12 (159)), fired by the UIKit adapter's timer
     * on the worker's queue: every live store of every attached registry checkpoints if it
     * committed since the last tick. Outside the foreground nothing runs: the adapter stops the
     * timer there, and each store asks the foreground itself, under its lock, which is the check
     * that holds (a tick that fired as the app left it). Called with no lock held, off the main
     * thread. A store that throws is logged and the rest still run (a throw on a dispatch queue
     * would end the process). The count of checkpoints run.
     */
    fun foregroundCheckpointTick(): Int {
        // The listing too: nothing of a tick may throw out of it.
        val ticks = try {
            locked { registries.toList() }.flatMap { it.handles() }.mapNotNull { it.onForegroundTick }
        } catch (t: Throwable) {
            log("[protection] listing the stores for the 30 s foreground checkpoint threw: ${t.message}")
            return 0
        }
        var ran = 0
        for (tick in ticks) {
            try {
                if (tick()) ran++
            } catch (t: Throwable) {
                log("[protection] a 30 s foreground checkpoint threw: ${t.message}")
            }
        }
        return ran
    }

    /**
     * The implicit token (lease draft §5.3: `protect()` is `acquire("implicit")`). True when the
     * process is protected now, by this call or already. Refused off the main thread while
     * latched.
     */
    fun protect(): Boolean {
        val already = locked {
            if (latched && !isMainThread()) return false
            protectedNow()
        }
        if (already) return true
        val token = begin("implicit", isImplicit = true, drainMs = WAKE_DRAIN_MS, onExpired = null) ?: return false
        releaseWhenIdle(token, locked { serial() })
        return true
    }

    /**
     * Released once a [IMPLICIT_IDLE_MS] tick passes with every gate idle and no admission since
     * the previous tick, so it outlives the access that asked for it by one to two ticks.
     */
    private fun releaseWhenIdle(token: Token, serialBefore: Long) {
        timer.after(IMPLICIT_IDLE_MS) {
            val (stillLive, idle, serialNow) = locked {
                Triple(token.tokenState == TokenState.LIVE, registries.all { it.gate.admittedCount == 0 }, serial())
            }
            when {
                !stillLive -> Unit
                idle && serialNow == serialBefore -> token.release()
                else -> releaseWhenIdle(token, serialNow)
            }
        }
    }

    /**
     * §4.2.1's `remaining`: null in the foreground (no deadline while it lasts; the backgrounding
     * token supplies the time, §4.2.1 "marker time is reserved up front"), else the platform's
     * `backgroundTimeRemaining`, asked outside the state lock. A seam that throws counts as none
     * left, so nothing starts on time nobody can name.
     */
    override fun remainingMs(): Long? {
        // ⚠⚠ B.23: while a task's token is live the process runs on the task's time, which the
        // platform states as no number (a `BGTask` has no deadline until its expirationHandler) and
        // `backgroundTimeRemaining` does not describe. As in the foreground, the time to finish is
        // what the expiry supplies: the interrupt and its 2 s drain (B.15.3), which every
        // operation's reserve already counts. Not null: that is the foreground's alone.
        val (inForeground, task) = locked { foreground to live.any { it.isTask } }
        if (inForeground) return null
        if (task) return Long.MAX_VALUE
        return try {
            assertions.backgroundTimeRemainingMs()
        } catch (t: Throwable) {
            log("[protection] backgroundTimeRemaining threw: ${t.message}; treated as 0")
            0L
        }
    }

    override fun awaitAdmission(gate: ReplicaGate, label: String) {
        if (gate.isOpen) return
        if (protect()) {
            // Protected now: the gate is open, or a quiesce is abandoning its drain and owes the
            // reopen; either way it opens shortly. Bounded, so a caller loops rather than hangs.
            if (gate.awaitOpen(PROTECTED_OPEN_WAIT_MS)) return
            // ⚠ Protected, closed, and no quiesce to apply a reopen: a state rule P forbids
            // (R5). Repaired here rather than spun on, and said.
            val repaired = locked { if (protectedNow() && !gate.isQuiescing && !gate.isOpen) true.also { gate.reopen() } else false }
            if (repaired) log("[gate] protected but closed with no quiesce running; reopened: $label")
            return
        }
        if (isMainThread()) {
            // ⚠ Never park the main thread: the foreground notification it would wait for is
            // delivered on it. Ask again after a pause.
            timer.sleep(MAIN_THREAD_RETRY_MS)
            return
        }
        gate.park(PARK_LOG_AFTER_MS) { log("[gate] parked without protection: $label") }
    }

    /**
     * Rule P's shutdown, serialised with any other: re-checked under the state lock before each
     * step that closes something, so protection that came back meanwhile abandons it.
     */
    private fun shutdown(drainMs: Long, awaitWork: Boolean, why: String, owner: Token? = null, interrupt: Boolean = false) {
        // The order is shutdowns, then a registry, then the state. A thread holding a
        // registry's lock here would deadlock against the closes (or the follow-up): it fails.
        check(locked { registries.toList() }.none { it.lock.isHeldByCurrentThread() }) {
            "[protection] a shutdown ($why) was started by a thread holding a registry's lock"
        }
        shutdowns.lock()
        try {
            locked { quiesceOwner = owner }
            val outcome = quiesce(drainMs, awaitWork, why, owner, interrupt)
            locked { outcomes += outcome }
        } finally {
            // A cut an expiry made for this quiesce never outlives it, whichever way it ended.
            locked {
                quiesceOwner = null
                registries.toList()
            }.forEach { it.gate.clearDrainCap() }
            shutdowns.unlock()
        }
    }

    /** Protection came back: a gate closed for this shutdown (or by the flip, C4) is reopened. */
    private fun regained(gates: List<ReplicaHandles>): ReplicaQuiesceOutcome {
        gates.forEach { if (it.gate.isQuiescing) it.gate.endQuiesce() }
        return ReplicaQuiesceOutcome.REGAINED
    }

    private fun quiesce(drainMs: Long, awaitWork: Boolean, why: String, owner: Token?, interrupt: Boolean): ReplicaQuiesceOutcome {
        // Step 2: cancel, so nothing starts more work.
        val gates = locked {
            if (protectedNow()) return regained(registries.toList())
            safely("stopping the loops") { work.stop() }
            registries.toList()
        }
        // Step 3: give the cancelled runs up to 1 s, with the gate still open, to stop.
        if (awaitWork) safely("waiting for the loops") { work.awaitStopped(WORK_STOP_WAIT_MS) }
        // Step 4: closed from this instant, unless protection came back meanwhile.
        locked {
            if (protectedNow()) return regained(gates)
            gates.forEach { it.gate.beginQuiesce() }
            // An expiry of the owner that arrived before the gates closed cuts this drain now.
            owner?.cutAtMs?.let { at -> gates.forEach { it.gate.shortenDrain(maxOf(0L, at - nowMs())) } }
            quiesceClosed = true
            // ⚠⚠ B.15 (200): a shutdown no assertion covers (an expiry, the background with no
            // time) interrupts every hold before it drains, so the drain waits for rollbacks, not
            // for transactions that would carry their locks into suspension (`0xdead10cc`).
            if (interrupt || owner?.interruptAtClose == true) interruptAll(gates, why)
        }
        var outcome = ReplicaQuiesceOutcome.QUIESCED
        try {
            for (registry in gates) {
                when (registry.gate.drain(drainMs)) {
                    // §4.3: with the gate drained, no SQLite lock of any kind is left in the process.
                    ReplicaDrain.QUIESCED -> closeAll(registry, why)
                    ReplicaDrain.TIMED_OUT -> {
                        outcome = ReplicaQuiesceOutcome.TIMED_OUT
                        val holders = registry.gate.heldAdmissions()
                        val oldest = holders.firstOrNull()
                        log("[quiesce] TIMED OUT holder=${oldest?.label ?: "?"} heldMs=${oldest?.heldMs ?: -1} admitted=${holders.size} ($why)")
                        // R7: if the holder leaves while the process is still unprotected, its
                        // connections and the job handles are closed then, not left for good.
                        registry.gate.whenIdle {
                            timer.dispatch { closeIfStillUnprotected(registry) }
                        }
                    }
                    ReplicaDrain.REOPENED -> if (outcome == ReplicaQuiesceOutcome.QUIESCED) outcome = ReplicaQuiesceOutcome.REGAINED
                }
            }
        } finally {
            // ⚠ The expiry ends BEFORE the gates reopen (B.15 (201)): an access admitted by a
            // reopen is protected again and must not be met by this quiesce's interrupt.
            locked {
                quiesceClosed = false
                gates.forEach { it.holdWatchdog.endExpiry() }
            }
            safely("the test seam between the expiry's end and the reopen") { betweenExpiryEndAndReopen?.invoke() }
            gates.forEach { it.gate.endQuiesce() }
        }
        return outcome
    }

    /**
     * ⚠⚠ **The token-expiry interrupt** (Appendix B.15 (200)): every hold of every gate in [gates],
     * now and until this quiesce ends ([ReplicaHoldWatchdog.expireAll]). Called under the state lock
     * (lock order: state, then a watchdog's, then a hold's; none of them is ever held while the state
     * lock is taken). Never blocks and never throws.
     */
    private fun interruptAll(gates: List<ReplicaHandles>, why: String) {
        var reached = 0
        for (registry in gates) {
            try {
                reached += registry.holdWatchdog.expireAll(why)
            } catch (t: Throwable) {
                log("[protection] the token-expiry interrupt of ${registry.databasePath} threw: ${t.message}")
            }
        }
        interrupts += why to reached
        log("[protection] token-expiry interrupt ($why): $reached transaction(s) reached")
    }

    /** A timed-out quiesce's follow-up: the late holder has left. */
    private fun closeIfStillUnprotected(registry: ReplicaHandles) {
        shutdowns.lock()
        try {
            // C5: decided and done as a quiesce of its own, under the registry's lock (order:
            // shutdowns, then the registry, then the state, as everywhere). A reopen from here on
            // is owed and applies at endQuiesce, after the closes, so no protected access or job
            // ever has a connection or a handle closed under it.
            registry.lock.lock()
            try {
                val still = locked {
                    (!protectedNow() && !registry.gate.isOpen && !registry.gate.isQuiescing && registry.gate.admittedCount == 0)
                        .also { if (it) registry.gate.beginQuiesce() }
                }
                if (!still) return
                try {
                    closeAll(registry, "after a timed-out quiesce")
                    log("[quiesce] closed after the timed-out holder left")
                } finally {
                    registry.gate.endQuiesce()
                }
            } finally {
                registry.lock.unlock()
            }
        } finally {
            shutdowns.unlock()
        }
    }

    private fun closeAll(registry: ReplicaHandles, why: String) {
        try {
            registry.closeAllForQuiesce()
        } catch (t: Throwable) {
            log("[quiesce] a connection could not be closed ($why): ${t.message}")
        }
    }

    /** A hook of the caller's (the loops) must not cost an assertion its end (R2). */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("[protection] $what threw: ${t.message}")
        }
    }

    private fun protectedNow(): Boolean = foreground || live.isNotEmpty()

    private fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds

    private fun serial(): Long = registries.sumOf { it.gate.admissionSerial }

    private inline fun <T> locked(block: () -> T): T {
        state.lock()
        try {
            return block()
        } finally {
            state.unlock()
        }
    }

    companion object {
        /** The drain at the end of a wake, at an expiry, and when no time is granted at all. */
        const val WAKE_DRAIN_MS = 2_000L
        const val EXPIRY_DRAIN_MS = 2_000L

        /** The drain on entering the background. */
        const val BACKGROUND_DRAIN_MS = 10_000L

        /** Step 3: how long cancelled runs are given to stop. */
        const val WORK_STOP_WAIT_MS = 1_000L

        const val IMPLICIT_IDLE_MS = 250L
        const val MAIN_THREAD_RETRY_MS = 250L
        const val PARK_LOG_AFTER_MS = 1_000L

        /** After [protect] said yes: how long a thread waits for an owed reopen before it tries again. */
        const val PROTECTED_OPEN_WAIT_MS = 50L

        /** How long [acquire] waits for a reopen owed to a quiesce whose drain is giving up (C1). */
        const val OWED_REOPEN_WAIT_MS = 1_000L

        /** How long an expiry waits for its own release's quiesce, whose drain it cut to 2 s (R1). */
        const val RELEASING_EXPIRY_WAIT_MS = 3_000L

        /** How long a task token's end waits for its expiry in flight: the cut drain, the closes, and room. */
        const val TASK_EXPIRY_WAIT_MS = 10_000L

        /** A task token's assertion id: never handed to the platform (B.23). */
        private const val TASK_ASSERTION = -1L
    }
}
