package app.tenshu.ui.feedback

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import app.tenshu.reachability.UnreachableReason
import app.tenshu.ui.storage.AppUpdatePages
import app.tenshu.ui.storage.ControlInstallRoute
import app.tenshu.ui.storage.rememberAppUpdatePages
import app.tenshu.ui.storage.rememberControlInstallRoute
import app.tenshu.ui.storage.rememberControlServicesLauncher

/*
 * The **glyph and the button** for a Control-unavailable banner, decided once for every app
 * (`ADR 0025` §Decision 2).
 *
 * ⚠⚠ **The words stay with the app; only the posture is shared.** `:ui-core` ships no strings —
 * each app has to name what *it* loses ("your places can't load" vs "your health data can't
 * load"), which is ADR 0022 §Decision 2 arriving on this platform. What must not be re-decided per
 * app is which reasons get an action and which get an apology: that is the judgement five shells
 * were each about to make separately, and the one an ADR exists to make once.
 */

/**
 * ⚠ A different problem deserves a different glyph. A cloud-off beside "Control isn't installed"
 * reads as a network hiccup to wait out, and an update badge beside it reads as a version problem;
 * both send the reader somewhere there is nothing to do.
 */
fun controlBannerIcon(reason: UnreachableReason?): ImageVector = when (reason) {
    UnreachableReason.NotInstalled -> Icons.Default.GetApp
    // ⚠ Neither "install" nor "wait": Control is here and answering, and needs setting up
    // (ADR 0025 §Decision 8).
    UnreachableReason.NotSetUp, UnreachableReason.Checking -> Icons.Default.Settings
    UnreachableReason.Transport, null -> Icons.Default.CloudOff
    is UnreachableReason.Skew, is UnreachableReason.Fenced -> Icons.Default.SystemUpdateAlt
}

/**
 * The banner's action, or `null` when there is honestly nothing to offer — in which case the caller
 * must omit the button rather than render a dead one.
 *
 * Five reasons can carry one, and they are different offers:
 *
 * - [UnreachableReason.Transport] → **open** Control. It is installed and this platform can reach
 *   it, so it is asleep, locked, or not running, and opening it is what recovers the state. The
 *   copy has said so ("open Control and unlock it, then come back") since fit-app.md §9.14 with
 *   nothing to tap.
 * - [UnreachableReason.NotSetUp] → **open** Control, the same offer: it is installed and answering,
 *   and setting it up happens there (ADR 0025 §Decision 8). ⚠ Never *get* — it is installed.
 * - [UnreachableReason.NotInstalled] → **get** Control, but only when [route] is an actual
 *   [ControlInstallRoute.Store]. A build that did not come from a store, or one whose link is not
 *   configured, offers nothing and says why in words instead (ADR 0025 §Decision 6).
 * - [UnreachableReason.Skew] / [UnreachableReason.Fenced] → **update**, but only when [updatePages]
 *   has a page for the binary the verdict names (`core-schema-skew-surface.md` §8.5): a downloaded
 *   Android copy's tenshu.app page (`android-direct-apk-distribution.md` D4), a Play copy's
 *   configured listing, an iOS copy's configured App Store page. ⚠⚠ The page is the *stale* app's —
 *   Control's for a [app.tenshu.reachability.UpdateTarget.CONTROL] verdict, whichever app draws the
 *   banner. Every other channel and platform offers nothing: opening Control does not *update*
 *   Control.
 *
 * ⚠ `null` from [rememberControlServicesLauncher] is a first-class answer, not a failure: desktop
 * has no launch path in v1, and an app missing its manifest `<queries>` /
 * `LSApplicationQueriesSchemes` entry cannot see Control even when it is installed.
 */
@Composable
fun rememberControlBannerAction(
    reason: UnreachableReason?,
    route: ControlInstallRoute = rememberControlInstallRoute(),
    updatePages: AppUpdatePages = rememberAppUpdatePages(),
): ControlBannerAction? {
    val openControl = rememberControlServicesLauncher()
    val uriHandler = LocalUriHandler.current
    return when (bannerActionKind(reason, route, updatePages)) {
        ControlBannerAction.Kind.OPEN_CONTROL ->
            openControl?.let { ControlBannerAction(ControlBannerAction.Kind.OPEN_CONTROL, it) }
        ControlBannerAction.Kind.GET_CONTROL -> (route as? ControlInstallRoute.Store)?.let { store ->
            ControlBannerAction(ControlBannerAction.Kind.GET_CONTROL) { uriHandler.openUri(store.url) }
        }
        ControlBannerAction.Kind.UPDATE -> updatePageFor(reason, updatePages)?.let { page ->
            ControlBannerAction(ControlBannerAction.Kind.UPDATE) { uriHandler.openUri(page) }
        }
        null -> null
    }
}

/**
 * What the banner offers, if anything.
 *
 * ⚠ The [kind] is carried rather than inferred from the reason at the call site, because the label
 * is the app's to localise and "Open Control" and "Get Control" are not interchangeable — an app
 * that guessed from the reason alone would say *Get* to someone whose Control is merely locked.
 */
data class ControlBannerAction(val kind: Kind, val onClick: () -> Unit) {
    enum class Kind {
        OPEN_CONTROL,
        GET_CONTROL,

        /**
         * Open the stale app's download page. ⚠ The label must not name an app ("Update"): the
         * banner's words already say which one, and it is often not the app showing the button.
         */
        UPDATE,
    }
}

/**
 * Which offer a reason earns — the half of [rememberControlBannerAction] that is a judgement rather
 * than a platform lookup, split out so it can be tested.
 *
 * ⚠ The other half cannot be: `rememberControlServicesLauncher()` answers from the manifest and the
 * package manager, and on desktop it is `null` by construction, so a test that went through it
 * could only ever observe "no button" and would pass for the wrong reason. This is the part worth
 * pinning — and on-device the two compose correctly: a *disabled* Control is installed (so the
 * words stay transport's) yet has no launch intent (so the button is correctly omitted).
 */
internal fun bannerActionKind(
    reason: UnreachableReason?,
    route: ControlInstallRoute,
    updatePages: AppUpdatePages,
): ControlBannerAction.Kind? = when {
    reason is UnreachableReason.Transport -> ControlBannerAction.Kind.OPEN_CONTROL
    reason is UnreachableReason.NotSetUp -> ControlBannerAction.Kind.OPEN_CONTROL
    reason is UnreachableReason.NotInstalled && route is ControlInstallRoute.Store ->
        ControlBannerAction.Kind.GET_CONTROL
    updatePageFor(reason, updatePages) != null -> ControlBannerAction.Kind.UPDATE
    else -> null
}

/**
 * The page an update offer opens: the one for the binary the verdict names, or `null`.
 *
 * ⚠⚠ Gated on the **reason**, never on reachability's `state`: a fenced device is reachable and
 * still owes the offer (`core-schema-skew-surface.md` trap).
 */
internal fun updatePageFor(reason: UnreachableReason?, updatePages: AppUpdatePages): String? = when (reason) {
    is UnreachableReason.Skew -> updatePages.pageFor(reason.update)
    is UnreachableReason.Fenced -> updatePages.pageFor(reason.update)
    else -> null
}
