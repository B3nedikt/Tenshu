@file:OptIn(SharedReplicaPlatformApi::class)

package org.tenshu.sdk.ios

import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.CLOCK_UPTIME_RAW
import platform.posix.clock_gettime_nsec_np
import kotlin.math.abs
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Appendix B.12: the UIKit adapter over fakes of `UIApplication`, the worker's queue and the clock.
 * Real UIKit is covered only by the compile and the Swift build. Every name is made up.
 *
 * "The main thread" is the test's own thread: the fake application answers `isMainThread` for it
 * alone, runs nothing on it unless the test drains it, and records any main-only call made from
 * another thread.
 */
class SharedReplicaUiKitAdapterTest {

    private class FakeApp : UiKitApplication {
        private val main: NSThread = NSThread.currentThread
        private val condition = NSCondition()

        // ⚠ `==`, never `===`, on an NSThread (K/N).
        override val isMainThread: Boolean get() = NSThread.currentThread == main

        var inBackground = false
        var remainingSeconds = 30.0
        var grant = true
        private var nextId = 0uL

        val violations = ArrayList<String>()
        val begun = ArrayList<Pair<String, Boolean>>()
        val handlers = HashMap<ULong, () -> Unit>()
        val ended = ArrayList<Pair<ULong, Boolean>>()
        val pendingMain = ArrayList<() -> Unit>()
        var remainingReads = 0
        private var onBackground: (() -> Unit)? = null
        private var onForeground: (() -> Unit)? = null

        private inline fun <T> locked(block: () -> T): T {
            condition.lock()
            try {
                return block()
            } finally {
                condition.broadcast()
                condition.unlock()
            }
        }

        override fun isInBackground(): Boolean {
            if (!isMainThread) locked { violations += "applicationState off the main thread" }
            return inBackground
        }

        override fun beginBackgroundTask(name: String, onExpired: () -> Unit): ULong? = locked {
            begun += name to isMainThread
            if (!grant) return@locked null
            val id = ++nextId
            handlers[id] = onExpired
            id
        }

        override fun endBackgroundTask(id: ULong) {
            locked { ended += id to isMainThread }
        }

        override fun backgroundTimeRemainingSeconds(): Double = locked {
            remainingReads++
            if (!isMainThread) violations += "backgroundTimeRemaining off the main thread"
            remainingSeconds
        }

        override fun observeLifecycle(onDidEnterBackground: () -> Unit, onWillEnterForeground: () -> Unit) {
            onBackground = onDidEnterBackground
            onForeground = onWillEnterForeground
        }

        override fun runOnMainAsync(block: () -> Unit) {
            locked { pendingMain += block }
        }

        /** The test, on the main thread: the main queue runs what was queued on it. */
        fun drainMain() {
            check(isMainThread)
            val blocks = locked { pendingMain.toList().also { pendingMain.clear() } }
            blocks.forEach { it() }
        }

        fun postDidEnterBackground() {
            inBackground = true
            onBackground?.invoke() ?: fail("not observed")
        }

        fun postWillEnterForeground() {
            inBackground = false
            onForeground?.invoke() ?: fail("not observed")
        }

        /** The platform's expiration handler of [id], called on the main thread as iOS does. */
        fun expire(id: ULong) {
            check(isMainThread)
            val handler = locked { handlers[id] } ?: fail("no assertion $id")
            handler()
        }

        fun endedIds(): List<ULong> = locked { ended.map { it.first } }

        fun awaitEnded(id: ULong, timeoutMs: Long = 10_000): Pair<ULong, Boolean> {
            condition.lock()
            try {
                val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
                while (true) {
                    ended.firstOrNull { it.first == id }?.let { return it }
                    if (!condition.waitUntilDate(deadline)) fail("assertion $id was not ended within $timeoutMs ms")
                }
            } finally {
                condition.unlock()
            }
        }
    }

