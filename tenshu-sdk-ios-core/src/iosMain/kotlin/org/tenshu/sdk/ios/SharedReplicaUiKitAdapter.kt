@file:OptIn(SharedReplicaPlatformApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaBackgroundTime
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaJobOutcome
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaLoops
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaLifecycle
import org.tenshu.core.common.proxy.db.sqlite.SharedReplicaPlatformApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable
import platform.UIKit.UIApplicationProtectedDataWillBecomeUnavailable
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.DISPATCH_TIME_NOW
import platform.posix.QOS_CLASS_USER_INITIATED
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_attr_make_with_qos_class
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_time
import kotlin.concurrent.Volatile
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np

/**
 * ⚠⚠ **The UIKit adapter of the iOS shared replica** (`docs/proposals/ios-shared-store-sqlite.md`,
 * Appendix B.12; Q4: it lives here, beside [FirstPartyLocalRuntime], and it is the store's only
 * UIKit piece). It gives the store's protection what only UIKit has, through the one public seam
 * of `:core-common` ([SharedReplicaBackgroundTime], [SharedReplicaLifecycle]):
 *
 * - **Assertions:** `beginBackgroundTask(withName:expirationHandler:)` / `endBackgroundTask`. The
 *   platform's expiration handler calls the store's own (which ends the assertion before it
 *   returns, B.5.2 (70)).
 * - **The transitions:** `UIApplicationDidEnterBackgroundNotification` → `enteredBackground`, and
 *   `UIApplicationWillEnterForegroundNotification` → `enteredForeground`, then the worker's
 *   [SharedReplicaLifecycle.kick] and the 30 s checkpoint timer. ⚠ Q3: both run synchronously
 *   in the notification on the main thread; the store takes the `background` token there and
 *   dispatches the quiesce off it.
 * - ⚠⚠ **`backgroundTimeRemainingMs()` from any thread, never waiting for main** (B.7, B.11.4
 *   (2), (6)): `UIApplication.backgroundTimeRemaining` is read **on the main thread only**, at
 *   install, at each transition, after each assertion begun (asynchronously when begun off main)
 *   and in each expiration handler, and the value between samples is the last sample less the
 *   monotonic time since. `DBL_MAX` is "no deadline", `Long.MAX_VALUE`, never converted.
 * - **The worker's queue:** a serial queue of the process's own at `QOS_CLASS_USER_INITIATED`
 *   (B.12 (158)), which also carries the 30 s timer's ticks.
 *
 * ⚠⚠ **Bound by every production assembly** (Appendix B.13; no switch, B.22):
 * [SharedReplicaBinding] makes it, attaches the store's registry to [lifecycle] and then calls
 * [install]. Internal: Swift never sees it.
 */
internal class SharedReplicaUiKitAdapter(
    private val app: UiKitApplication = UiKitApplicationPlatform,
    private val queue: ReplicaWorkerQueue = DispatchReplicaWorkerQueue(),
    private val clock: () -> Long = continuousClockMs,
    private val log: (String) -> Unit = ::println,
    /** How each job the worker ran ended (B.13): the binding's open waits on it. On the worker's queue. */
    private val onJobOutcome: (SharedReplicaJobOutcome) -> Unit = {},
) : SharedReplicaBackgroundTime {

    /** The last main-thread reading of `backgroundTimeRemaining`, published for every thread. */
    private class RemainingSample(val remainingMs: Long, val unlimited: Boolean, val atMs: Long)

    @Volatile
    private var sample: RemainingSample? = null

    @Volatile
    private var driver: ReplicaLifecycleDriver? = null

    /**
     * `UIApplication.isProtectedDataAvailable` as last read on the main thread (B.13 (170)): at
     * [install] and at the two protected-data notifications. ⚠ **No reading yet is false**, the
     * safe answer: the store reads "locked" as "retry later", never as damage.
     */
    @Volatile
    private var protectedData = false

    /** Main thread only. */
    private var checkpointTimer: ReplicaRepeatingTimer? = null

    /**
     * Told at every foreground, on the main thread, after the worker's kick: the binding's re-read
     * of the replica choice (B.14 (196)). A throw is logged and never leaves the notification.
     */
    @Volatile
    var onForeground: (() -> Unit)? = null

    /**
     * B.17 (259), Q42: told on the main thread when `UIApplicationProtectedDataDidBecomeAvailable`
     * is posted, after the sample is updated. A throw is logged and never leaves the notification.
     */
    @Volatile
    var onProtectedDataAvailable: (() -> Unit)? = null

    /** Test seam: when true, every sample taken is kept in [samples]. Off in production, so nothing grows. */
    internal var recordSamples = false

    /** Test seam: every sample taken while [recordSamples], in order (ms, or null for "no deadline"). */
    internal val samples = ArrayList<Long?>()
    private val samplesLock = NSRecursiveLock()

    /**
     * The store's lifecycle over this adapter and its worker queue: what the binding attaches each
     * registry to, before [install]. ⚠ Declared last (K/N: nothing above may read it in an `init`).
     */
    val lifecycle: SharedReplicaLifecycle = SharedReplicaLifecycle.create(this, queue::dispatch, onJobOutcome)

    /**
     * At launch, on the main thread, once, after every registry is attached: the application's
     * state then decides a foreground or a background launch, and the two notifications are
     * observed from here on. [loops] are what rule P stops and starts (B.13). [driver] is a test
     * seam; production drives [lifecycle].
     */
    fun install(driver: ReplicaLifecycleDriver? = null, loops: SharedReplicaLoops? = null) {
        check(app.isMainThread) { "[replica-uikit] install is called on the main thread" }
        check(this.driver == null) { "[replica-uikit] installed twice" }
        @Suppress("NAME_SHADOWING")
        val driver = driver ?: LifecycleDriver(lifecycle, loops)
        val foreground = !app.isInBackground()
        sampleOnMain()
        protectedData = app.isProtectedDataAvailable()
        app.observeProtectedData { available ->
            protectedData = available
            if (available) {
                try {
                    onProtectedDataAvailable?.invoke()
                } catch (t: Throwable) {
                    log("[replica-uikit] the protected-data hook threw: ${t.message}")
                }
            }
        }
        this.driver = driver
        driver.install(isForeground = foreground)
        app.observeLifecycle(onDidEnterBackground = ::didEnterBackground, onWillEnterForeground = ::willEnterForeground)
        if (foreground) startCheckpointTimer()
    }

    /**
     * A wake (a background refresh, a `BGTask`): the worker's trigger. Any thread; only
     * dispatches. ⚠ The wake's own token and the `BGTaskScheduler` registration are not this
     * adapter's (Appendix B.23).
     */
    fun wake() {
        val installed = driver ?: return log("[replica-uikit] a wake before install: ignored")
        installed.kick()
    }

    /**
     * `isProtectedDataAvailable` for the store, from any thread: the last main-thread reading,
     * never UIKit off main (B.13 (170)). False before [install].
     */
    fun protectedDataAvailable(): Boolean = protectedData

    /** True while the 30 s timer runs (between a foreground and the next background). Main thread. */
    internal val checkpointTimerRunning: Boolean get() = checkpointTimer != null

    // ── the transitions, on the main thread, synchronously in the notification (Q3) ──

    private fun didEnterBackground() {
        val installed = driver ?: return
        if (!app.isMainThread) log("[replica-uikit] didEnterBackground off the main thread")
        stopCheckpointTimer()
        // In the background already: the reading is the background allowance (or DBL_MAX while a
        // BGProcessingTask runs), never the foreground's.
        sampleOnMain()
        // ⚠⚠ Q3: the `background` token is taken HERE, before this returns; its release (the
        // quiesce, up to 1 s plus the 10 s drain) is dispatched off the main thread by the store.
        installed.enteredBackground()
        sampleOnMain()
    }

    private fun willEnterForeground() {
        val installed = driver ?: return
        if (!app.isMainThread) log("[replica-uikit] willEnterForeground off the main thread")
        sampleOnMain()
        installed.enteredForeground()
        // The worker's trigger at a foreground (B.8): after the gates are open and the foreground
        // counted, so a run held until "the next foreground" (B.11 (147)) runs now. Only dispatches.
        installed.kick()
        startCheckpointTimer()
        try {
            onForeground?.invoke()
        } catch (t: Throwable) {
            log("[replica-uikit] the foreground's choice check threw: ${t.message}")
        }
    }

    private fun startCheckpointTimer() {
        if (checkpointTimer != null) return
        checkpointTimer = queue.repeating(FOREGROUND_CHECKPOINT_INTERVAL_MS) {
            // A throw out of a tick on a dispatch queue would end the process.
            try {
                driver?.foregroundCheckpointTick()
            } catch (t: Throwable) {
                log("[replica-uikit] the 30 s foreground checkpoint threw: ${t.message}")
            }
        }
    }

    private fun stopCheckpointTimer() {
        checkpointTimer?.cancel()
        checkpointTimer = null
    }

    // ── SharedReplicaBackgroundTime ──

    override fun begin(reason: String, onExpired: () -> Unit): Long? {
        val id = app.beginBackgroundTask(reason) {
            // The platform calls this on the main thread: the reading there is the expiry's.
            if (app.isMainThread) sampleOnMain()
            onExpired()
        } ?: return null
        // A new assertion may be new time (a wake): read it on main, never waiting for main.
        if (app.isMainThread) sampleOnMain() else app.runOnMainAsync(::sampleOnMain)
        return id.toLong()
    }

    override fun end(id: Long) {
        app.endBackgroundTask(id.toULong())
    }

    /**
     * ⚠⚠ Never touches UIKit and never waits for the main thread: the last main-thread sample less
     * the monotonic time since. No sample yet is 0 (nothing starts on time nobody can name);
     * "no deadline" stays `Long.MAX_VALUE` however long ago it was read.
     */
    override fun backgroundTimeRemainingMs(): Long {
        val last = sample ?: return 0L
        if (last.unlimited) return Long.MAX_VALUE
        return (last.remainingMs - (clock() - last.atMs)).coerceAtLeast(0L)
    }

    /** Reads `backgroundTimeRemaining`. ⚠ Main thread only, which every caller checks. */
    private fun sampleOnMain() {
        val ms = remainingSecondsToMs(app.backgroundTimeRemainingSeconds())
        sample = RemainingSample(ms ?: Long.MAX_VALUE, unlimited = ms == null, atMs = clock())
        if (!recordSamples) return
        samplesLock.lock()
        try {
            samples += ms
        } finally {
            samplesLock.unlock()
        }
    }

    companion object {
        /**
         * ⚠⚠ The elapsed-time clock, in ms: one that **keeps counting while the device sleeps**
         * (B.12 (165)). `man clock_gettime` (Darwin): `CLOCK_MONOTONIC` "will continue to increment
         * while the system is asleep", `CLOCK_MONOTONIC_RAW` is "like `CLOCK_MONOTONIC`" without
         * frequency adjustments, and `CLOCK_UPTIME_RAW` (= `mach_absolute_time`) "does not
         * increment while the system is asleep". Kotlin's `TimeSource.Monotonic` is an uptime clock
         * on Darwin, so across a sleep it would leave the sample too large: the store would admit
         * work into time that no longer exists. A clock that includes sleep can only under-report.
         */
        val continuousClockMs: () -> Long = { (clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()) / 1_000_000uL).toLong() }

        /** §4.2.1: the opportunistic `PASSIVE` every 30 s while foregrounded. */
        const val FOREGROUND_CHECKPOINT_INTERVAL_MS = 30_000L

        /**
         * At or past this many seconds a reading is "no deadline": `DBL_MAX`, the foreground's and a
         * `BGProcessingTask`'s answer. Any real allowance is minutes; a day is far above it and far
         * below where a conversion to ms could overflow.
         */
        const val NO_DEADLINE_SECONDS = 86_400.0

        /**
         * ⚠ `backgroundTimeRemaining` to ms. Null: no deadline (`DBL_MAX`, an infinity, anything
         * past [NO_DEADLINE_SECONDS]). A NaN or a negative reading is 0, never "plenty".
         */
        fun remainingSecondsToMs(seconds: Double): Long? = when {
            seconds.isNaN() || seconds <= 0.0 -> 0L
            seconds >= NO_DEADLINE_SECONDS -> null
            else -> (seconds * 1_000.0).toLong()
        }
    }
}

