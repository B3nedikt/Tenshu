package app.tenshu.liveness

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/*
 * The **Android foreground-service start is refusable**, and every Tenshu app used to treat it as
 * infallible. This file holds the two shapes that fixes, kept Android-free (pure lambdas and flows)
 * so both are unit-testable — the refusal path cannot be provoked off-device, and no app module in
 * this repo has Robolectric.
 *
 * Since API 31 `startForegroundService()` throws `ForegroundServiceStartNotAllowedException` unless
 * the app holds an FGS-start allowance. That exception is an [IllegalStateException] — which is what
 * both helpers narrow to, deliberately: catching the API-31 subclass by name would put an API-31
 * type reference in the catch clause, while the supertype stays verifiable on older runtimes.
 *
 * Observed 2026-07-30 as a fatal crash in two apps at once (Control's `ProxyService` on the main
 * thread, Fit's import launcher on a Dispatchers.Default worker). On ColorOS the crash is not even
 * the worst of it: a crash loop gets the app flagged (`notifyUnstableAppInfo …
 * unstable_restrict_switch`) and it can end up **disabled** outright.
 *
 * A refused start is always a *degradation, never a failure*: the work itself carries on, it just
 * loses the notification and the OS-reclaim protection until the app is next foregrounded.
 */

/**
 * Promotes an already-running service to the foreground, returning whether the OS allowed it.
 *
 * The [startService] → [startForeground] pair is the standard promotion dance (a bound-only or
 * started service must be *started* before `startForeground` is permitted), and **either half can
 * be refused**:
 *
 * - [startService] refused — nothing was started, so there is nothing to unwind.
 * - [startForeground] refused — ⚠ the started lifecycle is already running and its
 *   `startForeground` deadline is ticking, so [stopStartedLifecycle] (`stopSelf`) ends it now
 *   rather than let the OS kill the process for missing the window.
 *
 * Callers keep the returned flag and retry later; a service that never promoted must also not post
 * its ongoing notification (there would be no service behind it).
 */
fun startAndPromoteForeground(
    startService: () -> Unit,
    startForeground: () -> Unit,
    stopStartedLifecycle: () -> Unit,
): Boolean {
    try {
        startService()
    } catch (_: IllegalStateException) {
        return false
    }
    return try {
        startForeground()
        true
    } catch (_: IllegalStateException) {
        stopStartedLifecycle()
        false
    }
}

/**
 * Enters the foreground from a service that is **already started**, returning whether the OS allowed
 * it — the `onCreate` half of the contract.
 *
 * `startForegroundService()` obliges the service to call `startForeground` promptly or be killed
 * with `ForegroundServiceDidNotStartInTimeException`, so these call sites are unconditional by
 * design. That does not make them infallible: the allowance can lapse between the start and
 * `onCreate`, and API 34 adds its own type/permission refusals. When it is refused there is nothing
 * useful left to do but end the started lifecycle immediately — which is strictly better than
 * being killed for missing the deadline, and far better than an uncaught throw.
 */
fun enterForegroundOrStop(
    startForeground: () -> Unit,
    stopStartedLifecycle: () -> Unit,
): Boolean = startAndPromoteForeground(
    startService = {}, // already started — that is the premise
    startForeground = startForeground,
    stopStartedLifecycle = stopStartedLifecycle,
)

/**
 * Drives a *launcher*: starts a foreground service once per in-flight period of [inFlight], and
 * survives the OS refusing.
 *
 * ⚠⚠ **The attempt is eager, and gating it on [foreground] instead would be a regression.** An app
 * is allowed to start an FGS from the background whenever it holds an allowance — most commonly the
 * while-in-use window inherited from having just been foreground. That is exactly the Drive
 * folder-upload case: the user starts an upload and immediately backgrounds Drive, and the rising
 * edge lands *after* the app leaves the screen but while the start is still perfectly legal. A
 * foreground gate would skip it and leave the upload unprotected precisely when it needs the
 * service most.
 *
 * So: always attempt; and once refused, **wait for a foreground transition before attempting
 * again**. That is what keeps a refused start from becoming a retry storm — [inFlight] is a progress
 * flow that can emit continuously for the length of a multi-minute operation, and re-attempting on
 * every emission would be thousands of throwing Binder calls. Recovery is still automatic: opening
 * the app is a `false → true` edge on [foreground], which re-arms exactly one attempt.
 *
 * [foreground] must report *process* foregroundedness (`AppForegroundState` on Android), not a
 * single activity's.
 */
suspend fun armForegroundService(
    inFlight: Flow<Boolean>,
    foreground: Flow<Boolean>,
    startService: () -> Unit,
) {
    var serviceStarted = false
    var refused = false
    var wasForeground = false
    combine(inFlight, foreground) { importing, isForeground -> importing to isForeground }
        .collect { (importing, isForeground) ->
            val enteredForeground = isForeground && !wasForeground
            wasForeground = isForeground

            if (!importing) {
                // Period ended — the service demotes and stops itself; re-arm for the next one.
                serviceStarted = false
                refused = false
                return@collect
            }
            if (serviceStarted) return@collect
            // Back off after a refusal until the app is foregrounded again — see the KDoc.
            if (refused && !enteredForeground) return@collect

            refused = false
            serviceStarted = try {
                startService()
                true
            } catch (_: IllegalStateException) {
                refused = true
                false
            }
        }
}
