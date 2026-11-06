package org.tenshu.core.common.proxy.db.sqlite

import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Rule P as a predicate (§4.4, S6), the draft's tokens and quiesce (lease draft §5.3, kept by
 * §4.1), and §4.2.1's admission checks, over a real store, with fake assertions, a fake timer and
 * a fake main thread. W7 and W8 of §12 are here; the hold watchdog has its own suite.
 */
class ReplicaProtectionTest {

    // ── fakes ──

    /** What the process looked like at the instant an assertion ended (Q2). */
    private data class AtEnd(val reason: String, val gateOpen: Boolean, val admitted: Int, val runtimeConnected: Boolean, val handles: Int)

    private class FakeAssertions : ProtectionAssertions {
        private val lock = NSRecursiveLock()
        private var next = 1L
        val begun = ArrayList<String>()
        val ended = ArrayList<Long>()
        val reasons = HashMap<Long, String>()
        private val handlers = HashMap<Long, () -> Unit>()

        /** How many of the next begins return null. */
        var refuse = 0

        /** Every begin returns null. */
        var grantNone = false

        /** What `backgroundTimeRemaining` answers; 30 s admits every operation at the defaults. */
        @kotlin.concurrent.Volatile
        var remainingMs: Long = 30_000

        /** When set, `backgroundTimeRemaining` throws. */
        var remainingThrows = false

        override fun backgroundTimeRemainingMs(): Long = if (remainingThrows) error("no time source") else remainingMs
        var onEnd: ((String) -> Unit)? = null

        /** Called after an assertion is granted and before begin returns, outside this fake's lock. */
        var afterGrant: ((String) -> Unit)? = null

        /** Called first in every begin, outside this fake's lock. */
        var onBegin: ((String) -> Unit)? = null

        override fun begin(reason: String, onExpired: () -> Unit): Long? {
            onBegin?.invoke(reason)
            val id = locked {
                begun += reason
                if (grantNone) return@locked null
                if (refuse > 0) {
                    refuse--
                    return@locked null
                }
                val id = next++
                reasons[id] = reason
                handlers[id] = onExpired
                id
            } ?: return null
            afterGrant?.invoke(reason)
            return id
        }

        override fun end(id: Long) {
            val reason = locked {
                check(id !in ended) { "assertion $id ended twice" }
                ended += id
                reasons.getValue(id)
            }
            onEnd?.invoke(reason)
        }

        fun idOf(reason: String): Long = locked { reasons.entries.last { it.value == reason }.key }
        fun isEnded(reason: String): Boolean = locked { idOf(reason) in ended }

        /** The platform calls this assertion's expiration handler. */
        fun expire(reason: String) {
            val handler = locked { handlers.getValue(idOf(reason)) }
            handler()
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

    private class FakeTimer : ProtectionTimer {
        private val lock = NSRecursiveLock()
        private var now = 0L
        private val pending = ArrayList<Pair<Long, () -> Unit>>()
        val sleeps = ArrayList<Long>()
        val dispatches = AtomicInt(0)
        private val inFlight = NSCondition()
        private var running = 0
        private val held = ArrayList<() -> Unit>()

        /** While true, dispatched blocks wait in [held] until [releaseHeld]. */
        var holdDispatches = false

        /** As production does (N4): onto a real background queue. Counted; [awaitDispatched] waits for them. */
        override fun dispatch(block: () -> Unit) {
            dispatches.incrementAndGet()
            inFlight.lock()
            try {
                running++
                if (holdDispatches) {
                    held += block
                    return
                }
            } finally {
                inFlight.unlock()
            }
            launch(block)
        }

        private fun launch(block: () -> Unit) {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                try {
                    block()
                } finally {
                    inFlight.lock()
                    running--
                    inFlight.broadcast()
                    inFlight.unlock()
                }
            }
        }

        fun releaseHeld() {
            inFlight.lock()
            val blocks = held.toList()
            held.clear()
            holdDispatches = false
            inFlight.unlock()
            blocks.forEach(::launch)
        }

        /** Waits until every dispatched block has run. */
        fun awaitDispatched(timeoutMs: Long = 15_000) {
            inFlight.lock()
            try {
                val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
                while (running > 0) {
                    if (!inFlight.waitUntilDate(deadline)) fail("dispatched work still running after $timeoutMs ms")
                }
            } finally {
                inFlight.unlock()
            }
        }

        override fun after(delayMs: Long, block: () -> Unit) {
            lock.lock()
            try {
                pending += (now + delayMs) to block
            } finally {
                lock.unlock()
            }
        }

        override fun sleep(ms: Long) {
            lock.lock()
            try {
                sleeps += ms
                if (sleeps.size > 20) fail("the main thread asked again more than 20 times")
            } finally {
                lock.unlock()
            }
            usleep(1_000u)
        }

        /** Moves the clock and runs what fell due, on the calling thread. */
        fun advance(ms: Long) {
            val due = ArrayList<() -> Unit>()
            lock.lock()
            try {
                now += ms
                pending.filter { it.first <= now }.forEach { due += it.second }
                pending.removeAll { it.first <= now }
            } finally {
                lock.unlock()
            }
            due.forEach { it() }
        }
    }

    private class CountingWork : ProtectedWork {
        val starts = AtomicInt(0)
        val stops = AtomicInt(0)
        val awaits = AtomicInt(0)

        /** Run inside step 3's wait, as a cancelled run or the platform might act meanwhile. */
        var onAwait: (() -> Unit)? = null
        override fun start() {
            starts.incrementAndGet()
        }

        var onStop: (() -> Unit)? = null

        override fun stop() {
            stops.incrementAndGet()
            onStop?.invoke()
        }

        override fun awaitStopped(timeoutMs: Long) {
            awaits.incrementAndGet()
            onAwait?.invoke()
        }
    }

    /** A block on another thread, awaited with a deadline so that a park that never ends fails rather than hangs. */
    private class OnAnotherThread<T>(block: () -> T) {
        private val condition = NSCondition()
        private var result: Result<T>? = null

        init {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                val r = runCatching(block)
                condition.lock()
                result = r
                condition.broadcast()
                condition.unlock()
            }
        }

        val isDone: Boolean
            get() {
                condition.lock()
                try {
                    return result != null
                } finally {
                    condition.unlock()
                }
            }

        fun await(timeoutMs: Long = 5_000): T {
            condition.lock()
            try {
                val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
                while (result == null) {
                    if (!condition.waitUntilDate(deadline)) break
                }
                return (result ?: fail("still running after $timeoutMs ms")).getOrThrow()
            } finally {
                condition.unlock()
            }
        }
    }

    private class Setup(isForeground: Boolean, limits: ReplicaLimits = ReplicaLimits(), files: ReplicaFiles = PosixReplicaFiles) {
        val process = ReplicaProcess(tempReplica(), limits, files)
        val assertions = FakeAssertions()
        val timer = FakeTimer()
        val work = CountingWork()
        val logs = ArrayList<String>()
        private val logLock = NSRecursiveLock()

        @kotlin.concurrent.Volatile
        var onMainThread = false
        val protection = ReplicaProtection(assertions, timer, { onMainThread }) { line ->
            logLock.lock()
            try {
                logs += line
            } finally {
                logLock.unlock()
            }
        }
        val store: SharedReplicaStore = process.store()
        val atEnd = ArrayList<AtEnd>()

        init {
            protection.attach(process.registry)
            protection.install(isForeground, work)
            assertions.onEnd = { reason -> atEnd += snapshot(reason) }
        }

        val gate get() = process.registry.gate
        val runtime get() = process.registry.handles().single { it.kind == ReplicaHandleKind.RUNTIME }

        fun snapshot(reason: String) = AtEnd(reason, gate.isOpen, gate.admittedCount, runtime.hasConnection, process.registry.handles().size)

        fun logged(prefix: String): Boolean {
            logLock.lock()
            try {
                return logs.any { it.startsWith(prefix) }
            } finally {
                logLock.unlock()
            }
        }

        /** A job's handle with a connection open, as a checkpoint or a run would hold between its accesses. */
        fun openJobHandle(): ReplicaHandle {
            val handle = process.registry.register(ReplicaHandleKind.CHECKPOINT, "job")
            assertTrue(gate.enter("job"))
            try {
                process.registry.connectionFor(handle)
            } finally {
                gate.leave()
            }
            return handle
        }