/** What the adapter drives: [SharedReplicaLifecycle], behind a seam the tests fake. */
internal interface ReplicaLifecycleDriver {
    fun install(isForeground: Boolean)
    fun enteredForeground()
    fun enteredBackground()
    fun kick()
    fun foregroundCheckpointTick(): Int
}

internal class LifecycleDriver(
    private val lifecycle: SharedReplicaLifecycle,
    private val loops: SharedReplicaLoops? = null,
) : ReplicaLifecycleDriver {
    override fun install(isForeground: Boolean) = lifecycle.install(isForeground, loops)
    override fun enteredForeground() = lifecycle.enteredForeground()
    override fun enteredBackground() = lifecycle.enteredBackground()
    override fun kick() = lifecycle.kick()
    override fun foregroundCheckpointTick(): Int = lifecycle.foregroundCheckpointTick()
}

/** The slice of `UIApplication` the adapter uses: a seam, so the tests drive begin, end, expiry and the notifications. */
internal interface UiKitApplication {
    val isMainThread: Boolean

    /** `applicationState == .background`. Main thread. */
    fun isInBackground(): Boolean

    /** `beginBackgroundTask(withName:expirationHandler:)`; null for `UIBackgroundTaskInvalid`. Any thread (UIKit documents it so). */
    fun beginBackgroundTask(name: String, onExpired: () -> Unit): ULong?

