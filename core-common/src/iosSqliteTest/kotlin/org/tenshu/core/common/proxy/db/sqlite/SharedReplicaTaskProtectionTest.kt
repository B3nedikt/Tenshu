@file:OptIn(SharedReplicaPlatformApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlin.concurrent.AtomicInt
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Appendix B.23: **a `BGTask`'s hold on the shared store** ([SharedReplicaTaskProtection]). The
 * token ends before the task is completed, as a release when the work is done and as an expiry
 * after the task's `expirationHandler`; each end happens once, a second caller waits for it, and an
 * expiry that arrives before the token was handed over is kept. Fake tokens record one timeline.
 */
class SharedReplicaTaskProtectionTest {

    private class Timeline {
        private val lock = NSRecursiveLock()
        private val events = ArrayList<String>()
        fun add(event: String) {
            lock.lock()
            events += event
            lock.unlock()
        }

        fun all(): List<String> {
            lock.lock()
            try {
                return events.toList()
            } finally {
                lock.unlock()
            }
        }
    }

    private class Token(private val timeline: Timeline, private val whileExpiring: () -> Unit = {}) : SharedReplicaTaskToken {
        override fun release() = timeline.add("release")
        override fun expire() {
            whileExpiring()
            timeline.add("expire")
        }
    }

    private class OnAnotherThread(block: () -> Unit) {
        private val condition = NSCondition()
        private var done = false

        init {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                block()
                condition.lock()
                done = true
                condition.broadcast()
                condition.unlock()
            }
        }

        val isDone: Boolean get() = condition.run { lock(); try { done } finally { unlock() } }

        fun await(timeoutMs: Long = 10_000) {
            condition.lock()
            try {
                val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
                while (!done) if (!condition.waitUntilDate(deadline)) fail("still running after $timeoutMs ms")
            } finally {
                condition.unlock()
            }
        }
    }

    private fun within(timeoutMs: Long = 5_000, what: String, done: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!done()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMs) fail("$what: not within $timeoutMs ms")
            usleep(2_000u)
        }
    }

    private fun protection(timeline: Timeline, token: SharedReplicaTaskToken? = Token(timeline), isMain: Boolean = false, offMain: (() -> Unit) -> Unit = { fail("hopped off main") }) =
        SharedReplicaTaskProtection("made-up", acquire = { timeline.add("begin"); token }, isMainThread = { isMain }, offMain = offMain)

    @Test
    fun theTokenIsReleasedBeforeTheTaskIsCompleted() {
        val timeline = Timeline()
        val hold = protection(timeline)
        assertTrue(hold.begin())
        hold.finish { timeline.add("complete") }
        assertEquals(listOf("begin", "release", "complete"), timeline.all())
        // A second end, and an expiry after it, touch the token no more.
        hold.endToken()
        hold.expire()
        hold.endToken()
        assertEquals(listOf("begin", "release", "complete"), timeline.all())
    }

    @Test
    fun afterTheExpirationHandlerTheTokenIsExpiredNotReleasedAndThenTheTaskCompleted() {
        val timeline = Timeline()
        val hold = protection(timeline)
        assertTrue(hold.begin())
        hold.expire()
        assertEquals(listOf("begin"), timeline.all(), "the mark ends nothing: the owner's cancel comes first")
        hold.finish { timeline.add("complete") }
        hold.endToken()
        assertEquals(listOf("begin", "expire", "complete"), timeline.all())
    }

    @Test
    fun anExpiryWithNoCompletionFromTheOwnerStillEndsTheToken() {
        val timeline = Timeline()
        val hold = protection(timeline)
        assertTrue(hold.begin())
        hold.expire()
        hold.endToken()
        assertEquals(listOf("begin", "expire"), timeline.all())
        // A completion that comes later (a graph torn down) completes, and ends nothing twice.
        hold.finish { timeline.add("complete") }
        assertEquals(listOf("begin", "expire", "complete"), timeline.all())
    }

    /** A token source that blocks until [open] is called: `begin()` is in flight meanwhile. */
    private class BlockedSource(private val timeline: Timeline) {
        private val condition = NSCondition()
        private var opened = false
        val entered = AtomicInt(0)

        fun take(): SharedReplicaTaskToken {
            entered.value = 1
            condition.lock()
            while (!opened) condition.wait()
            condition.unlock()
            timeline.add("granted")
            return Token(timeline)
        }

        fun open() {
            condition.lock()
            opened = true
            condition.broadcast()
            condition.unlock()
        }
    }

    @Test
    fun anExpiryWhileTheTokenIsStillBeingTakenWaitsForItAndExpiresIt() {
        // Photos' real sequence: the expirationHandler marks the hold and ends
        // it at once, while `begin()` is still inside the token's source (a gate still reopening).
        val timeline = Timeline()
        val source = BlockedSource(timeline)
        val hold = SharedReplicaTaskProtection("made-up", acquire = { source.take() }, isMainThread = { false })
        val beginning = OnAnotherThread { assertTrue(hold.begin()) }
        within(what = "begin takes the token") { source.entered.value == 1 }
        val expiring = OnAnotherThread {
            hold.expire()
            hold.endToken()
            timeline.add("handler returned")
        }
        usleep(100_000u)
        assertFalse(expiring.isDone, "the expirationHandler returned with a token still to come")
        source.open()
        beginning.await()
        expiring.await()
        assertEquals(listOf("granted", "expire", "handler returned"), timeline.all(), "the late grant is expired, never released")
        // The owner's completion, after: ends nothing twice.
        hold.finish { timeline.add("complete") }
        assertEquals(listOf("granted", "expire", "handler returned", "complete"), timeline.all())
    }

    @Test
    fun aCompletionWhileTheTokenIsStillBeingTakenComesAfterTheTokenEnded() {
        // A teardown completes the task while `begin()` is in flight: the token is released first,
        // and only then is the task completed.
        val timeline = Timeline()
        val source = BlockedSource(timeline)
        val hold = SharedReplicaTaskProtection("made-up", acquire = { source.take() }, isMainThread = { false })
        val beginning = OnAnotherThread { hold.begin() }
        within(what = "begin takes the token") { source.entered.value == 1 }
        val finishing = OnAnotherThread { hold.finish { timeline.add("complete") } }
        usleep(100_000u)
        assertFalse(finishing.isDone, "completed with the token still being taken")
        source.open()
        beginning.await()
        finishing.await()
        assertEquals(listOf("granted", "release", "complete"), timeline.all())
    }

    @Test
    fun aHoldEndedBeforeItBeganTakesNoToken() {
        val timeline = Timeline()
        val hold = protection(timeline)
        hold.expire()
        hold.endToken()
        assertFalse(hold.begin(), "a launch after its expiry carries no store work")
        assertEquals(emptyList(), timeline.all())
    }

    @Test
    fun anExpiryDuringAReleaseTurnsItIntoAnExpiryAndWaitsForIt() {
        val timeline = Timeline()
        val gate = NSCondition()
        var go = false
        val token = object : SharedReplicaTaskToken {
            override fun release() {
                timeline.add("releasing")
                gate.lock()
                // Bounded, so a missing expiry fails the case rather than hanging it.
                val deadline = NSDate.dateWithTimeIntervalSinceNow(3.0)
                while (!go) if (!gate.waitUntilDate(deadline)) break
                gate.unlock()
                timeline.add("released")
            }

            override fun expire() {
                timeline.add("expire")
                gate.lock()
                go = true
                gate.broadcast()
                gate.unlock()
            }
        }
        val hold = protection(timeline, token)
        assertTrue(hold.begin())
        val finishing = OnAnotherThread { hold.finish { timeline.add("complete") } }
        within(what = "the release runs") { "releasing" in timeline.all() }
        hold.expire()
        hold.endToken()
        timeline.add("handler returned")
        finishing.await()
        val events = timeline.all()
        assertEquals(listOf("begin", "releasing", "expire"), events.take(3), "the release was not turned into an expiry: $events")
        assertTrue(events.indexOf("released") < events.indexOf("handler returned"), "the handler returned with the release in flight: $events")
        assertTrue(events.indexOf("released") < events.indexOf("complete"), events.toString())
    }

    // ── over the real protection: no instant fakes ──

    private class Real(isForeground: Boolean = false) {
        val process = ReplicaProcess(tempReplica())
        val protection = ReplicaProtection(
            object : ProtectionAssertions {
                override fun begin(reason: String, onExpired: () -> Unit): Long = 1
                override fun end(id: Long) = Unit
                override fun backgroundTimeRemainingMs(): Long = 60_000
            },
            DispatchProtectionTimer,
            isMainThread = { false },
            log = {},
        )
        val store: SharedReplicaStore = process.store()

        init {
            protection.attach(process.registry)
            protection.install(isForeground)
        }

        fun hold(): SharedReplicaTaskProtection =
            SharedReplicaTaskProtection("made-up", acquire = { onExpired -> protection.acquireTask("task:made-up", onExpired)?.let(::TaskToken) }, isMainThread = { false })

        val runtimeConnection: SqliteConnection
            get() = assertNotNull(process.registry.handles().single { it.kind == ReplicaHandleKind.RUNTIME }.connection)

        /** An access whose INSERT runs a statement only an interrupt stops (its own stop after 8 s). */
        fun accessStuckInAStatement(running: AtomicInt): OnAnotherThreadResult<Throwable?> {
            store.statementFault = { sql ->
                if (sql.startsWith("INSERT INTO objects")) {
                    val started = TimeSource.Monotonic.markNow()
                    runtimeConnection.stoppableBy(everyOps = 10_000, shouldStop = {
                        running.value = 1
                        started.elapsedNow().inWholeMilliseconds > 8_000
                    }) {
                        runtimeConnection.exec("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 2000000000) SELECT count(*) FROM c")
                    }
                }
            }
            return OnAnotherThreadResult { runCatching { store.storeObject(row(2)) }.exceptionOrNull() }
        }
    }

    private class OnAnotherThreadResult<T>(block: () -> T) {
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

        fun await(timeoutMs: Long = 30_000): T {
            condition.lock()
            try {
                val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
                while (result == null) if (!condition.waitUntilDate(deadline)) break
                return (result ?: fail("still running after $timeoutMs ms")).getOrThrow()
            } finally {
                condition.unlock()
            }
        }
    }

    @Test
    fun overTheRealProtectionAnExpiryDuringTheReleasesDrainInterruptsLatchesAndEndsBeforeTheHandlerReturns() {
        val s = Real()
        val hold = s.hold()
        assertTrue(hold.begin())
        s.store.storeObject(row(1))
        val running = AtomicInt(0)
        val access = s.accessStuckInAStatement(running)
        within(what = "the statement runs") { running.value == 1 }
        val completed = AtomicInt(0)
        // The pass ended: its release quiesces, and its 2 s drain waits on the running statement.
        val finishing = OnAnotherThread { hold.finish { completed.value = 1 } }
        within(what = "the release drains") { s.process.registry.gate.isQuiescing }
        // The expirationHandler, Photos' order: mark, then end.
        val started = TimeSource.Monotonic.markNow()
        hold.expire()
        hold.endToken()
        val tookMs = started.elapsedNow().inWholeMilliseconds
        assertIs<ProtectionExpired>(access.await(), "the release was not turned into an expiry: nothing interrupted")
        assertTrue(s.protection.isLatched, "the store is not latched after the task expired")
        assertTrue(s.protection.expiryInterrupts.isNotEmpty())
        assertEquals(0, s.process.registry.gate.admittedCount, "the handler returned with the drain still running")
        assertFalse(s.process.registry.gate.isQuiescing, "the handler returned with the quiesce still running")
        assertTrue(tookMs < 3_000, "$tookMs ms")
        finishing.await()
        assertEquals(1, completed.value)
        ReplicaProcess(s.process.directory).sql { it.exec("BEGIN IMMEDIATE"); it.exec("ROLLBACK") }
    }

    @Test
    fun overTheRealProtectionAnExpiryWhileTheTokenIsBeingTakenExpiresTheLateGrant() {
        val s = Real()
        val entered = AtomicInt(0)
        val gate = NSCondition()
        var open = false
        val hold = SharedReplicaTaskProtection(
            "made-up",
            acquire = { onExpired ->
                entered.value = 1
                gate.lock()
                while (!open) gate.wait()
                gate.unlock()
                s.protection.acquireTask("task:made-up", onExpired)?.let(::TaskToken)
            },
            isMainThread = { false },
        )
        val beginning = OnAnotherThread { hold.begin() }
        within(what = "begin takes the token") { entered.value == 1 }
        val handler = OnAnotherThread {
            hold.expire()
            hold.endToken()
        }
        usleep(100_000u)
        assertFalse(handler.isDone, "the expirationHandler returned with a token still to come")
        gate.lock()
        open = true
        gate.broadcast()
        gate.unlock()
        beginning.await()
        handler.await()
        assertFalse(s.protection.isProtected, "the store is still protected after the handler returned")
        assertTrue(s.protection.isLatched, "the late grant was released, not expired: no latch")
    }

    @Test
    fun overTheRealProtectionAnExpiryThatWinsTheRaceIntoTheReleaseIsWaitedForBeforeTheCompletion() {
        // The release is chosen, then the expiry is escalated and marks the token DEAD before the
        // release is entered: the release meets a DEAD token and must still wait for the expiry,
        // so the task is never completed with the expiry still draining.
        val s = Real()
        val timeline = Timeline()
        s.protection.betweenExpiryEndAndReopen = {
            timeline.add("quiesce ending")
            usleep(300_000u)
            timeline.add("quiesce ended")
        }
        val expireCalled = AtomicInt(0)
        val hold = SharedReplicaTaskProtection(
            "made-up",
            acquire = { onExpired ->
                val real = assertNotNull(s.protection.acquireTask("task:made-up", onExpired)).let(::TaskToken)
                object : SharedReplicaTaskToken {
                    override fun release() {
                        // Forces the interleaving: the escalated expiry has taken the token from LIVE.
                        within(what = "the escalated expiry takes the token") { expireCalled.value == 1 && s.protection.liveTokenCount == 0 }
                        real.release()
                    }

                    override fun expire() {
                        expireCalled.value = 1
                        real.expire()
                    }
                }
            },
            isMainThread = { false },
        )
        assertTrue(hold.begin())
        val finishing = OnAnotherThread { hold.finish { timeline.add("complete") } }
        usleep(50_000u)
        hold.expire()
        hold.endToken()
        timeline.add("handler returned")
        finishing.await()
        val events = timeline.all()
        assertTrue(events.indexOf("quiesce ended") in 0 until events.indexOf("complete"), "the task was completed with the expiry still draining: $events")
        assertTrue(events.indexOf("quiesce ended") < events.indexOf("handler returned"), events.toString())
        assertTrue(s.protection.isLatched)
    }

    @Test
    fun aTaskTokenIsNeverEndedByARegistryLockHolder() {
        val s = Real()
        val token = assertNotNull(s.protection.acquireTask("task:made-up"))
        s.process.registry.lock.lock()
        try {
            assertFailsWith<IllegalStateException> { token.release() }
            assertFailsWith<IllegalStateException> { token.expire() }
        } finally {
            s.process.registry.lock.unlock()
        }
        assertTrue(s.protection.isProtected, "nothing was handed off")
        token.release()
        assertFalse(s.protection.isProtected)
    }

    @Test
    fun aCompletionThatWaitsForAnExpiryInFlightComesAfterIt() {
        val timeline = Timeline()
        val release = NSCondition()
        var go = false
        val token = Token(timeline) {
            timeline.add("draining")
            release.lock()
            while (!go) release.wait()
            release.unlock()
        }
        val hold = protection(timeline, token)
        assertTrue(hold.begin())
        hold.expire()
        val expiring = OnAnotherThread { hold.endToken() }
        within(what = "the expiry drains") { "draining" in timeline.all() }
        val completing = OnAnotherThread { hold.finish { timeline.add("complete") } }
        usleep(100_000u)
        assertFalse(completing.isDone, "the completion did not wait for the drain")
        release.lock()
        go = true
        release.broadcast()
        release.unlock()
        expiring.await()
        completing.await()
        assertEquals(listOf("begin", "draining", "expire", "complete"), timeline.all())
    }

    @Test
    fun aReleaseOnTheMainThreadIsMovedOffItWithTheCompletion() {
        val timeline = Timeline()
        val hopped = ArrayList<() -> Unit>()
        val hold = protection(timeline, isMain = true, offMain = { hopped += it })
        assertTrue(hold.begin())
        hold.finish { timeline.add("complete") }
        assertEquals(listOf("begin"), timeline.all(), "a release that may quiesce ran on main")
        hopped.single()()
        assertEquals(listOf("begin", "release", "complete"), timeline.all())
    }

    @Test
    fun noTokenMeansTheTaskCarriesNoStoreWork() {
        val timeline = Timeline()
        val hold = protection(timeline, token = null)
        assertFalse(hold.begin())
        assertTrue(hold.protectsTheStore)
    }

    @Test
    fun withNoBindingItProtectsNothingAndPassesThrough() {
        val hold = SharedReplicaTaskProtection("made-up", acquire = null)
        assertFalse(hold.protectsTheStore)
        assertTrue(hold.begin())
        var completed = 0
        hold.finish { completed++ }
        assertEquals(1, completed)
    }
}