        /** On another thread with a deadline, so that an access that never gets in fails the test instead of hanging the run. */
        fun write(hash: Long) = OnAnotherThread { store.storeObject(row(hash)) }.await()
    }

    private fun quiescedAtEnd(reason: String) = AtEnd(reason, gateOpen = false, admitted = 0, runtimeConnected = false, handles = 1)

    // ── launch and the foreground ──

    @Test
    fun aProcessLaunchedInTheForegroundIsOpenAndRunsItsLoops() {
        val s = Setup(isForeground = true)
        assertTrue(s.gate.isOpen)
        assertTrue(s.protection.isProtected)
        assertEquals(1, s.work.starts.value)
        s.write(1)
        assertEquals(emptyList(), s.assertions.begun, "the foreground needs no token")
    }

    @Test
    fun aProcessLaunchedInTheBackgroundStartsClosedWithItsLoopsStopped() {
        val s = Setup(isForeground = false)
        assertFalse(s.gate.isOpen)
        assertFalse(s.protection.isProtected)
        assertEquals(0, s.work.starts.value)
    }

    @Test
    fun leavingTheForegroundQuiescesBeforeTheAssertionEnds() {
        val s = Setup(isForeground = true)
        s.write(1)
        val job = s.openJobHandle()
        assertTrue(s.runtime.hasConnection)

        s.protection.enteredBackground()
        s.timer.awaitDispatched()

        assertEquals(listOf("background"), s.assertions.begun)
        assertEquals(1, s.timer.dispatches.value, "the token is taken on the caller's thread, its quiesce is handed off it (R6)")
        assertTrue(s.assertions.isEnded("background"))
        // Q2 and S5: at the instant the last assertion ended, the gate was closed and drained,
        // the runtime's connection closed with its handle kept, the job's handle released.
        assertEquals(listOf(quiescedAtEnd("background")), s.atEnd)
        assertTrue(job.isReleased)
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
        assertTrue(s.work.stops.value >= 1, "the loops are stopped")
        assertEquals(1, s.work.awaits.value, "step 3 gave them their second")
        assertFalse(s.protection.isProtected)
    }

    @Test
    fun leavingTheForegroundWithNoTimeGrantedShutsDownAtOnce() {
        val s = Setup(isForeground = true)
        s.write(1)
        s.assertions.grantNone = true
        s.protection.enteredBackground()
        s.timer.awaitDispatched()
        assertEquals(0, s.work.awaits.value, "no 1 s wait with no assertion to cover the open gate (R3)")
        assertFalse(s.gate.isOpen)
        assertFalse(s.runtime.hasConnection)
        assertEquals(emptyList(), s.assertions.ended, "there was no assertion to end")
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
    }

    @Test
    fun enteringTheForegroundAgainOpensTheGateAndRestartsTheLoops() {
        val s = Setup(isForeground = true)
        s.write(1)
        s.protection.enteredBackground()
        s.timer.awaitDispatched()
        assertFalse(s.gate.isOpen)
        s.protection.enteredForeground()
        assertTrue(s.gate.isOpen)
        assertEquals(2, s.work.starts.value)
        s.write(2)
        assertTrue(s.runtime.hasConnection, "reopened through the registry on the next access (§4.3)")
        assertNotNull(s.store.getObject(2))
    }

    @Test
    fun theLoopsFollowTheForegroundAloneNotTheTokens() {
        val s = Setup(isForeground = true)
        val wake = assertNotNull(s.protection.acquire("wake"))
        assertEquals(1, s.work.starts.value, "a token starts nothing")
        s.protection.enteredBackground()
        s.timer.awaitDispatched()
        assertTrue(s.work.stops.value >= 1, "leaving the foreground stops them with a token live")
        assertTrue(s.gate.isOpen, "the wake's token keeps the gate open")
        assertEquals(emptyList(), s.protection.quiesceOutcomes, "the background token was not the last")
        s.write(1)
        wake.release()
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
        assertEquals(1, s.work.starts.value, "nor does its release start them")
    }

    // ── tokens ──

    @Test
    fun aTokenInTheBackgroundOpensTheGateAndItsReleaseClosesIt() {
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake"))
        assertTrue(s.gate.isOpen)
        s.write(1)
        token.release()
        assertEquals(listOf(quiescedAtEnd("wake")), s.atEnd)
        token.release()
        assertEquals(1, s.assertions.ended.size, "a release is idempotent")
    }

    @Test
    fun aTokenThePlatformRefusesIsNullAndChangesNothing() {
        val s = Setup(isForeground = false)
        s.assertions.grantNone = true
        assertNull(s.protection.acquire("wake"))
        assertFalse(s.gate.isOpen)
        assertEquals(0, s.protection.liveTokenCount)
    }

    @Test
    fun anAssertionThatThrowsIsARefusal() {
        val s = ReplicaProcess(tempReplica())
        val protection = ReplicaProtection(
            object : ProtectionAssertions {
                override fun begin(reason: String, onExpired: () -> Unit): Long? = error("no")
                override fun end(id: Long) = fail("nothing was begun")
                override fun backgroundTimeRemainingMs(): Long = 30_000
            },
            FakeTimer(),
            { false },
        ) {}
        protection.attach(s.registry)
        protection.install(isForeground = false)
        assertNull(protection.acquire("wake"))
        assertFalse(protection.protect())
    }

    // ── expiry: W7 and the latch ──

    @Test
    fun aWakeExpiringInTheForegroundEndsOnlyItsToken() {
        // W7 (finding 1): the draft stopped the session here.
        val s = Setup(isForeground = true)
        val cancelled = AtomicInt(0)
        assertNotNull(s.protection.acquire("wake") { cancelled.incrementAndGet() })
        s.write(1)

        s.assertions.expire("wake")

        assertEquals(1, cancelled.value, "the wake's job is cancelled")
        assertTrue(s.assertions.isEnded("wake"), "and its assertion ended")
        assertTrue(s.gate.isOpen)
        assertTrue(s.protection.isProtected)
        assertFalse(s.protection.isLatched)
        assertEquals(0, s.work.stops.value, "the loops run on")
        assertEquals(emptyList(), s.protection.quiesceOutcomes)
        assertTrue(s.runtime.hasConnection, "nothing was closed")
        s.write(2)
        assertNotNull(s.store.getObject(2), "writes still succeed")
    }