    /** `endBackgroundTask`. Any thread (UIKit documents it so). */
    fun endBackgroundTask(id: ULong)

    /** `backgroundTimeRemaining`, in seconds. ⚠ Main thread only. */
    fun backgroundTimeRemainingSeconds(): Double

    /** Observes the two notifications, delivered synchronously on the posting (main) thread. */
    fun observeLifecycle(onDidEnterBackground: () -> Unit, onWillEnterForeground: () -> Unit)

    /** Runs [block] on the main queue later; never waits. */
    fun runOnMainAsync(block: () -> Unit)

    /** `isProtectedDataAvailable`. ⚠ Main thread only. */
    fun isProtectedDataAvailable(): Boolean = false

    /**
     * Observes `UIApplicationProtectedDataDidBecomeAvailable` (true) and
     * `UIApplicationProtectedDataWillBecomeUnavailable` (false), on the posting (main) thread.
     */
    fun observeProtectedData(onChange: (Boolean) -> Unit) = Unit
}

internal object UiKitApplicationPlatform : UiKitApplication {
    private val observers = ArrayList<Any>()

    override val isMainThread: Boolean get() = NSThread.isMainThread

    override fun isInBackground(): Boolean =
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground

    override fun beginBackgroundTask(name: String, onExpired: () -> Unit): ULong? {
        val id = UIApplication.sharedApplication.beginBackgroundTaskWithName(name, onExpired)
        return if (id == UIBackgroundTaskInvalid) null else id
    }