    private class FakeQueue : ReplicaWorkerQueue {
        val dispatched = ArrayList<() -> Unit>()
        val timers = ArrayList<FakeTimer>()

        class FakeTimer(val intervalMs: Long, private val block: () -> Unit) : ReplicaRepeatingTimer {
            var cancelled = false
            override fun cancel() {
                cancelled = true
            }

            /** One interval passes. A cancelled timer fires nothing. */
            fun fire() {
                if (!cancelled) block()
            }
        }

        override fun dispatch(block: () -> Unit) {
            dispatched += block
        }

        override fun repeating(intervalMs: Long, block: () -> Unit): ReplicaRepeatingTimer =
            FakeTimer(intervalMs, block).also { timers += it }

        fun live(): List<FakeTimer> = timers.filter { !it.cancelled }
    }

    private class FakeDriver : ReplicaLifecycleDriver {
        val calls = ArrayList<String>()
        var onEnteredBackground: (() -> Unit)? = null
        var tickThrows = false
        override fun install(isForeground: Boolean) {
            calls += "install($isForeground)"
        }

        override fun enteredForeground() {
            calls += "enteredForeground"
        }

        override fun enteredBackground() {
            calls += "enteredBackground"
            onEnteredBackground?.invoke()
        }

        override fun kick() {
            calls += "kick"
        }

        override fun foregroundCheckpointTick(): Int {
            calls += "tick"
            if (tickThrows) throw IllegalStateException("a made-up tick failure")
            return 0
        }
    }

    private class Clock {
        var nowMs = 1_000L
        val read: () -> Long = { nowMs }
    }

    private fun adapter(app: FakeApp, queue: FakeQueue = FakeQueue(), clock: Clock = Clock()) =
        SharedReplicaUiKitAdapter(app, queue, clock.read, log = {}).also { it.recordSamples = true }

