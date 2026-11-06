package app.tenshu.control.android.server

import app.tenshu.liveness.startAndPromoteForeground

/**
 * The promote / degrade / retry state machine behind `ProxyService`'s [TaskLivenessHandle].
 *
 * ⚠⚠ **Promotion can be refused, and treating it as infallible was a fatal crash.** Since API 31
 * `startForegroundService()` throws `ForegroundServiceStartNotAllowedException` unless the app holds
 * an FGS-start allowance, and `ProxyService` called it straight from the liveness edge — on the main
 * thread, with no caller to catch it:
 *
 * ```
 * FATAL EXCEPTION: main
 * Process: app.tenshu.control.android
 * android.app.ForegroundServiceStartNotAllowedException: startForegroundService() not allowed
 *   due to mAllowStartForeground false: service app.tenshu.control.android/.server.ProxyService
 *   at ProxyService$ForegroundServiceHandle.onActive(ProxyService.kt:111)
 *   at ForegroundTaskLiveness.onCount$control(ForegroundTaskLiveness.kt:54)
 * ```
 *
 * Observed repeatedly on a real device 2026-07-30, and it is worse than an ordinary crash: ColorOS
 * responds to a crash loop by flagging the app (`notifyUnstableAppInfo … unstable_restrict_switch`)
 * and can end up **disabling** it outright — the package was found at
 * `COMPONENT_ENABLED_STATE_DISABLED_USER`, which takes the whole platform down, since every other
 * Tenshu app reaches its data through this proxy.
 *
 * ⚠ **The fix is deliberately NOT a foreground gate**, unlike Fit's `FitImportServiceLauncher`.
 * Control promoting itself *while backgrounded* is the entire point — it exists to serve other apps,
 * and the liveness edge fires precisely when a client asked it to do work. What normally makes that
 * legal is the while-in-use allowance inherited from a **bound client that is itself foreground**;
 * the refusal happens when no such client is around (a background sync drain, or a client that just
 * went away). Gating on Control's own foregroundedness would refuse the common case.
 *
 * So the contract here is: **try, and degrade to bound-only when refused.** A degraded proxy is not
 * a broken one — it stays alive for as long as a client is bound, exactly as it did before Phase
 * L2-LIVE; it only loses the OS-reclaim protection that keeps a *detached* task alive after the last
 * client unbinds. [onCountChanged] retries — at most once per [PROMOTION_RETRY_INTERVAL_MS] after a
 * refusal, never on every change — so a client returning to the foreground recovers it without an
 * idle→active cycle.
 *
 * Free of Android imports for the same reason [ForegroundTaskLiveness] is: so the edges — including
 * the refusal path, which cannot be provoked off-device — are unit-testable on the JVM.
 */
internal class ForegroundPromotion(
    /** `startForegroundService` — throws [IllegalStateException] when the OS refuses. */
    private val startService: () -> Unit,
    /** `startForeground` — throws [IllegalStateException] when the OS refuses. */
    private val startForeground: (taskCount: Int) -> Unit,
    /** `stopForeground(STOP_FOREGROUND_REMOVE)`. */
    private val stopForeground: () -> Unit,
    /** `stopSelf` — ends the *started* lifecycle; a bound client keeps the service alive. */
    private val stopStartedLifecycle: () -> Unit,
    /** Re-post the ongoing notification with a new count. */
    private val refreshNotification: (taskCount: Int) -> Unit,
    /** Monotonic milliseconds, for the retry throttle. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** The least time between two promotion attempts after a refusal. */
    private val retryIntervalMs: Long = PROMOTION_RETRY_INTERVAL_MS,
) : TaskLivenessHandle {

    /** Whether the service is currently promoted. Internal for the test. */
    internal var promoted = false
        private set

    /**
     * The OS stopped the foreground service for its time limit (`onTimeout`, API 34+). No promotion
     * is attempted again until the work goes idle and a new user-started task begins.
     */
    internal var timedOut = false
        private set

    /** When the last attempt was refused, or `null` when the last attempt succeeded (or none ran). */
    private var lastRefusalAtMs: Long? = null

    override fun onActive(taskCount: Int) = promoteOrDegrade(taskCount)

    override fun onCountChanged(taskCount: Int) {
        // The retry point: if an earlier promotion was refused we are still bound-only, and a
        // client may have come to the foreground since. Refreshing a notification we never posted
        // would also put up a standalone notification with no service behind it.
        if (promoted) refreshNotification(taskCount) else promoteOrDegrade(taskCount)
    }

    override fun onIdle() {
        if (promoted) {
            stopForeground()
            promoted = false
        }
        // The time limit and a refusal both belong to the work that hit them. A new task after idle
        // is a new user action — possibly a fresh tap with the client in front — and whether it may
        // promote is the OS's call to make again, not a throttle's.
        timedOut = false
        lastRefusalAtMs = null
        // Ends the started lifecycle; the service stays alive as long as a client remains bound.
        stopStartedLifecycle()
    }

    /**
     * The OS's `onTimeout`: the `dataSync` budget (6 h per 24 h on Android 15) is spent. The service
     * must leave the foreground within seconds or the OS crashes it, so this demotes at once, even
     * mid-task — the task itself carries on bound-only.
     */
    fun onTimedOut() {
        if (promoted) {
            stopForeground()
            promoted = false
        }
        timedOut = true
        stopStartedLifecycle()
    }

    private fun promoteOrDegrade(taskCount: Int) {
        // ⚠ After a time-out the OS refuses every dataSync start until the user opens the app, so
        // asking again only produces a refusal per task.
        if (timedOut) return
        // ⚠⚠ Throttled after a refusal. Every task start and finish is a count change, and retrying on
        // each one sent thousands of refused binder calls to ActivityManager per background backup
        // (4,980 for 300 photos, control-background-work-off-fgs.md §3.5).
        val refusedAt = lastRefusalAtMs
        if (refusedAt != null && nowMs() - refusedAt < retryIntervalMs) return
        promoted = startAndPromoteForeground(
            startService = startService,
            startForeground = { startForeground(taskCount) },
            stopStartedLifecycle = stopStartedLifecycle,
        )
        lastRefusalAtMs = if (promoted) null else nowMs()
    }

    companion object {
        /** See [promoteOrDegrade]: one attempt per ten seconds is enough to catch a client returning. */
        const val PROMOTION_RETRY_INTERVAL_MS: Long = 10_000
    }
}