    override fun endBackgroundTask(id: ULong) {
        UIApplication.sharedApplication.endBackgroundTask(id)
    }

    override fun backgroundTimeRemainingSeconds(): Double = UIApplication.sharedApplication.backgroundTimeRemaining

    override fun observeLifecycle(onDidEnterBackground: () -> Unit, onWillEnterForeground: () -> Unit) {
        val center = NSNotificationCenter.defaultCenter
        // queue = null: the block runs synchronously on the posting thread, which for these two
        // is the main thread, inside the notification (Q3).
        observers += center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { _ -> onDidEnterBackground() }
        observers += center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, null) { _ -> onWillEnterForeground() }
    }

    override fun runOnMainAsync(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue(), block)
    }

    override fun isProtectedDataAvailable(): Boolean = UIApplication.sharedApplication.isProtectedDataAvailable()

    override fun observeProtectedData(onChange: (Boolean) -> Unit) {
        val center = NSNotificationCenter.defaultCenter
        // WillBecomeUnavailable is posted before the data goes (about 10 s after the lock), and
        // DidBecomeAvailable after it is back: the sample is never "available" while it is not.
        observers += center.addObserverForName(UIApplicationProtectedDataDidBecomeAvailable, null, null) { _ -> onChange(true) }
        observers += center.addObserverForName(UIApplicationProtectedDataWillBecomeUnavailable, null, null) { _ -> onChange(false) }
    }
}

/** The worker's queue: serial, the process's own, never the main one. */
internal interface ReplicaWorkerQueue {
    /** Runs [block] once on the queue. */
    fun dispatch(block: () -> Unit)

    /** Runs [block] on the queue every [intervalMs], the first after [intervalMs], until cancelled. */
    fun repeating(intervalMs: Long, block: () -> Unit): ReplicaRepeatingTimer
}

internal fun interface ReplicaRepeatingTimer {
    fun cancel()
}

/**
 * ⚠ `QOS_CLASS_USER_INITIATED` (B.12 (158)): the worker's runs take the registry's lock and the
 * write lock that a main-thread access then waits behind, and a migration run is what
 * "Updating storage…" waits for. At utility or background QoS they would be throttled (CPU and,
 * at background, I/O) while the main thread waits on them, and run past the admission checks'
 * budgets. Its contention with the launch is a device measurement (B.11.6 (3)).
 */
internal class DispatchReplicaWorkerQueue(
    label: String = "org.tenshu.replica.worker",
    /**
     * Test seam: what each tick of [repeating] runs inside, outside the tick's own guard. Production
     * runs it as it is; a test catches here what escaped the guard, so a guard removed fails the
     * test instead of ending the test process.
     */
    private val outermost: (() -> Unit) -> Unit = { it() },
) : ReplicaWorkerQueue {
    private val queue = dispatch_queue_create(label, dispatch_queue_attr_make_with_qos_class(null, QOS_CLASS_USER_INITIATED, 0))

    override fun dispatch(block: () -> Unit) {
        dispatch_async(queue, block)
    }

    /** A chain of `dispatch_after`s with a generation, so a cancel needs no source object. */
    override fun repeating(intervalMs: Long, block: () -> Unit): ReplicaRepeatingTimer {
        val state = RepeatingState()
        fun next() {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, intervalMs * 1_000_000), queue) {
                if (state.cancelled) return@dispatch_after
                outermost {
                    // ⚠ A throw here would end the process (a dispatch queue) and the chain with it.
                    try {
                        block()
                    } catch (t: Throwable) {
                        println("[replica-uikit] a repeating tick threw: ${t.message}")
                    }
                }
                next()
            }
        }
        next()
        return ReplicaRepeatingTimer { state.cancelled = true }
    }

    private class RepeatingState {
        @Volatile
        var cancelled = false
    }
}
