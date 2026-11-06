package app.tenshu.control.ui.common

import androidx.compose.runtime.Composable

/**
 * Platform "open this URL in the system browser", used by the paid-tier checkout flow to hand the
 * user off to Stripe's hosted Checkout / billing portal (`commerce-accounts-and-sessions.md` §6).
 * Android fires an `ACTION_VIEW` intent; desktop uses `java.awt.Desktop.browse`.
 *
 * [canOpen] decides whether the affordance is offered at all — `true` on Android, and on desktop
 * where a browser handler exists; a headless / unsupported desktop hides it. [open] takes the
 * absolute URL; it swallows failures so a missing handler never crashes the screen.
 *
 * Mirrors Drive's `OpenFileLauncher` seam. Unlike that one this hands over no user data — only a
 * Stripe URL carrying the caller's `ownerId` (as `client_reference_id`), which is already the
 * commerce plane's identifier (no relay/House-Rule-#1 exposure).
 */
class OpenExternalUrlLauncher(
    val canOpen: Boolean,
    val open: (url: String) -> Unit,
)

@Composable
expect fun rememberOpenExternalUrlLauncher(): OpenExternalUrlLauncher
