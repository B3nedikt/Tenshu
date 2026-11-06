package app.tenshu.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tenshu.ui.theme.spacing
import app.tenshu.ui.preview.PreviewBox
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign

/**
 * The **full-screen** forward-to-Control surface — Compose's twin of iOS's `ConnectToControlView`
 * (`ADR 0022` §Decision 1, `ADR 0025` §Decision 1).
 *
 * ## ⚠⚠ Why this replaces the whole app rather than sitting above it
 *
 * ADR 0025 reasoned that `NotInstalled` is the one reason that is **permanent until the user acts**,
 * and therefore the one that earns the stronger surface — and then shipped a banner for it anyway.
 * A device pass showed what that costs. With Control uninstalled, Timeline rendered its banner over
 * *"Your timeline starts here — Import from Google Takeout"*: an empty-library claim that is false,
 * above a button that cannot work, on a device with no realm at all. The rest of the UI was not a
 * distraction from the message; it was **actively contradicting** it.
 *
 * So on a platform where every read goes over IPC to Control, an absent Control leaves nothing
 * truthful to render. This surface takes the whole window — no nav, no empty states, no storage
 * banner underneath it.
 *
 * ⚠ **Only for the two reasons that are permanent until the user acts** — `NotInstalled`, and
 * `NotSetUp` (Control installed, no realm; ADR 0025 §Decision 8).
 * A transport outage is transient and self-recovering, and a
 * full-screen wall that slams down on a three-second blip is worse than any banner; that case keeps
 * [ErrorBanner]. The rule is the reason, not the platform.
 *
 * ## ⚠ Insets are this component's own problem
 *
 * It applies `WindowInsets.safeDrawing` itself rather than trusting a parent. The bug that prompted
 * the redesign was exactly this: `AdaptiveNavScaffold` passes `contentWindowInsets = 0`, so a banner
 * placed at the top of its *content* — rather than in a `Scaffold`'s `topBar` slot — drew under the
 * status bar, with the clock and battery icons on top of the text. A surface that replaces the
 * window cannot inherit a caller's inset handling, because there is no longer a caller's chrome to
 * inherit it from.
 *
 * ⚠ **The caller supplies the localised copy**, matching [ErrorBanner] and `RealmStorageBanner` —
 * only the app knows what *it* loses without Control (ADR 0022 §Decision 2: a generic sentence would
 * be true of nothing in particular), so each app passes strings from its own catalogue.
 *
 * ### When to use
 * In an app root, in place of the whole shell, for `UnreachableReason.NotInstalled` and
 * `UnreachableReason.NotSetUp`, and as the retry phase of [AppControlCheckingGate]. Not for a
 * transport outage, a version skew or a fenced device — those keep [ErrorBanner].
 *
 * ### Variants
 * - **With an action** — [actionLabel] and [onAction] both set: "Get Control" (`NotInstalled` with a
 *   store route), "Open Control" (`NotSetUp`), "Try again" (the checking gate's retry).
 * - **Statement only** — both null, where there is no route to offer; [body] says what to do.
 * - [icon] follows the reason (`controlBannerIcon`): get, settings, or the checking gate's settings.
 *
 * ### Accessibility
 * The icon is decorative (`contentDescription = null`) — the title says the same thing in words.
 * The title is marked as a heading, so a screen reader announces the wall's subject first and can
 * navigate to it; title, body and the one button are read in that order, and the button is the
 * only focusable element, so it is keyboard-reachable.
 *
 * @param title What is wrong, as a heading ("Set up Tenshu Control").
 * @param body What the app loses and what the user can do, in the app's own words.
 * @param icon The reason's icon; decorative.
 * @param actionLabel The one action's label, or null for none.
 * @param onAction The one action, or null for none; the button shows only when both are set.
 */
@Composable
fun AppControlUnavailableWall(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.GetApp,
    /** ⚠ Both `null` where there is no route to offer — the wall then states the situation in [body] alone. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = MaterialTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(bottom = MaterialTheme.spacing.xl),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = MaterialTheme.spacing.m),
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.padding(top = MaterialTheme.spacing.xxl)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

// Preview copy only: real callers pass strings from their app's catalogue.
@Preview
@Composable
private fun AppControlUnavailableWallWithActionPreview() = PreviewBox {
    AppControlUnavailableWall(
        title = "Set up Tenshu Control",
        body = "Tenshu Control is installed but isn't set up on this device yet. Open it, then come back.",
        icon = Icons.Default.Settings,
        actionLabel = "Open Control",
        onAction = {},
    )
}

@Preview
@Composable
private fun AppControlUnavailableWallStatementPreview() = PreviewBox {
    AppControlUnavailableWall(
        title = "Set up Tenshu Control",
        body = "Tenshu Control isn't installed on this device, and it isn't in the store yet.",
    )
}

@Preview
@Composable
private fun AppControlUnavailableWallDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppControlUnavailableWall(
        title = "Set up Tenshu Control",
        body = "Tenshu Control isn't installed on this device. Install it, then come back.",
        actionLabel = "Get Control",
        onAction = {},
    )
}
