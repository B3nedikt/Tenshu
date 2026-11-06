package org.tenshu.core.common.app

import org.tenshu.core.TResult
import org.tenshu.core.app.AppService
import org.tenshu.core.app.OpenOutcome
import org.tenshu.core.app.OpenTarget
import org.tenshu.core.app.OpenTargetUnavailable
import org.tenshu.core.app.openTarget
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.entity.DataId
import org.tenshu.core.get

/**
 * A citing app's side of cross-app open (`control-cross-app-open.md` TT-1b).
 *
 * ⭐ **One call for the caller, two shapes underneath.** Control either delivers the request to a
 * running handler itself or hands back a launch ticket the caller must fire — and which of those
 * happened is not something a tapped row should have to reason about. The distinction is a
 * platform fact (a background proxy may not start an activity, CO-D3), so it is absorbed here.
 *
 * ⚠⚠ **The caller never learns which app opened.** [OpenOutcome.appId] exists for diagnostics, and
 * the ticket is opaque; nothing in this class hands out a package name. That is CO-D1 holding at
 * the last hop — the address is the data, never the app.
 */
fun interface TargetOpener {

    suspend fun open(target: DataId): OpenTargetResult
}

/**
 * The real [TargetOpener] — [OpenTarget] over the proxy, plus whatever launch it hands back.
 *
 * ⚠ [TargetOpener] is an interface and this is one implementation, rather than one class doing
 * both: a view model that depended on the concrete type could only be tested against a live proxy,
 * and the interesting cases here are the **five refusals**, which a live proxy is the hardest
 * possible way to produce.
 */
class ControlTargetOpener(
    private val client: TenshuClient,
    private val performLaunch: LaunchPerformer,
) : TargetOpener {

    override suspend fun open(target: DataId): OpenTargetResult {
        val service = runCatching { client.getService<AppService>(AppService.ID) }
            .getOrElse { return OpenTargetResult.Unreachable }

        val result = runCatchingCancellable { service.openTarget(target) }
            .getOrElse { return OpenTargetResult.Unreachable }

        // ⚠⚠ **The refusal arrives as a `TResult.Failure`, not as a throw.** A typed error thrown
        // proxy-side fails the request scope and crosses the wire as a value; only an in-process
        // caller ever sees the exception. Catching `TenshuException` here and nothing else — which
        // is the obvious shape — silently turned every one of CO-D9's five reasons into
        // "Control is unreachable", the one sentence that is never true when the proxy just
        // answered.
        (result as? TResult.Failure<*>)?.let { failure ->
            val error = failure.error as? OpenTargetUnavailable ?: return OpenTargetResult.Unreachable
            return OpenTargetResult.Refused(
                reason = error.reason.get(),
                message = error.message.get(),
            )
        }

        val outcome = (result as? TResult.Success<*>)?.value as? OpenOutcome
            ?: return OpenTargetResult.Unreachable

        val ticketId = outcome.launchTicketId.get()
        // Empty means Control already handed it over — the handler was running and connected.
        if (ticketId.isEmpty()) return OpenTargetResult.Delivered

        return if (performLaunch.perform(ticketId)) {
            OpenTargetResult.LaunchRequested
        } else {
            OpenTargetResult.Refused(
                reason = OpenTargetUnavailable.Reasons.LAUNCH_FAILED,
                message = "The app did not come up.",
            )
        }
    }
}

/**
 * Performs the launch Control resolved but may not run itself.
 *
 * ⚠ Per-platform by necessity: Android fires a `PendingIntent`, and desktop and iOS have neither
 * that type nor the same constraint. A platform with no launcher bound returns `false`, which the
 * caller reports as an honest [OpenTargetUnavailable.Reasons.LAUNCH_FAILED] rather than a silent
 * nothing.
 */
fun interface LaunchPerformer {

    /** @return whether the launch was accepted — never whether the user saw it (CO-D9). */
    suspend fun perform(ticketId: String): Boolean
}

/**
 * ⚠⚠ **The two successes are not equally certain, and collapsing them would be a lie.** Control
 * handing a request to a connected handler is observed; a fired launch ticket is not — see
 * [LaunchRequested].
 */
sealed interface OpenTargetResult {

    /**
     * Control pushed the request to a **connected** handler. ⚠ Not "the user is looking at it" —
     * that is unknowable — but the hand-over itself is confirmed.
     */
    data object Delivered : OpenTargetResult

    /**
     * A launch ticket was fired and not rejected. ⚠⚠ **This is weaker than [Delivered] and the gap
     * is measured, not theoretical**: with the calling app not visible, Android blocks the start
     * (`Abort background activity starts`, `BAL_BLOCK`) while `PendingIntent.send()` returns
     * perfectly normally. So this means *"nothing refused it in a way we can see"*, and the only
     * honest confirmation is the handler reporting in on its own connection (CO-D9).
     *
     * ⚠ In practice the block cannot happen on the intended path — a tapped citation implies a
     * visible caller, and the framework allows it as `BAL_ALLOW_VISIBLE_WINDOW [realCaller]`. It is
     * the *unintended* paths (a fire from a worker, a delayed retry) this name exists to keep
     * honest.
     */
    data object LaunchRequested : OpenTargetResult

    /** @param reason one of [OpenTargetUnavailable.Reasons] — the caller picks the sentence. */
    data class Refused(val reason: String, val message: String) : OpenTargetResult

    /** Control could not be reached at all. ⚠ Never rendered as "nothing happened". */
    data object Unreachable : OpenTargetResult
}
