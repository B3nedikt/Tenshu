package app.tenshu.macos

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * A native call that may never return, made so that its **caller** always does.
 *
 * ⚠⚠ A downcall cannot be interrupted or cancelled: a thread inside one stays there until the C
 * function returns. Made inline, a call that wedges (a Mach IPC to WindowServer or powerd that
 * never answers) takes its caller with it — and when that caller is a coroutine under a `Mutex`
 * that a session teardown joins, the whole process can no longer shut down. That is how
 * `HomeDeviceControlSystemTest` hung a gate for 17 minutes, parked in `CGEventSourceSecondsSinceLastEventType`
 * (`docs/tech-debt.md`, "A Mac idle probe that never returned").
 *
 * So the call runs on this probe's own daemon thread and the caller waits at most [timeoutMs]:
 * - an answer in time is returned;
 * - no answer in time, a throw, or an interrupt answers `null` — the probe's documented unknown;
 * - a caller arriving while a call is merely in flight waits on that same call (its own bound);
 * - ⚠ once a call has **timed out** and is still stuck, every later call answers `null` at once
 *   rather than queueing behind it, so a wedged function costs one daemon thread, never one per caller;
 * - once the stuck call does return, the next call asks the OS again.
 */
internal class BoundedNativeCall<T : Any>(
    name: String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val call: () -> T?,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tenshu-native-call-$name").apply { isDaemon = true }
    }

    private var inFlight: Future<T?>? = null

    /** The in-flight call that has already outlived a caller's bound — stuck until proven otherwise. */
    private var timedOut: Future<T?>? = null

    operator fun invoke(): T? {
        val future = synchronized(this) {
            val previous = inFlight
            when {
                previous == null || previous.isDone -> executor.submit<T?> { call() }.also { inFlight = it }
                previous === timedOut -> return null
                // Merely slow so far (a cold first call): wait on the same answer, don't run it twice.
                else -> previous
            }
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            synchronized(this) { if (inFlight === future) timedOut = future }
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    companion object {
        /**
         * A healthy first call (the WindowServer connect) takes ~80 ms on an idle machine; later
         * ones microseconds. Symbol lookup and linking happen outside the bound ([MacPowerProbe]).
         */
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