    @Test
    fun aWakeExpiringBehindAnotherTokenEndsOnlyItself() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        assertNotNull(s.protection.acquire("upload"))
        s.assertions.expire("wake")
        assertTrue(s.gate.isOpen)
        assertFalse(s.protection.isLatched)
        assertEquals(emptyList(), s.protection.quiesceOutcomes)
        s.write(1)
    }

    @Test
    fun theExpiryThatEndsProtectionShutsDownThenEndsTheAssertionAndLatches() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.openJobHandle()
        s.assertions.expire("wake")
        assertEquals(listOf(quiescedAtEnd("wake")), s.atEnd)
        assertEquals(0, s.work.awaits.value, "no 1 s wait on the expiry path")
        assertTrue(s.protection.isLatched)
        assertFalse(s.protection.protect(), "a thread off the main thread is refused while latched")
        assertEquals(listOf("wake"), s.assertions.begun, "without asking the platform")
    }

    @Test
    fun theLatchLetsTheMainThreadThrough() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.assertions.expire("wake")
        s.onMainThread = true
        assertTrue(s.protection.protect())
        assertTrue(s.gate.isOpen)
    }

    @Test
    fun theLatchClearsOnTheForegroundAndOnAnExplicitToken() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.assertions.expire("wake")
        assertTrue(s.protection.isLatched)
        val explicit = assertNotNull(s.protection.acquire("upload"))
        assertFalse(s.protection.isLatched)
        s.assertions.expire("upload")
        assertTrue(s.protection.isLatched)
        s.protection.enteredForeground()
        assertFalse(s.protection.isLatched)
        explicit.release()
    }

    @Test
    fun anExpiryAfterTheReleaseIsIgnored() {
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake"))
        token.release()
        s.assertions.expire("wake")
        assertEquals(1, s.assertions.ended.size)
        assertFalse(s.protection.isLatched)
    }

    // ── the implicit token ──

    @Test
    fun anAccessRefusedAtTheGateTakesAnImplicitTokenThatEndsWhenIdle() {
        val s = Setup(isForeground = false)
        s.write(1)
        assertEquals(listOf("implicit"), s.assertions.begun)
        assertTrue(s.gate.isOpen)

        s.timer.advance(ReplicaProtection.IMPLICIT_IDLE_MS)
        assertFalse(s.assertions.isEnded("implicit"), "the access came after the token: not idle across a whole tick yet")
        s.timer.advance(ReplicaProtection.IMPLICIT_IDLE_MS)
        assertEquals(listOf(quiescedAtEnd("implicit")), s.atEnd)
    }

    @Test
    fun theImplicitTokenOutlivesAnAccessStillRunning() {
        val s = Setup(isForeground = false)
        s.write(1)
        val holding = OnAnotherThread { s.store.access("hold") { usleep(400_000u) } }
        usleep(100_000u)
        repeat(3) { s.timer.advance(ReplicaProtection.IMPLICIT_IDLE_MS) }
        assertFalse(s.assertions.isEnded("implicit"), "an admission is held")
        holding.await()
        repeat(2) { s.timer.advance(ReplicaProtection.IMPLICIT_IDLE_MS) }
        assertTrue(s.assertions.isEnded("implicit"))
    }

    @Test
    fun anOpenInTheBackgroundGetsProtectionFirst() {
        val s = Setup(isForeground = false)
        s.store.close()
        assertTrue(OnAnotherThread { s.process.open() }.await() is ReplicaOpenResult.Ready)
        assertTrue("implicit" in s.assertions.begun)
    }

    // ── waiting holds nothing ──

    @Test
    fun aThreadRefusedProtectionParksHoldingNothingUntilTheForeground() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.expire("wake")
        assertTrue(s.protection.isLatched)

        val parked = OnAnotherThread { s.write(2) }
        usleep(1_300_000u)
        assertFalse(parked.isDone, "parked")
        assertEquals(0, s.gate.admittedCount, "holding no admission")
        s.process.sql { sibling ->
            sibling.exec("BEGIN IMMEDIATE")
            sibling.exec("ROLLBACK")
        }
        assertTrue(s.logged("[gate] parked without protection: storeObject"))

        s.protection.enteredForeground()
        parked.await()
        assertNotNull(s.store.getObject(2))
    }

    @Test
    fun theMainThreadNeverParksItAsksAgainEvery250Ms() {
        val s = Setup(isForeground = false)
        s.onMainThread = true
        s.assertions.refuse = 2
        // On a thread standing in for the main one, so that a park fails the test, not the run.
        OnAnotherThread { s.write(1) }.await()
        assertEquals(listOf(ReplicaProtection.MAIN_THREAD_RETRY_MS, ReplicaProtection.MAIN_THREAD_RETRY_MS), s.timer.sleeps)
        assertEquals(listOf("implicit", "implicit", "implicit"), s.assertions.begun)
    }

    @Test
    fun aThreadWaitingOnASiblingWhenTheAppExpiresHoldsNothing() {
        // W8: between slices it holds nothing and is not counted; within one, the drain waits at
        // most one busy_timeout; and it never obtains the lock with the gate closed.
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        val sibling = rawConnection(s.process.databasePath)
        try {
            sibling.exec("BEGIN IMMEDIATE")
            val waiting = OnAnotherThread { s.write(2) }
            usleep(150_000u)
            assertFalse(waiting.isDone)

            val started = TimeSource.Monotonic.markNow()
            s.assertions.expire("wake")
            val tookMs = started.elapsedNow().inWholeMilliseconds
            assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
            assertTrue(tookMs < 500, "the drain waited out one slice, not the 2 s deadline: $tookMs ms")

            sibling.exec("ROLLBACK")
            usleep(200_000u)
            assertFalse(waiting.isDone, "the gate is closed: it does not take the lock the sibling gave up")
            s.process.sql { other ->
                other.exec("BEGIN IMMEDIATE")
                other.exec("ROLLBACK")
            }
            assertEquals(0, s.gate.admittedCount)

            s.protection.enteredForeground()
            waiting.await()
            assertNotNull(s.store.getObject(2))
        } finally {
            sibling.close()
        }
    }

    // ── a quiesce abandoned, a quiesce timed out ──

    @Test
    fun aShutdownIsAbandonedWhenProtectionComesBackDuringTheDrain() {
        val s = Setup(isForeground = false)
        val first = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        val holding = OnAnotherThread { s.store.access("hold") { usleep(500_000u) } }
        usleep(100_000u)
        val releasing = OnAnotherThread { first.release() }
        usleep(100_000u)
        assertFalse(s.gate.isOpen, "the drain is waiting for the access")
        val second = assertNotNull(s.protection.acquire("upload"))
        // C1: the token is handed out only once the owed reopen applied, so a job holding it does
        // not meet the closed gate; and the drain is abandoned at once, not when the access ends.
        assertTrue(s.gate.isOpen, "the gate is open when acquire returns, while the access still runs")
        assertFalse(holding.isDone)
        releasing.await()
        holding.await()
        assertEquals(listOf(ReplicaQuiesceOutcome.REGAINED), s.protection.quiesceOutcomes)
        assertTrue(s.gate.isOpen)
        assertTrue(s.runtime.hasConnection, "nothing was closed")
        assertTrue(s.assertions.isEnded("wake"))
        s.write(2)
        second.release()
        assertEquals(ReplicaQuiesceOutcome.QUIESCED, s.protection.quiesceOutcomes.last())
    }

    @Test
    fun protectionRegainedWhileTheLoopsStopAbandonsTheShutdownBeforeTheGateCloses() {
        val s = Setup(isForeground = false)
        val first = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        var second: ReplicaProtectionToken? = null
        s.work.onAwait = { second = s.protection.acquire("upload") }
        first.release()
        assertEquals(listOf(ReplicaQuiesceOutcome.REGAINED), s.protection.quiesceOutcomes)
        assertTrue(s.gate.isOpen)
        assertTrue(s.runtime.hasConnection)
        s.work.onAwait = null
        assertNotNull(second).release()
    }

    @Test
    fun aLockObtainedAsTheGateClosesIsGivenBackAndTheAccessWaits() {
        // §4.2 step 5: obtained, but the gate closed meanwhile: rolled back at once, as if never
        // admitted, and the access goes back to the gate rather than failing.
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        var once = true
        s.process.registry.beginFault = { if (once) { once = false; s.gate.beginQuiesce() } }
        val waiting = OnAnotherThread { s.write(2) }
        usleep(150_000u)
        assertFalse(waiting.isDone)
        s.process.sql { sibling ->
            sibling.exec("BEGIN IMMEDIATE")
            sibling.exec("ROLLBACK")
        }
        assertEquals(0, s.gate.admittedCount)
        s.gate.reopen()
        s.gate.endQuiesce()
        waiting.await()
        assertNotNull(s.store.getObject(2))
    }

    @Test
    fun aDrainThatTimesOutClosesNothingAndSaysWho() {
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 100))
        s.write(1)
        val runtime = s.runtime
        // The harness's snapshot takes the registry's lock, which the holder has: read without it.
        s.assertions.onEnd = { s.atEnd += AtEnd(it, s.gate.isOpen, s.gate.admittedCount, runtime.hasConnection, -1) }
        val holding = OnAnotherThread { s.store.access("hold") { usleep(600_000u) } }
        usleep(100_000u)
        token.release()
        assertEquals(listOf(ReplicaQuiesceOutcome.TIMED_OUT), s.protection.quiesceOutcomes)
        assertTrue(s.logged("[quiesce] TIMED OUT holder=hold"), "${s.logs}")
        val end = s.atEnd.single()
        assertTrue(end.runtimeConnected, "a thread is inside it: nothing is closed")
        assertEquals(1, end.admitted)
        assertTrue(runtime.hasConnection, "and nothing is closed while it holds")
        holding.await()
        s.timer.awaitDispatched()
        // R7: once the late holder has left, still unprotected, the connections are closed.
        assertFalse(s.runtime.hasConnection)
        assertTrue(s.logged("[quiesce] closed after the timed-out holder left"))
    }

    @Test
    fun aTimedOutQuiesceClosesNothingLaterIfProtectionCameBack() {
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 100))
        s.write(1)
        s.assertions.onEnd = null
        val holding = OnAnotherThread { s.store.access("hold") { usleep(400_000u) } }
        usleep(100_000u)
        token.release()
        val again = assertNotNull(s.protection.acquire("upload"))
        holding.await()
        s.timer.awaitDispatched()
        assertTrue(s.runtime.hasConnection)
        assertFalse(s.logged("[quiesce] closed after the timed-out holder left"))
        again.release()
    }

    // ── tokens at a closing gate, throwing hooks, the latch's edges ──

    @Test
    fun aTokenTakenWhileAQuiesceClosesIsHandedOutOnlyOnceTheGateIsOpen() {
        // C1, deterministic: the quiesce has drained and is closing connections, held up on the
        // registry's lock; a reopen owed now applies only when that ends. acquire waits for it.
        val s = Setup(isForeground = false)
        val first = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        val locker = OnAnotherThread {
            s.process.registry.lock.lock()
            try {
                usleep(400_000u)
            } finally {
                s.process.registry.lock.unlock()
            }
        }
        usleep(50_000u)
        val releasing = OnAnotherThread { first.release() }
        usleep(100_000u)
        assertFalse(s.gate.isOpen, "closing, held up on the lock")
        val second = assertNotNull(s.protection.acquire("upload"))
        assertTrue(s.gate.isOpen, "a job holding the token does not meet the closed gate")
        releasing.await()
        locker.await()
        second.release()
    }

    @Test
    fun anExpiryHandlerThatThrowsStillShutsDownAndEndsItsAssertion() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake") { error("the caller's cancel failed") })
        s.write(1)
        s.assertions.expire("wake")
        assertEquals(listOf(quiescedAtEnd("wake")), s.atEnd)
        assertTrue(s.logged("[protection] the expiry handler of 'wake' threw"))
    }

    @Test
    fun loopsThatThrowWhenStoppedCostNoAssertionItsEnd() {
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake"))
        s.work.onStop = { error("stop failed") }
        s.write(1)
        token.release()
        assertEquals(listOf(quiescedAtEnd("wake")), s.atEnd)
    }

    @Test
    fun anExpiryDuringItsOwnReleasesDrainCutsItAndEndsTheAssertionBeforeReturning() = expiryDuringItsOwnReleasesDrain(watched = false)

    /**
     * The same, under the hold watchdog in real time (Appendix B.10; the watchdog is off in every
     * suite but its own): the parked access, idle 3 s on its write lock, has its COMMIT refused at
     * HOLD_MAX, and nothing of the drain's cut or the assertion's end changes.
     */
    @Test
    fun underTheHoldWatchdogTheAccessParkedThroughAnExpiryCutDrainHasItsCommitRefused() = expiryDuringItsOwnReleasesDrain(watched = true)

    private fun expiryDuringItsOwnReleasesDrain(watched: Boolean) {
        // R1: the release's quiesce is waiting (10 s) on an access; the expiry cuts it to 2 s and
        // does not return with its assertion alive.
        val s = Setup(isForeground = false)
        if (watched) s.process.watchdogInRealTime()
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 10_000))
        s.write(1)
        // The harness's snapshot takes the registry's lock, which the holder has: not here.
        s.assertions.onEnd = null
        val holding = OnAnotherThread { s.store.access("hold") { usleep(3_000_000u) } }
        usleep(100_000u)
        val releasing = OnAnotherThread { token.release() }
        usleep(200_000u)
        assertFalse(s.gate.isOpen, "the release is draining")
        val started = TimeSource.Monotonic.markNow()
        s.assertions.expire("wake")
        val tookMs = started.elapsedNow().inWholeMilliseconds
        assertTrue(s.assertions.isEnded("wake"), "ended before the handler returned")
        assertTrue(tookMs in 1_500..2_900, "the drain was cut to the expiry's 2 s: $tookMs ms")
        assertTrue(s.protection.isLatched)
        releasing.await()
        if (watched) {
            // Idle for 3 s past HOLD_MAX: refused at its COMMIT, between statements, never interrupted.
            val refused = assertFailsWith<HoldCeilingExceeded> { holding.await() }
            assertNull(refused.cause, "refused before SQLite")
            assertEquals(0, s.process.registry.holdWatchdog.armed)
        } else {
            // B.15 (200): the expiry left nothing protecting, so the idle holder's COMMIT is
            // refused by the token-expiry interrupt (it was between statements: no cause).
            assertNull(assertFailsWith<ProtectionExpired> { holding.await() }.cause, "refused before SQLite")
        }
        assertEquals(listOf(ReplicaQuiesceOutcome.TIMED_OUT), s.protection.quiesceOutcomes)
        assertEquals(1, s.assertions.ended.size, "ended once")
    }

    @Test
    fun anImplicitTokenGrantedAsTheLatchIsSetIsRefused() {
        // R4: protect() read the latch as clear; an expiry latched it before the token registered.
        val s = Setup(isForeground = false)
        var once = true
        s.assertions.afterGrant = { reason ->
            if (reason == "implicit" && once) {
                once = false
                assertNotNull(s.protection.acquire("wake"))
                s.assertions.expire("wake")
            }
        }
        assertFalse(s.protection.protect())
        assertTrue(s.assertions.isEnded("implicit"), "its assertion was ended, not leaked")
        assertEquals(0, s.protection.liveTokenCount)
        assertFalse(s.gate.isOpen)
    }

    @Test
    fun aTokenTakenBeforeInstallKeepsTheGateOpen() {
        val process = ReplicaProcess(tempReplica())
        val assertions = FakeAssertions()
        val protection = ReplicaProtection(assertions, FakeTimer(), { false }) {}
        protection.attach(process.registry)
        assertNotNull(protection.acquire("wake"))
        protection.install(isForeground = false)
        assertTrue(process.registry.gate.isOpen, "install honours the predicate (R5)")
    }

    @Test
    fun aProtectedButClosedGateWithNoQuiesceIsRepairedNotSpunOn() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        assertTrue(s.gate.closeAndDrain(0))
        s.write(2)
        assertTrue(s.logged("[gate] protected but closed with no quiesce running; reopened: storeObject"))
        assertTrue(s.gate.isOpen)
    }

    @Test
    fun anAccessThatWouldWaitHoldingTheRegistryLockTwiceFailsInsteadOfDeadlocking() {
        // R8: waiting releases the lock once; a caller holding it twice would park with it held.
        val s = Setup(isForeground = false)
        val failure = OnAnotherThread {
            s.process.registry.lock.lock()
            try {
                runCatching { s.store.storeObject(row(1)) }.exceptionOrNull()
            } finally {
                s.process.registry.lock.unlock()
            }
        }.await()
        assertTrue(failure is IllegalStateException && "held 2 times" in failure.message.orEmpty(), "$failure")
    }

    @Test
    fun aGateTakesOneProtectionOnly() {
        val s = Setup(isForeground = true)
        val other = ReplicaProtection(FakeAssertions(), FakeTimer(), { false }) {}
        assertFailsWith<IllegalStateException> { other.attach(s.process.registry) }
    }

    @Test
    fun enteringTheForegroundTwiceStartsTheLoopsOnce() {
        val s = Setup(isForeground = false)
        s.protection.enteredForeground()
        s.protection.enteredForeground()
        assertEquals(1, s.work.starts.value)
    }

    // ── without protection ──

    @Test
    fun aGateWithNoProtectionStillRefusesWithReplicaGateClosed() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        assertTrue(process.registry.gate.closeAndDrain(100))
        assertFailsWith<ReplicaGateClosed> { store.storeObject(row(1)) }
        process.registry.gate.reopen()
        store.storeObject(row(1))
    }

    @Test
    fun aJobStopsAtAClosedGateRatherThanAskingForProtection() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.expire("wake")
        val begunBefore = s.assertions.begun.size
        val outcome = s.process.replacement().run()
        assertTrue(outcome is ReplicaRunOutcome.Stopped, "$outcome")
        assertEquals(begunBefore, s.assertions.begun.size, "a job takes its own token; it never asks the gate for one")
    }

    // ── the owed reopen's wait, the background flip, cut drains ──

    @Test
    fun offTheMainThreadATokenWaitsForASlowQuiesceToFinishItsCloses() {
        // C3: the closes are held up on the registry's lock for longer than 1 s.
        val s = Setup(isForeground = false)
        val first = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        val locker = OnAnotherThread {
            s.process.registry.lock.lock()
            try {
                usleep(1_600_000u)
            } finally {
                s.process.registry.lock.unlock()
            }
        }
        usleep(50_000u)
        val releasing = OnAnotherThread { first.release() }
        usleep(100_000u)
        val started = TimeSource.Monotonic.markNow()
        val second = OnAnotherThread { s.protection.acquire("upload") }.await()
        val tookMs = started.elapsedNow().inWholeMilliseconds
        assertNotNull(second)
        assertTrue(s.gate.isOpen, "handed out only once the gate is open")
        assertTrue(tookMs >= 1_100, "it waited past the main thread's 1 s bound: $tookMs ms")
        releasing.await()
        locker.await()
        second.release()
    }

    @Test
    fun onTheMainThreadThatWaitIsBoundedAtOneSecondPerGate() {
        val s = Setup(isForeground = false)
        val first = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        val locker = OnAnotherThread {
            s.process.registry.lock.lock()
            try {
                usleep(2_000_000u)
            } finally {
                s.process.registry.lock.unlock()
            }
        }
        usleep(50_000u)
        val releasing = OnAnotherThread { first.release() }
        usleep(100_000u)
        s.onMainThread = true
        val started = TimeSource.Monotonic.markNow()
        val second = assertNotNull(s.protection.acquire("upload"))
        val tookMs = started.elapsedNow().inWholeMilliseconds
        assertTrue(tookMs in 900..1_500, "bounded: $tookMs ms")
        assertTrue(s.logged("[protection] 'upload': the gate did not reopen within"))
        releasing.await()
        locker.await()
        s.onMainThread = false
        second.release()
    }

    @Test
    fun leavingTheForegroundTakesItsAssertionWhileTheForegroundStillProtects() {
        // C4: no instant with the gate open and nothing protecting.
        val s = Setup(isForeground = true)
        s.write(1)
        var protectedAtBegin: Boolean? = null
        s.assertions.onBegin = { if (it == "background") protectedAtBegin = s.protection.isProtected && s.gate.isOpen }
        s.protection.enteredBackground()
        s.timer.awaitDispatched()
        assertEquals(true, protectedAtBegin)
    }

    @Test
    fun leavingTheForegroundWithNoTimeClosesTheGateWithTheFlip() {
        val s = Setup(isForeground = true)
        s.write(1)
        s.assertions.grantNone = true
        var protectedAtBegin: Boolean? = null
        s.assertions.onBegin = { protectedAtBegin = s.protection.isProtected }
        s.protection.enteredBackground()
        assertEquals(true, protectedAtBegin, "still the foreground's while the platform is asked")
        assertFalse(s.gate.isOpen)
        assertFalse(s.gate.isQuiescing, "the shutdown ended its quiesce")
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
    }

    @Test
    fun withNoTimeTheGateIsAlreadyClosedWhenTheShutdownBegins() {
        // C4: closed in the flip's critical section, not later by the shutdown's own step 4.
        val s = Setup(isForeground = true)
        s.write(1)
        s.assertions.grantNone = true
        val openAtEachStop = ArrayList<Boolean>()
        s.work.onStop = { openAtEachStop += s.gate.isOpen }
        s.protection.enteredBackground()
        assertEquals(listOf(true, false), openAtEachStop, "the flip's stop, then the shutdown's step 2 with the gate already closed")
    }

    @Test
    fun aTimedOutQuiescesFollowUpClosesAsAQuiesceSoATokenWaitsForIt() {
        // C5: a token acquired while the follow-up closes is handed out only after the closes.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 100))
        s.write(1)
        s.assertions.onEnd = null
        val closing = NSCondition()
        var inClose = false
        var slowOnce = true
        val holding = OnAnotherThread { s.store.access("hold") { usleep(400_000u) } }
        usleep(100_000u)
        token.release()
        s.process.registry.closeFault = {
            if (slowOnce) {
                slowOnce = false
                closing.lock(); inClose = true; closing.broadcast(); closing.unlock()
                usleep(500_000u)
            }
        }
        holding.await()
        closing.lock()
        try {
            val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0)
            while (!inClose) if (!closing.waitUntilDate(deadline)) fail("the follow-up never closed")
        } finally {
            closing.unlock()
        }
        val second = assertNotNull(OnAnotherThread { s.protection.acquire("upload") }.await())
        assertTrue(s.logged("[quiesce] closed after the timed-out holder left"), "handed out only after the follow-up's closes")
        assertTrue(s.gate.isOpen)
        s.timer.awaitDispatched()
        s.process.registry.closeFault = null
        second.release()
    }

    @Test
    fun anOpenInTheBackgroundHoldsItsAdmissionThroughItsFileWork() {
        // C6: the implicit token does not idle out while the open creates its files.
        val files = RecordingFiles()
        val process = ReplicaProcess(tempReplica(), files = files)
        val assertions = FakeAssertions()
        val timer = FakeTimer()
        val protection = ReplicaProtection(assertions, timer, { false }) {}
        protection.attach(process.registry)
        protection.install(isForeground = false)
        var ticked = false
        files.onCall = { call ->
            if (!ticked && call.startsWith("createExclusive")) {
                ticked = true
                repeat(3) { timer.advance(ReplicaProtection.IMPLICIT_IDLE_MS) }
            }
        }
        val result = OnAnotherThread { process.open() }.await()
        assertTrue(ticked)
        assertTrue(result is ReplicaOpenResult.Ready, "$result")
        assertEquals(listOf("implicit"), assertions.begun)
        assertFalse(assertions.isEnded("implicit"), "not idle while the open held its admission")
    }

    @Test
    fun anExpiryBeforeItsReleaseClosesTheGatesCutsTheDrainThatFollows() = expiryBeforeItsReleaseClosesTheGates(watched = false)

    /** The same under the hold watchdog in real time (Appendix B.10): the parked access idle 4 s has its COMMIT refused. */
    @Test
    fun underTheHoldWatchdogTheAccessParkedThroughAnEarlyExpiryCutDrainHasItsCommitRefused() = expiryBeforeItsReleaseClosesTheGates(watched = true)

    private fun expiryBeforeItsReleaseClosesTheGates(watched: Boolean) {
        // N1 / (81): the expiry lands during step 3, before the gates close; its cut is applied
        // when its own quiesce closes them.
        val s = Setup(isForeground = false)
        if (watched) s.process.watchdogInRealTime()
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 10_000))
        s.write(1)
        s.assertions.onEnd = null
        val holding = OnAnotherThread { s.store.access("hold") { usleep(4_000_000u) } }
        usleep(100_000u)
        var expiring: OnAnotherThread<Unit>? = null
        s.work.onAwait = {
            expiring = OnAnotherThread { s.assertions.expire("wake") }
            usleep(100_000u)
        }
        val started = TimeSource.Monotonic.markNow()
        token.release()
        val tookMs = started.elapsedNow().inWholeMilliseconds
        assertEquals(listOf(ReplicaQuiesceOutcome.TIMED_OUT), s.protection.quiesceOutcomes)
        assertTrue(tookMs < 3_000, "the drain was cut to 2 s from the expiry, not 10 s: $tookMs ms")
        assertNotNull(expiring).await()
        if (watched) {
            val refused = assertFailsWith<HoldCeilingExceeded> { holding.await() }
            assertNull(refused.cause, "refused before SQLite")
            assertEquals(0, s.process.registry.holdWatchdog.armed)
        } else {
            // B.15 (201): the expiry's interrupt is applied when its own quiesce closes the gates.
            assertNull(assertFailsWith<ProtectionExpired> { holding.await() }.cause, "refused before SQLite")
        }
    }

    @Test
    fun aCutLeftFromAnEarlierShutdownNeverShortensTheNextDrain() {
        // N1: a cap set outside a quiesce (the gap between a shutdown's end and its release's
        // DEAD) is cleared by the next beginQuiesce: that drain gets its full time.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        s.gate.shortenDrain(0)
        val holding = OnAnotherThread { s.store.access("hold") { usleep(300_000u) } }
        usleep(100_000u)
        token.release()
        holding.await()
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
    }

    @Test
    fun theForegroundReturningBeforeTheDispatchedReleaseRunsMeansNoQuiesce() {
        // N4: the hand-off off the main thread really is one; the release finds the foreground.
        val s = Setup(isForeground = true)
        s.write(1)
        s.timer.holdDispatches = true
        s.protection.enteredBackground()
        assertFalse(s.assertions.isEnded("background"), "its release has not run yet")
        s.protection.enteredForeground()
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
        assertTrue(s.assertions.isEnded("background"))
        assertEquals(emptyList(), s.protection.quiesceOutcomes)
        assertTrue(s.gate.isOpen)
        assertTrue(s.runtime.hasConnection)
        assertEquals(2, s.work.starts.value)
    }

    // ── races of releases, expiries and flips; registry-lock holders; deferred closes ──

    @Test
    fun aBackgroundTokenThatExpiresBeforeTheFlipStillGetsItsShutdown() {
        // (78): the flip closed the gates; only its own shutdown ends that quiesce.
        val s = Setup(isForeground = true)
        s.write(1)
        s.protection.beforeBackgroundFlip = { s.assertions.expire("background") }
        s.protection.enteredBackground()
        s.timer.awaitDispatched()
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
        assertFalse(s.gate.isQuiescing, "the quiesce the flip began was ended")
        assertFalse(s.runtime.hasConnection, "and the connections were closed")
    }

    @Test
    fun anExpiryBetweenReleasingAndTheShutdownWaitsForTheRelease() {
        // (82): the release has not taken the shutdown lock yet when its token expires.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        val holding = OnAnotherThread { s.store.access("hold") { usleep(800_000u) } }
        usleep(100_000u)
        var expiring: OnAnotherThread<Int>? = null
        s.protection.beforeReleaseShutdown = {
            s.protection.beforeReleaseShutdown = null
            expiring = OnAnotherThread {
                s.assertions.expire("wake")
                s.protection.quiesceOutcomes.size
            }
            usleep(100_000u)
        }
        token.release()
        assertEquals(1, assertNotNull(expiring).await(), "the expiry returned only once the release's quiesce had ended")
        // B.15 (201): nothing else protected, so the idle holder's COMMIT is refused.
        assertFailsWith<ProtectionExpired> { holding.await() }
        assertEquals(1, s.assertions.ended.size)
    }

    @Test
    fun aTokenThatExpiresWhileItWaitsForTheReopenIsNotHandedOut() {
        // (83): the expiry wins the wait; acquire answers null.
        val s = Setup(isForeground = false)
        val first = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        val locker = OnAnotherThread {
            s.process.registry.lock.lock()
            try {
                usleep(1_500_000u)
            } finally {
                s.process.registry.lock.unlock()
            }
        }
        usleep(50_000u)
        val releasing = OnAnotherThread { first.release() }
        usleep(100_000u)
        val acquiring = OnAnotherThread { s.protection.acquire("upload") }
        usleep(300_000u)
        assertFalse(acquiring.isDone, "waiting for the reopen")
        s.assertions.expire("upload")
        assertNull(acquiring.await())
        releasing.await()
        locker.await()
    }

    @Test
    fun aFlipDuringAnotherShutdownsClosesDropsTheReopenTheForegroundHadOwed() {
        // S is closing (held up); the foreground returns (a reopen owed to S) and leaves
        // again with no time. That owed reopen is stale: S must not open the gate at its end.
        val s = Setup(isForeground = false)
        val first = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        val locker = OnAnotherThread {
            s.process.registry.lock.lock()
            try {
                usleep(600_000u)
            } finally {
                s.process.registry.lock.unlock()
            }
        }
        usleep(50_000u)
        val releasing = OnAnotherThread { first.release() }
        usleep(100_000u)
        s.protection.enteredForeground()
        s.assertions.grantNone = true
        val openAtStop = ArrayList<Boolean>()
        s.work.onStop = { openAtStop += s.gate.isOpen }
        val leaving = OnAnotherThread { s.protection.enteredBackground() }
        leaving.await()
        releasing.await()
        locker.await()
        assertEquals(false, openAtStop.last(), "the flip's shutdown found the gate still closed after S ended")
        assertFalse(s.gate.isOpen)
    }

    @Test
    fun aLastReleaseByARegistryLockHolderClosesTheGateAndHandsTheShutdownOff() {
        // N-B: the gate closes in the release's own step, the shutdown runs on another
        // thread, and the assertion ends only once that shutdown has returned (Q2).
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        s.timer.holdDispatches = true
        val atReturn = OnAnotherThread {
            s.process.registry.lock.lock()
            try {
                token.release()
                Triple(s.gate.isOpen, s.assertions.isEnded("wake"), s.protection.quiesceOutcomes.size)
            } finally {
                s.process.registry.lock.unlock()
            }
        }.await()
        assertEquals(Triple(false, false, 0), atReturn, "closed at once; the assertion lives until the handed-off shutdown")
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
        assertEquals(0, s.work.awaits.value, "no step 3 once the gates are closed")
        assertTrue(s.assertions.isEnded("wake"))
        assertEquals(listOf(ReplicaQuiesceOutcome.QUIESCED), s.protection.quiesceOutcomes)
        assertFalse(s.runtime.hasConnection)
    }

    @Test
    fun anEarlyExpiryOnARegistryLockHoldersAcquireClosesTheGateAndHandsTheShutdownOff() {
        // N-B through `expiredEarly`: the assertion expires while acquire begins it.
        val s = Setup(isForeground = false)
        s.write(1)
        s.timer.awaitDispatched()
        repeat(2) { s.timer.advance(ReplicaProtection.IMPLICIT_IDLE_MS) }
        s.timer.awaitDispatched()
        s.assertions.onEnd = null
        s.assertions.afterGrant = { reason -> if (reason == "job") s.assertions.expire("job") }
        s.timer.holdDispatches = true
        val atReturn = OnAnotherThread {
            s.process.registry.lock.lock()
            try {
                val token = s.protection.acquire("job")
                Triple(token == null, s.gate.isOpen, s.assertions.isEnded("job"))
            } finally {
                s.process.registry.lock.unlock()
            }
        }.await()
        assertEquals(Triple(true, false, false), atReturn)
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
        assertTrue(s.assertions.isEnded("job"))
    }

    @Test
    fun aCloseAfterATimedOutQuiesceIsDeferredToTheFollowUp() {
        // The late holder has left, the follow-up has not run yet; a store's
        // close() then makes no SQLite call with the gate closed and no quiesce closing.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 100))
        s.write(1)
        s.assertions.onEnd = null
        val runtime = s.runtime
        val holding = OnAnotherThread { s.store.access("hold") { usleep(400_000u) } }
        usleep(100_000u)
        token.release()
        s.timer.holdDispatches = true
        holding.await()
        s.store.close()
        assertTrue(runtime.hasConnection, "the close was not made")
        assertEquals(1, s.process.registry.deferredCloseCount())
        assertFalse(runtime.isReleased)
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
        assertFalse(runtime.hasConnection, "the follow-up made it")
        assertTrue(runtime.isReleased)
        assertEquals(0, s.process.registry.deferredCloseCount())
    }

    @Test
    fun aDeferredCloseIsMadeAtTheNextAdmittedCallOfTheRegistryOnceProtectionIsBack() {
        // Protection returned before the follow-up, which then closes nothing; the owed
        // close is made by the registry's next admitted call, so the pending release is finished.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 100))
        s.write(1)
        s.assertions.onEnd = null
        val runtime = s.runtime
        val holding = OnAnotherThread { s.store.access("hold") { usleep(400_000u) } }
        usleep(100_000u)
        token.release()
        s.timer.holdDispatches = true
        holding.await()
        s.store.close()
        assertEquals(1, s.process.registry.deferredCloseCount())
        val again = assertNotNull(OnAnotherThread { s.protection.acquire("upload") }.await())
        // The reopen's own retry is dispatched and held here: the registry's next call makes it.
        assertTrue(runtime.hasConnection)
        s.process.registry.release(s.process.registry.register(ReplicaHandleKind.CHECKPOINT, "next"))
        assertFalse(runtime.hasConnection)
        assertTrue(runtime.isReleased)
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
        again.release()
    }

    @Test
    fun aJobsTokenIsNeverTakenOnTheMainThreadOrHoldingARegistryLock() {
        // (86) in code.
        val s = Setup(isForeground = false)
        s.onMainThread = true
        assertFailsWith<IllegalStateException> { s.protection.acquireForJob("wipe") }
        s.onMainThread = false
        val held = OnAnotherThread {
            s.process.registry.lock.lock()
            try {
                runCatching { s.protection.acquireForJob("wipe") }.exceptionOrNull()
            } finally {
                s.process.registry.lock.unlock()
            }
        }.await()
        assertTrue(held is IllegalStateException, "$held")
        assertEquals(emptyList(), s.assertions.begun, "refused before any assertion")
        val token = assertNotNull(OnAnotherThread { s.protection.acquireForJob("wipe") }.await())
        token.release()
    }

    @Test
    fun anExpiryAfterDeadButBeforeTheReleaseEndsItWaits() {
        // A release that has set DEAD but not yet ended the assertion: the expiry waits for it.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        var expiring: OnAnotherThread<Boolean>? = null
        s.protection.afterReleaseDead = {
            s.protection.afterReleaseDead = null
            expiring = OnAnotherThread {
                s.assertions.expire("wake")
                s.assertions.isEnded("wake")
            }
            usleep(150_000u)
        }
        token.release()
        assertEquals(true, assertNotNull(expiring).await(), "the handler did not return with its assertion alive")
    }

    @Test
    fun theReadOnlyClassificationOfAnOpenIsAdmittedToo() {
        val s = Setup(isForeground = false)
        OnAnotherThread { s.process.opener().classifyReadOnly() }.await()
        assertEquals(listOf("implicit"), s.assertions.begun)
    }

    // ── closes inside an admission, deferred-close retries, the handed-off shutdown ──

    @Test
    fun aCloseInsideAnAdmissionWhileTheGateDrainsIsMadeAtOnce() {
        // Should-fix 1: a caller holding an admission is counted by the drain; its close is not
        // deferred, so no connection (or write transaction) outlives it behind a reopened gate.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        val job = s.openJobHandle()
        val gateClosed = NSCondition()
        var closedSeen = false
        val holding = OnAnotherThread {
            s.store.access("hold") {
                gateClosed.lock()
                try {
                    val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0)
                    while (!closedSeen) if (!gateClosed.waitUntilDate(deadline)) break
                } finally {
                    gateClosed.unlock()
                }
                s.process.registry.closeConnection(job)
                job.hasConnection
            }
        }
        usleep(100_000u)
        val releasing = OnAnotherThread { token.release() }
        val deadline = TimeSource.Monotonic.markNow()
        while (s.gate.isOpen && deadline.elapsedNow().inWholeMilliseconds < 2_000) usleep(5_000u)
        gateClosed.lock(); closedSeen = true; gateClosed.broadcast(); gateClosed.unlock()
        assertEquals(false, holding.await(), "closed at once, inside the admission")
        releasing.await()
        assertEquals(0, s.process.registry.deferredCloseCount())
    }

    @Test
    fun aDeferredCloseIsRetriedTheMomentTheGateReopens() {
        // Should-fix 2: the reopen dispatches the retry; no later registry call is needed.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 100))
        s.write(1)
        s.assertions.onEnd = null
        val runtime = s.runtime
        val holding = OnAnotherThread { s.store.access("hold") { usleep(400_000u) } }
        usleep(100_000u)
        token.release()
        s.timer.holdDispatches = true
        holding.await()
        s.store.close()
        assertTrue(runtime.hasConnection)
        val again = assertNotNull(OnAnotherThread { s.protection.acquire("upload") }.await())
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
        assertFalse(runtime.hasConnection, "closed by the reopen's retry")
        assertTrue(runtime.isReleased)
        again.release()
    }

    @Test
    fun aRuntimeHandleRegisteredWhileTheLastOnesReleaseIsOwedIsRetryable() {
        // StoreUnavailable, not a programming error.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 100))
        s.write(1)
        s.assertions.onEnd = null
        val holding = OnAnotherThread { s.store.access("hold") { usleep(400_000u) } }
        usleep(100_000u)
        token.release()
        s.timer.holdDispatches = true
        holding.await()
        s.store.close()
        assertFailsWith<StoreUnavailable> { s.process.registry.register(ReplicaHandleKind.RUNTIME, "rebuild") }
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
    }

    @Test
    fun protectionBackWhileTheHandedOffShutdownWaitsReopensTheGate() {
        // The hand-off's shutdown finds the foreground back and takes the regained path.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.onEnd = null
        s.timer.holdDispatches = true
        OnAnotherThread {
            s.process.registry.lock.lock()
            try {
                token.release()
            } finally {
                s.process.registry.lock.unlock()
            }
        }.await()
        assertFalse(s.gate.isOpen)
        s.protection.enteredForeground()
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
        assertEquals(listOf(ReplicaQuiesceOutcome.REGAINED), s.protection.quiesceOutcomes)
        assertTrue(s.gate.isOpen)
        assertTrue(s.runtime.hasConnection)
        assertTrue(s.assertions.isEnded("wake"))
    }

    @Test
    fun aRegistryIsAttachedBeforeInstallOnly() {
        val s = Setup(isForeground = false)
        val late = ReplicaProcess(tempReplica())
        assertFailsWith<IllegalStateException> { s.protection.attach(late.registry) }
    }

    // ── §4.2.1's admission checks ──

    @Test
    fun theAdmissionArithmeticAtThePlaceholders() {
        val l = ReplicaLimits()
        // own bound + R if it opens + 2·S_full + 2 s, at R = T = 200, S = 100, S_full = 500, HOLD_MAX = 1,000, B_ckpt = 1,250.
        // An open's own bound is its write half's, HOLD_MAX + S, reserved up front.
        assertEquals(4_300, ReplicaAdmissionBudget.requiredMs(ReplicaOperation.OPEN, l, opensConnection = false))
        assertEquals(4_100, ReplicaAdmissionBudget.requiredMs(ReplicaOperation.WRITE, l, opensConnection = false))
        assertEquals(4_300, ReplicaAdmissionBudget.requiredMs(ReplicaOperation.WRITE, l, opensConnection = true))
        assertEquals(4_100, ReplicaAdmissionBudget.requiredMs(ReplicaOperation.JOB_CHUNK, l, opensConnection = false))
        assertEquals(3_500, ReplicaAdmissionBudget.requiredMs(ReplicaOperation.CHECKPOINT_PASSIVE, l, opensConnection = true))
        assertEquals(4_750, ReplicaAdmissionBudget.requiredMs(ReplicaOperation.CHECKPOINT_RESTART, l, opensConnection = true))
    }

    @Test
    fun aWriteInTheBackgroundStartsOnlyWithItsTimeLeftAndIsDeferredWithNothingHeld() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.remainingMs = 4_100
        s.write(2)
        s.assertions.remainingMs = 4_099
        val refused = OnAnotherThread { runCatching { s.store.storeObject(row(3)) }.exceptionOrNull() }.await()
        assertTrue(refused is ReplicaAdmissionDeferred && "WRITE needs 4100 ms" in refused.message.orEmpty(), "$refused")
        assertEquals(0, s.gate.admittedCount, "the admission was given back")
        s.process.sql { sibling ->
            sibling.exec("BEGIN IMMEDIATE")
            sibling.exec("ROLLBACK")
        }
        s.assertions.remainingMs = 30_000
        assertNull(s.store.getObject(3), "nothing was begun")
        assertNull(s.store.fencedBy, "deferred, not fenced")
    }

    @Test
    fun aWriteThatOpensTheConnectionAlsoReservesTheIndexRebuild() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.remainingMs = 4_200
        s.write(2)
        s.process.registry.closeConnection(s.runtime)
        assertFailsWith<ReplicaAdmissionDeferred> { OnAnotherThread { s.store.storeObject(row(3)) }.await() }
        s.assertions.remainingMs = 4_300
        s.write(3)
    }

    @Test
    fun aWriteOnAHandleOwedADeferredCloseReservesTheReopen() {
        // The owed close is made before the admission check, so the write is asked
        // for the index rebuild its reopen will need, not admitted on a connection about to close.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 100))
        s.write(1)
        s.assertions.onEnd = null
        val holding = OnAnotherThread { s.store.access("hold") { usleep(400_000u) } }
        usleep(100_000u)
        token.release()
        s.timer.holdDispatches = true
        holding.await()
        s.process.registry.closeConnection(s.runtime)
        assertEquals(1, s.process.registry.deferredCloseCount())
        val again = assertNotNull(OnAnotherThread { s.protection.acquire("upload") }.await())
        assertTrue(s.runtime.hasConnection, "the reopen's retry is held")
        s.assertions.remainingMs = 4_200
        assertFailsWith<ReplicaAdmissionDeferred> { OnAnotherThread { s.store.storeObject(row(2)) }.await() }
        assertFalse(s.runtime.hasConnection, "the owed close was made first")
        s.assertions.remainingMs = 4_300
        s.write(2)
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
        again.release()
    }

    @Test
    fun anAccessWhoseOwedCloseFinishesItsHandlesReleaseIsAnsweredAsFencedNotAnIllegalState() {
        // Acquire's owed close can finish this handle's own pending release; connectionFor's
        // check(!released) must not then throw IllegalStateException.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 100))
        s.write(1)
        s.assertions.onEnd = null
        val runtime = s.runtime
        val holding = OnAnotherThread { s.store.access("hold") { usleep(400_000u) } }
        usleep(100_000u)
        token.release()
        s.timer.holdDispatches = true
        holding.await()
        s.store.close()
        assertEquals(1, s.process.registry.deferredCloseCount())
        val again = assertNotNull(OnAnotherThread { s.protection.acquire("upload") }.await())
        assertFalse(runtime.isReleased, "the reopen's retry is held")
        assertNull(OnAnotherThread { s.store.getObject(1) }.await(), "answered as a fenced store")
        assertTrue(runtime.isReleased)
        assertEquals(0, s.gate.admittedCount, "the admission was given back")
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
        again.release()
    }

    @Test
    fun aCheckpointWithoutItsTimeDoesNoMarkerWorkOnAnUntrustedMarker() {
        // Looking for the marker before the admission check would make an untrusted one durable
        // (a directory F_FULLFSYNC) with no time reserved.
        val files = RecordingFiles()
        val s = Setup(isForeground = false, files = files)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        // A sibling's marker, not yet durable as far as this process knows.
        PosixReplicaFiles.closeDescriptor(PosixReplicaFiles.createAndWrite("${s.process.directory}/${ReplicaCorruptMarker.NAME}", "sibling".encodeToByteArray()))
        fun checkpoint() = OnAnotherThread {
            assertTrue(s.gate.enter("checkpoint"))
            try {
                s.store.checkpointer.run(SqliteCheckpointMode.PASSIVE)
            } finally {
                s.gate.leave()
            }
        }.await()
        files.calls.clear()
        s.assertions.remainingMs = 3_499
        assertEquals(ReplicaCheckpointOutcome.NotReset, checkpoint(), "skipped")
        assertTrue(files.calls.none { it.startsWith("fullSync") }, "no directory sync: ${files.calls}")
        s.assertions.remainingMs = 3_500
        assertTrue(checkpoint() is ReplicaCheckpointOutcome.Marked, "with its time, a marked store still answers Marked")
        assertTrue(files.calls.any { it.startsWith("fullSync") }, "the marker was made durable then: ${files.calls}")
    }

    @Test
    fun theForegroundHasNoDeadline() {
        val s = Setup(isForeground = true)
        s.assertions.remainingMs = 0
        s.write(1)
        assertNotNull(s.store.getObject(1))
    }

    @Test
    fun anOpenInTheBackgroundReservesItsRebuildAndItsWrite() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.store.close()
        s.assertions.remainingMs = 4_299
        assertFailsWith<ReplicaAdmissionDeferred> { OnAnotherThread { s.process.open() }.await() }
        assertEquals(0, s.gate.admittedCount)
        s.assertions.remainingMs = 4_300
        assertTrue(OnAnotherThread { s.process.open() }.await() is ReplicaOpenResult.Ready)
    }

    @Test
    fun aDeferredOpenDoesNoFileWork() {
        // The open's own check runs before step 0: no objects.db is created for a refused open.
        val process = ReplicaProcess(tempReplica())
        val assertions = FakeAssertions()
        val protection = ReplicaProtection(assertions, FakeTimer(), { false }) {}
        protection.attach(process.registry)
        protection.install(isForeground = false)
        assertNotNull(protection.acquire("wake"))
        // 4,299 ms would let the read-only half run; the open reserves its write half up front.
        assertions.remainingMs = 4_299
        assertFailsWith<ReplicaAdmissionDeferred> { OnAnotherThread { process.open() }.await() }
        assertFalse(fileExists(process.databasePath), "no file work before the admission")
    }

    @Test
    fun aCeilingCheckpointWithoutItsTimeIsSkippedAndTheWriteIsWalResetPendingAtOnce() {
        // C = 8, G = 8, Wmax = 68 pages (ReplicaWalCeilingTest's), B_ckpt at its 1.25 s default.
        val small = ReplicaLimits(wmaxPages = 68, cachePages = 8, statementPages = 8, rowMaxBytes = 8_000)
        val s = Setup(isForeground = false, limits = small)
        assertNotNull(s.protection.acquire("wake"))
        s.write(0)
        val reader = rawConnection(s.process.databasePath).also {
            it.exec("BEGIN")
            it.queryLong("SELECT count(*) FROM objects")
        }
        try {
            var written = 1L
            OnAnotherThread {
                while (s.store.walBytes <= small.beginCeilingBytes) {
                    s.store.storeObject(row(written++, payloadBytes = 2_000))
                    check(written < 500) { "the -wal never reached the ceiling" }
                }
            }.await()
            // A write (4,100 with its connection open) is admitted; RESTART (4,750) is not.
            s.assertions.remainingMs = 4_200
            val started = TimeSource.Monotonic.markNow()
            val refused = OnAnotherThread { runCatching { s.store.storeObject(row(9_999)) }.exceptionOrNull() }.await()
            val tookMs = started.elapsedNow().inWholeMilliseconds
            assertTrue(refused is WalResetPending, "$refused")
            assertTrue(tookMs < small.ceilingWaitMs, "refused before any slice of waiting: $tookMs ms")
            assertEquals(1, s.process.registry.handles().size, "no checkpoint handle was opened")
        } finally {
            reader.exec("ROLLBACK")
            reader.close()
        }
    }

    @Test
    fun aJobChunkWithoutItsTimeStops() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.remainingMs = 4_000
        val outcome = OnAnotherThread { s.process.replacement().run() }.await()
        assertTrue(outcome is ReplicaRunOutcome.Stopped && "JOB_CHUNK" in outcome.toString(), "$outcome")
    }

    @Test
    fun aTimeSourceThatThrowsAdmitsNothing() {
        val s = Setup(isForeground = false)
        assertNotNull(s.protection.acquire("wake"))
        s.write(1)
        s.assertions.remainingThrows = true
        assertFailsWith<ReplicaAdmissionDeferred> { OnAnotherThread { s.store.storeObject(row(2)) }.await() }
        assertTrue(s.logged("[protection] backgroundTimeRemaining threw"))
    }

    @Test
    fun anUnmatchedLeaveChangesNeitherTheCountNorTheOwnersEntryAndADrainStillWaitsForTheOwner() {
        // A leave with no entry of its own must not decrement the count (a quiesce would see zero
        // under the owner's live access, and the owner's leave then drive it negative), nor take
        // another thread's entry.
        val gate = ReplicaGate()
        assertTrue(gate.enter("mine"))
        OnAnotherThread { gate.leave() }.await()
        assertEquals(1, gate.admittedCount, "the count is not given back")
        assertTrue(gate.callerHoldsAdmission(), "this thread's entry is still there")
        gate.beginQuiesce()
        assertEquals(ReplicaDrain.TIMED_OUT, gate.drain(50), "the drain still waits for the owner")
        gate.leave()
        assertEquals(0, gate.admittedCount)
        assertEquals(ReplicaDrain.QUIESCED, gate.drain(50))
        gate.endQuiesce()
        // In tests it is a failed check; the count stays where it was.
        gate.failOnUnmatchedLeave = true
        assertFailsWith<IllegalStateException> { gate.leave() }
        assertEquals(0, gate.admittedCount, "never negative")
    }

    @Test
    fun aReopenBetweenACloseRefusalAndItsQueueingStillGetsTheCloseMade() {
        // The close is refused, a reopen lands in the gap, then the close is queued. The reopen's
        // retry must still make it: it takes the registry's lock, which the closer holds until the
        // close is queued.
        val s = Setup(isForeground = false)
        val token = assertNotNull(s.protection.acquire("wake", drainMs = 100))
        s.write(1)
        s.assertions.onEnd = null
        val runtime = s.runtime
        val holding = OnAnotherThread { s.store.access("hold") { usleep(400_000u) } }
        usleep(100_000u)
        token.release()
        s.timer.holdDispatches = true
        holding.await()
        var again: ReplicaProtectionToken? = null
        s.process.registry.beforeDeferQueued = {
            s.process.registry.beforeDeferQueued = null
            again = OnAnotherThread { s.protection.acquire("upload") }.await()
        }
        s.store.close()
        assertNotNull(again)
        assertTrue(s.gate.isOpen)
        assertTrue(runtime.hasConnection, "queued, not yet made")
        s.timer.releaseHeld()
        s.timer.awaitDispatched()
        assertFalse(runtime.hasConnection, "the reopen's retry made it")
        assertTrue(runtime.isReleased)
        assertNotNull(again).release()
    }
}