    /** Runs [block] on a real background thread while the main thread waits, draining nothing. */
    private fun <T> offMainWhileMainIsBlocked(timeoutMs: Long = 5_000, block: () -> T): T {
        val condition = NSCondition()
        var result: Result<T>? = null
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val r = runCatching(block)
            condition.lock()
            result = r
            condition.broadcast()
            condition.unlock()
        }
        condition.lock()
        try {
            val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
            while (result == null) {
                if (!condition.waitUntilDate(deadline)) fail("the background call waited for the blocked main thread (> $timeoutMs ms)")
            }
        } finally {
            condition.unlock()
        }
        return result!!.getOrThrow()
    }

    // ── Q3: the token on main, in the notification; the quiesce off it ──

    @Test
    fun theBackgroundTokenIsTakenOnTheMainThreadInsideTheNotificationAndItsQuiesceRunsOffIt() {
        val app = FakeApp()
        val adapter = adapter(app)
        adapter.install()
        assertTrue(adapter.lifecycle.isForeground)

        app.postDidEnterBackground()
        // Synchronously, before the notification returned, on the main thread.
        assertEquals(listOf("background" to true), app.begun.toList())
        assertFalse(adapter.lifecycle.isForeground)

        // Its release, the quiesce, ends the assertion off the main thread (the main thread is
        // free meanwhile, but it is never asked to do it).
        val (_, endedOnMain) = app.awaitEnded(1uL)
        assertFalse(endedOnMain, "the quiesce and the end of its assertion ran on the main thread")
        assertTrue(app.violations.isEmpty(), "${app.violations}")
    }

    @Test
    fun theTransitionsDriveTheLifecycleInOrderAndAForegroundKicksTheWorkerAfterItOpens() {
        val app = FakeApp()
        val driver = FakeDriver()
        adapter(app).install(driver)
        app.postDidEnterBackground()
        app.postWillEnterForeground()
        assertEquals(listOf("install(true)", "enteredBackground", "enteredForeground", "kick"), driver.calls)
    }

    @Test
    fun aBackgroundLaunchInstallsInTheBackgroundWithNoTimerAndNoKick() {
        val app = FakeApp().apply { inBackground = true }
        val driver = FakeDriver()
        val queue = FakeQueue()
        adapter(app, queue).install(driver)
        assertEquals(listOf("install(false)"), driver.calls)
        assertTrue(queue.timers.isEmpty())
    }

    @Test
    fun aWakeKicksTheWorkerFromAnyThreadAndBeforeInstallIsIgnored() {
        val app = FakeApp()
        val driver = FakeDriver()
        val adapter = adapter(app)
        adapter.wake()
        adapter.install(driver)
        offMainWhileMainIsBlocked { adapter.wake() }
        assertEquals(listOf("install(true)", "kick"), driver.calls)
    }

    @Test
    fun installIsOnTheMainThreadAndOnce() {
        val app = FakeApp()
        val adapter = adapter(app)
        val offMain = offMainWhileMainIsBlocked { runCatching { adapter.install(FakeDriver()) }.exceptionOrNull() }
        assertTrue(offMain is IllegalStateException, "$offMain")
        adapter.install(FakeDriver())
        assertFailsWith<IllegalStateException> { adapter.install(FakeDriver()) }
    }

    // ── backgroundTimeRemainingMs: any thread, never main ──

    @Test
    fun backgroundTimeRemainingFromABackgroundThreadNeverTouchesTheMainThread() {
        val app = FakeApp().apply {
            inBackground = true
            remainingSeconds = 25.0
        }
        val clock = Clock()
        val adapter = adapter(app, clock = clock)
        adapter.install(FakeDriver())
        val readsOnMain = app.remainingReads
        clock.nowMs += 4_000

        // The main thread is blocked and drains nothing: a dispatch_sync to it would never return.
        val remaining = offMainWhileMainIsBlocked { adapter.backgroundTimeRemainingMs() }
        assertEquals(21_000, remaining)
        assertEquals(readsOnMain, app.remainingReads, "the platform was asked off the main thread")

        // An assertion begun off the main thread samples on main LATER, never by waiting for it.
        val id = offMainWhileMainIsBlocked { adapter.begin("a made-up wake") {} }
        assertNotNull(id)
        assertEquals(readsOnMain, app.remainingReads)
        assertEquals(1, app.pendingMain.size)
        app.remainingSeconds = 170.0
        app.drainMain()
        assertEquals(170_000, adapter.backgroundTimeRemainingMs())
        assertTrue(app.violations.isEmpty(), "${app.violations}")
    }

    @Test
    fun theRemainingTimeIsTheLastMainSampleLessTheMonotonicTimeSinceAndNeverBelowZero() {
        val app = FakeApp().apply { remainingSeconds = 25.0 }
        val clock = Clock()
        val adapter = adapter(app, clock = clock)
        assertEquals(0, adapter.backgroundTimeRemainingMs(), "no sample yet: no time anybody can name")
        adapter.install(FakeDriver())
        assertEquals(25_000, adapter.backgroundTimeRemainingMs())
        clock.nowMs += 10_000
        assertEquals(15_000, adapter.backgroundTimeRemainingMs())
        clock.nowMs += 20_000
        assertEquals(0, adapter.backgroundTimeRemainingMs())

        // Each transition samples on main again.
        app.remainingSeconds = 29.0
        app.postDidEnterBackground()
        assertEquals(29_000, adapter.backgroundTimeRemainingMs())
    }

    @Test
    fun noDeadlineIsLongMaxValueHoweverLongAgoItWasRead() {
        val app = FakeApp().apply { remainingSeconds = Double.MAX_VALUE }
        val clock = Clock()
        val adapter = adapter(app, clock = clock)
        adapter.install(FakeDriver())
        clock.nowMs += 5_000
        assertEquals(Long.MAX_VALUE, adapter.backgroundTimeRemainingMs())
        assertEquals(listOf<Long?>(null), adapter.samples)
    }

    @Test
    fun theConversionClampsDblMaxInfinityNanAndNegatives() {
        assertNull(SharedReplicaUiKitAdapter.remainingSecondsToMs(Double.MAX_VALUE))
        assertNull(SharedReplicaUiKitAdapter.remainingSecondsToMs(Double.POSITIVE_INFINITY))
        assertNull(SharedReplicaUiKitAdapter.remainingSecondsToMs(SharedReplicaUiKitAdapter.NO_DEADLINE_SECONDS))
        assertEquals(0, SharedReplicaUiKitAdapter.remainingSecondsToMs(Double.NaN))
        assertEquals(0, SharedReplicaUiKitAdapter.remainingSecondsToMs(-1.0))
        assertEquals(0, SharedReplicaUiKitAdapter.remainingSecondsToMs(0.0))
        assertEquals(29_500, SharedReplicaUiKitAdapter.remainingSecondsToMs(29.5))
        assertEquals(3_600_000, SharedReplicaUiKitAdapter.remainingSecondsToMs(3_600.0))
    }

    // ── assertions and expiry ──

    @Test
    fun anAssertionIsOneBackgroundTaskAndNoTimeGrantedIsNull() {
        val app = FakeApp()
        val adapter = adapter(app)
        val id = assertNotNull(adapter.begin("a made-up job") {})
        assertEquals(listOf("a made-up job" to true), app.begun.toList())
        adapter.end(id)
        assertEquals(listOf(id.toULong()), app.endedIds())
        app.grant = false
        assertNull(adapter.begin("refused") {})
    }

    @Test
    fun thePlatformsExpirationHandlerCallsTheStoresAndSamplesOnMain() {
        val app = FakeApp().apply { remainingSeconds = 5.0 }
        val adapter = adapter(app)
        var expiredOnMain: Boolean? = null
        val id = assertNotNull(adapter.begin("a made-up job") { expiredOnMain = NSThread.isMainThread || app.isMainThread })
        app.remainingSeconds = 0.4
        app.expire(id.toULong())
        assertEquals(true, expiredOnMain)
        assertEquals(400, adapter.samples.last())
    }

    @Test
    fun theBackgroundTokensExpiryReturnsOnlyWithItsAssertionEndedOnce() {
        val app = FakeApp()
        val adapter = adapter(app)
        adapter.install()
        app.postDidEnterBackground()
        // However far its release had got: iOS ends the process if a handler returns with its
        // assertion alive.
        app.expire(1uL)
        assertEquals(listOf(1uL), app.endedIds().filter { it == 1uL })
        assertFalse(adapter.lifecycle.isForeground)
    }

    // ── the 30 s foreground checkpoint timer ──

    @Test
    fun theThirtySecondTimerRunsOnTheWorkersQueueOnlyWhileForegrounded() {
        val app = FakeApp()
        val driver = FakeDriver()
        val queue = FakeQueue()
        val adapter = adapter(app, queue)
        adapter.install(driver)
        val first = queue.live().single()
        assertEquals(30_000, first.intervalMs)
        assertTrue(adapter.checkpointTimerRunning)
        first.fire()
        first.fire()
        assertEquals(2, driver.calls.count { it == "tick" })

        app.postDidEnterBackground()
        assertTrue(first.cancelled, "the timer outlived the foreground")
        assertTrue(queue.live().isEmpty())
        assertFalse(adapter.checkpointTimerRunning)

        app.postWillEnterForeground()
        val second = queue.live().single()
        second.fire()
        assertEquals(3, driver.calls.count { it == "tick" })
        // A second foreground notification starts no second timer.
        app.postWillEnterForeground()
        assertEquals(1, queue.live().size)
    }

    // ── the clock, the sample's ordering, and the timer's guard ──

    @Test
    fun theDefaultClockKeepsCountingThroughSleep() {
        // B.12 (165): the continuous clock, CLOCK_MONOTONIC_RAW, never the uptime one.
        val raw = (clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()) / 1_000_000uL).toLong()
        val uptime = (clock_gettime_nsec_np(CLOCK_UPTIME_RAW.toUInt()) / 1_000_000uL).toLong()
        val clock = SharedReplicaUiKitAdapter.continuousClockMs()
        assertTrue(abs(clock - raw) <= 1_000, "the clock ($clock) is not CLOCK_MONOTONIC_RAW ($raw)")
        // Distinguishable only on a host that has slept since boot (the simulator shares the Mac's clocks).
        if (raw - uptime > 2_000) assertTrue(abs(clock - uptime) > 1_000, "the clock tracks the uptime clock ($uptime)")
    }

    @Test
    fun leavingTheForegroundSamplesBeforeTheStoreFlipsToTheBackground() {
        // A reader between the flip and the post-sample must not see the foreground's "no deadline".
        val app = FakeApp().apply { remainingSeconds = Double.MAX_VALUE }
        val driver = FakeDriver()
        val adapter = adapter(app)
        var seenAtTheFlip: Long? = null
        driver.onEnteredBackground = { seenAtTheFlip = adapter.backgroundTimeRemainingMs() }
        adapter.install(driver)
        app.remainingSeconds = 25.0
        app.postDidEnterBackground()
        assertEquals(25_000, seenAtTheFlip)
    }

    @Test
    fun aForegroundBeforeTheBackgroundsQuiesceEndsLeavesTheProcessProtectedAndTheAssertionEndedOnce() {
        // The store half (an attached store, its gate open and an access served) is
        // ReplicaForegroundCheckpointTest's: attaching a registry is internal to :core-common (B.12.3).
        val app = FakeApp()
        val adapter = adapter(app)
        adapter.install()
        app.postDidEnterBackground()
        app.postWillEnterForeground()
        assertTrue(adapter.lifecycle.isForeground)
        val (_, endedOnMain) = app.awaitEnded(1uL)
        assertFalse(endedOnMain)
        assertEquals(listOf(1uL), app.endedIds())
        assertTrue(adapter.checkpointTimerRunning)
    }

    @Test
    fun outsideTheTestsNoSampleIsKept() {
        val app = FakeApp()
        val adapter = SharedReplicaUiKitAdapter(app, FakeQueue(), Clock().read, log = {})
        adapter.install(FakeDriver())
        app.postDidEnterBackground()
        assertTrue(adapter.samples.isEmpty(), "${adapter.samples}")
        assertEquals(30_000, adapter.backgroundTimeRemainingMs(), "the sample itself is still taken")
    }

    @Test
    fun aThrowingTickIsLoggedAndTheTimerKeepsRunning() {
        val app = FakeApp()
        val driver = FakeDriver().apply { tickThrows = true }
        val queue = FakeQueue()
        adapter(app, queue).install(driver)
        val timer = queue.live().single()
        timer.fire()
        timer.fire()
        assertEquals(2, driver.calls.count { it == "tick" })
    }

    @Test
    fun theDispatchTimersChainSurvivesAThrowingBlock() {
        val condition = NSCondition()
        var escaped = 0
        // What escapes the queue's own guard is caught here, so a missing guard fails this test
        // rather than ending the test process.
        val queue = DispatchReplicaWorkerQueue("org.tenshu.test.made-up-queue") { tick ->
            try {
                tick()
            } catch (_: Throwable) {
                condition.lock()
                escaped++
                condition.unlock()
            }
        }
        var fires = 0
        val timer = queue.repeating(10) {
            condition.lock()
            fires++
            condition.broadcast()
            condition.unlock()
            throw IllegalStateException("a made-up tick failure")
        }
        condition.lock()
        try {
            val deadline = NSDate.dateWithTimeIntervalSinceNow(30.0)
            while (fires < 3) if (!condition.waitUntilDate(deadline)) fail("the chain stopped after $fires fires")
            assertEquals(0, escaped, "a tick's throw escaped the queue's own guard")
        } finally {
            condition.unlock()
        }
        timer.cancel()
    }
}
