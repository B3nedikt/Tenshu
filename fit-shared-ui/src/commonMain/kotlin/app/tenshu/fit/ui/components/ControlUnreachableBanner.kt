package app.tenshu.fit.ui.components

import app.tenshu.fit.shared.resources.fit_update_action
import app.tenshu.ui.feedback.appControlCheckingLabel
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_control_get_action
import app.tenshu.fit.shared.resources.fit_control_not_installed
import app.tenshu.fit.shared.resources.fit_control_not_set_up
import app.tenshu.fit.shared.resources.fit_control_not_installed_other_channel
import app.tenshu.fit.shared.resources.fit_control_not_installed_unpublished
import app.tenshu.fit.shared.resources.fit_control_open_action
import app.tenshu.fit.shared.resources.fit_control_unreachable_text
import app.tenshu.fit.shared.resources.fit_fenced_update_both
import app.tenshu.fit.shared.resources.fit_fenced_update_unknown
import app.tenshu.fit.shared.resources.fit_fenced_update_control
import app.tenshu.fit.shared.resources.fit_fenced_update_this_app
import app.tenshu.fit.shared.resources.fit_skew_update_both
import app.tenshu.fit.shared.resources.fit_skew_update_control
import app.tenshu.fit.shared.resources.fit_skew_update_this_app
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import app.tenshu.ui.feedback.ControlBannerAction
import app.tenshu.ui.feedback.ErrorBanner
import app.tenshu.ui.feedback.controlBannerIcon
import app.tenshu.ui.feedback.rememberControlBannerAction
import app.tenshu.ui.storage.ControlInstallRoute
import app.tenshu.ui.storage.rememberControlInstallRoute
import org.jetbrains.compose.resources.stringResource

/**
 * The shell-level "Can't reach Tenshu Control" banner (fit-app.md §9.14). Fit has no store of
 * its own — every read goes over IPC to Control — so an unreachable Control folds every screen
 * to empty; this says *why* instead of leaving the app silently blank. Rendered by the app shell
 * above the nav host whenever `FitRepository.controlReachable()` reports `false`. The visual form
 * is the shared `:ui-core` [ErrorBanner] (Timeline and Maps render the same strip over the same
 * `:app-reachability` engine).
 *
 * ## ⚠⚠ Two outages, two sets of words (SK-1, `core-schema-skew-surface.md`)
 *
 * [reason] is what makes this more than one string. A dead channel recovers on its own and the
 * banner carries no action; a proxy that is **answering and refusing** on a schema version
 * recovers only when the user updates something, and this is where we name what.
 *
 * ⚠⚠ The app to name is frequently **not Fit**. On Android and desktop a single Control-owned
 * runtime serves every app, so [UpdateTarget.CONTROL] is the common answer and Fit is innocent —
 * telling the user to update the app they are looking at would leave them stuck after doing
 * exactly what the banner asked.
 *
 * ⚠ `null` [reason] keeps the pre-SK-1 words, which is correct for both "not yet known" and any
 * wiring site that has not been updated.
 *
 * ## ⚠⚠ Two outages the user can act on, and only one of them by opening Control (ADR 0025)
 *
 * [UnreachableReason.NotInstalled] is a *different sentence*, not a stronger one: Control is not on
 * the device, so "open Control and unlock it" names something that is not there. It also gets **no
 * button** — the App Store / Play route is not configured yet on any platform, and offering a dead
 * one is the failure ADR 0022 was written about. The words carry it alone until it is.
 *
 * ⚠ The *transport* case is the one that gains a button here, and only where a route exists —
 * `rememberControlBannerAction` owns that judgement for every app, so which reasons are actionable
 * is decided once rather than five times.
 */
@Composable
fun ControlUnreachableBanner(
    reason: UnreachableReason? = null,
    modifier: Modifier = Modifier,
) {
    // ⚠⚠ The route decides the *words* as well as the button: a build whose Control is not in the
    // store yet must NOT be told "install it the way you installed Fit", because that way does not
    // work yet (ADR 0025 §Decision 6). The judgement lives in `:ui-core`; only the copy is Fit's.
    val installRoute = rememberControlInstallRoute()
    val action = rememberControlBannerAction(reason, installRoute)
    val text = when (reason) {
        is UnreachableReason.Skew -> when (reason.update) {
            UpdateTarget.CONTROL -> stringResource(Res.string.fit_skew_update_control)
            UpdateTarget.THIS_APP -> stringResource(Res.string.fit_skew_update_this_app)
            UpdateTarget.BOTH -> stringResource(Res.string.fit_skew_update_both)
        }
        is UnreachableReason.Fenced -> when (reason.update) {
            UpdateTarget.CONTROL -> stringResource(Res.string.fit_fenced_update_control)
            UpdateTarget.THIS_APP -> stringResource(Res.string.fit_fenced_update_this_app)
            UpdateTarget.BOTH -> stringResource(Res.string.fit_fenced_update_both)
            // ⚠⚠ No host declared itself. Name nothing rather than guess: "update Control" is
            // wrong under route 1 and "update Fit" is wrong on Android and desktop, so the honest
            // line points at Tenshu on this device without picking a binary.
            null -> stringResource(Res.string.fit_fenced_update_unknown)
        }
        // ⚠ Normally behind the wall (ADR 0025 §Decision 8); worded anyway, never as an outage.
        // Never reaches a banner — the root shows AppControlCheckingGate first (ADR 0025 §8.6).
        UnreachableReason.Checking -> appControlCheckingLabel()
        UnreachableReason.NotSetUp -> stringResource(Res.string.fit_control_not_set_up)
        UnreachableReason.NotInstalled -> when (installRoute) {
            is ControlInstallRoute.Store -> stringResource(Res.string.fit_control_not_installed)
            ControlInstallRoute.NotPublishedYet ->
                stringResource(Res.string.fit_control_not_installed_unpublished)
            ControlInstallRoute.SameChannelAsThisApp ->
                stringResource(Res.string.fit_control_not_installed_other_channel)
        }
        UnreachableReason.Transport, null -> stringResource(Res.string.fit_control_unreachable_text)
    }
    ErrorBanner(
        text = text,
        modifier = modifier,
        icon = controlBannerIcon(reason),
        actionLabel = action?.let {
            when (it.kind) {
                ControlBannerAction.Kind.OPEN_CONTROL -> stringResource(Res.string.fit_control_open_action)
                ControlBannerAction.Kind.GET_CONTROL -> stringResource(Res.string.fit_control_get_action)
                ControlBannerAction.Kind.UPDATE -> stringResource(Res.string.fit_update_action)
            }
        },
        onAction = action?.onClick,
    )
}
