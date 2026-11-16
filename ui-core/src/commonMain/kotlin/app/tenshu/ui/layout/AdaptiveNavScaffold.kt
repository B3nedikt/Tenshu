package app.tenshu.ui.layout

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tenshu.ui.preview.PreviewBox
import androidx.compose.ui.tooling.preview.Preview

/**
 * A single top-level navigation destination as exposed by
 * [AdaptiveNavScaffold].
 *
 * [key] is an arbitrary identity used for selection comparisons; it lets
 * callers keep their richer domain type (e.g., `Destination`) and still
 * drive selection correctly. [onClick] is the full interaction —
 * callers are responsible for back-stack or state manipulation.
 *
 * @param key       caller-defined identity for this destination.
 * @param icon      leading icon drawn in the nav chrome.
 * @param label     human-readable label. Used as icon a11y description
 *                  as well, so keep it short and specific.
 * @param selected  whether this destination is currently active.
 * @param onClick   tap handler.
 */
data class AdaptiveNavItem(
    val key: Any,
    val icon: ImageVector,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    /** Draws a small attention dot on the icon (e.g. Drive's pending-contact prompt). */
    val showBadge: Boolean = false,
    /**
     * Renders this destination with a filled, high-emphasis icon container — a
     * prominent inline call-to-action (e.g. Fit's centre *Record* destination). Strictly
     * additive: the default is the ordinary flat nav item every existing caller gets.
     */
    val emphasized: Boolean = false,
)

@Composable
internal fun NavItemIcon(item: AdaptiveNavItem) {
    val icon: @Composable () -> Unit = {
        if (item.emphasized) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.label,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        } else {
            Icon(item.icon, contentDescription = item.label)
        }
    }
    if (item.showBadge) {
        BadgedBox(badge = { Badge() }) { icon() }
    } else {
        icon()
    }
}

/**
 * Adaptive navigation scaffold shared across Tenshu apps (Drive, Control).
 *
 * ### What it does
 *
 * Renders the canonical Material nav pattern for the current
 * [WindowWidthSizeClass]:
 *
 * - [WindowWidthSizeClass.Compact] — [NavigationBar] at the bottom.
 * - Every wider class — [WideNavigationRail] leading, **always collapsed**
 *   (96dp), with no expand/collapse control.
 *
 * ### ⚠⚠ Why there is no expand toggle — at any width
 *
 * A [WideNavigationRailItem] *"always displays labels (if they exist) when
 * selected and unselected"*: collapsed merely stacks the label under the icon
 * instead of beside it, which is why the collapsed wide rail is 96dp and not
 * the classic rail's 80dp. Expanding therefore hides nothing and reveals
 * nothing — it re-flows identical text sideways for 124–264dp of body width.
 *
 * Material's expanded rail earns its width by *revealing secondary
 * destinations the collapsed rail hides* and by hosting a header FAB or logo.
 * Every app here passes one flat list of 2–5 destinations, inside the
 * three-to-seven range where M3 says the collapsed rail is the right
 * component. The toggle was offered first from Expanded, then from Large, and
 * at both it was a control with an empty payload. Don't bring it back — or an
 * expanded default — until an app has secondary destinations or header
 * content to show (`ui-adaptive-navigation.md` §7.4 / §10, N7).
 *
 * The body pane carries the Material window margin (see
 * [Modifier.appWindowMargin]); the rail stays flush to the leading edge,
 * because Material models rails as the window perimeter rather than as a
 * pane.
 *
 * ### Why a rail and not a drawer
 *
 * Material 3 deprecated the navigation drawer in favour of the expanded
 * rail, which "adapts better across breakpoints". Concretely: the drawer
 * this replaced was a fixed 300dp at every window width and with every
 * label, because `PermanentDrawerSheet`'s `widthIn` cannot beat
 * `NavigationDrawerItem`'s internal `fillMaxWidth()`. The expanded rail
 * sizes to its content instead. See
 * `docs/proposals/ui-adaptive-navigation.md`.
 *
 * ### When to use
 *
 * At the root of any Drive/Control main shell that has multiple
 * top-level destinations. Callers remain responsible for their own
 * navigation host and back stack — this component only owns the chrome.
 *
 * ### Variants
 *
 * Set [showNav] to `false` on screens that should render edge-to-edge
 * without chrome (login, onboarding, full-screen dialogs). Every
 * size-class branch collapses to just the content area, so consumers
 * don't have to special-case their layout.
 *
 * ### ⚠⚠ [content] is invoked from exactly one source position — keep it that way
 *
 * A Compose group's identity is where it is *written*, not what it renders. This scaffold used to
 * call `content()` from three places — a `!showNav` early return, the compact branch and the rail
 * branch — so **every** change of chrome discarded the whole content subtree and built a fresh one.
 *
 * That is not a repaint. It throws away every `remember` beneath it, which for an app shell means
 * the nav host's `rememberViewModelStoreNavEntryDecorator`: **each screen's ViewModel was cleared
 * and reconstructed**. Six apps pass `showNav = current is Destination.TopLevel`, so all six paid it
 * on every push and pop of a detail screen. It surfaced on Fit's History tab (2026-08-12) as a week
 * roll-up counting up from 0, a paged feed refreshing from page 0, and a lost scroll position on the
 * way back from a workout. Drive was the one app that never reproduced it — for no better reason
 * than that it never passes `showNav`.
 *
 * So: one `Scaffold`, one `Row`, one `Box`, one `content()`. The chrome is a *sibling* that comes
 * and goes; hiding a sibling leaves the following group's identity untouched. Anything conditional
 * belongs in a modifier or in the chrome, never in a second call to [content].
 * `AdaptiveNavContentIdentityTest` pins this.
 *
 * ### Accessibility
 *
 * Each [AdaptiveNavItem.label] is set both as visible text *and* as the
 * icon content description in every size class, so the destination is
 * announced identically in every size class. The rail is always visible
 * beside the content, so Tab navigation reaches it before the content area.
 *
 * @param navItems              ordered list of top-level destinations.
 * @param showNav               when false, chrome is hidden and only
 *                              [content] is drawn.
 * @param contentWindowInsets   insets forwarded to the underlying
 *                              `Scaffold`; defaults to zero so callers
 *                              handle insets themselves.
 * @param content               main content area.
 */
@Composable
fun AdaptiveNavScaffold(
    navItems: List<AdaptiveNavItem>,
    modifier: Modifier = Modifier,
    showNav: Boolean = true,
    /** What a phone sees; see [CompactNavStyle]. Ignored at every wider size class. */
    compactNavStyle: CompactNavStyle = CompactNavStyle.Bar,
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    /**
     * Drawn over [content], inside the content area — above the bottom bar, beside the rail — so an
     * indicator aligned to the bottom clears the nav chrome without measuring it
     * (`suite-sync-status.md` SS-2, the "Syncing…" capsule). ⚠ An overlay, never a sibling: a
     * sibling would shift the screen each time activity starts or ends.
     */
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val sizeClass = LocalWindowWidthSizeClass.current
    // Compact puts the chrome in a bottom bar; every wider class puts it in a leading rail. Both
    // are siblings of the content, so which one is drawn — or neither — never reaches [content].
    val compact = showNav && sizeClass == WindowWidthSizeClass.Compact
    val floating = compactNavStyle == CompactNavStyle.Floating
    val showBar = compact && !floating
    val showPill = compact && floating
    val showRail = showNav && sizeClass != WindowWidthSizeClass.Compact
    // Measured off the pill's wrapper, which spans from the pill's top to the content's bottom
    // edge. Reset when the pill goes, so a pushed screen never inherits the tab's clearance.
    var pillClearancePx by remember { mutableIntStateOf(0) }
    val clearance = if (showPill) with(LocalDensity.current) { pillClearancePx.toDp() } else 0.dp

    Scaffold(
        modifier = modifier,
        // ⚠ Always passed, and empty when the bar is hidden. An `if` that emits nothing leaves the
        // bottom-bar slot with no measurables, which is exactly what omitting the parameter did —
        // the scaffold falls back to `contentWindowInsets` for the bottom padding either way.
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    navItems.forEach { item ->
                        NavigationBarItem(
                            icon = { NavItemIcon(item) },
                            label = { Text(item.label) },
                            selected = item.selected,
                            onClick = item.onClick,
                        )
                    }
                }
            }
        },
        contentWindowInsets = contentWindowInsets,
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // ⚠ Only for the pill, so the docked bar and the rail keep exactly the insets they
                // always had: what the scaffold already applied must not be applied a second
                // time by the pill's own `windowInsetsPadding`.
                .then(if (floating) Modifier.consumeWindowInsets(innerPadding) else Modifier),
        ) {
            if (showRail) NavRail(navItems)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The window margin belongs to the rail layout only: compact takes none, and a
                    // chrome-less screen is deliberately edge-to-edge.
                    .then(if (showRail) Modifier.appWindowMargin() else Modifier),
            ) {
                // ⚠ Always provided, 0.dp when there is no pill, so this stays the one call site of
                // `content()` (see the KDoc) whichever chrome is showing.
                CompositionLocalProvider(LocalNavBarClearance provides clearance) {
                    content()
                }
                // The overlay is lifted above the pill, so a bottom-aligned capsule clears it the
                // same way it clears the docked bar.
                Box(Modifier.fillMaxSize().padding(bottom = clearance)) { overlay() }
                if (showPill) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // ⚠ First in the chain, so the size includes the inset and margin below.
                            .onSizeChanged { pillClearancePx = it.height }
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = FloatingNavBarBottomMargin),
                    ) {
                        FloatingNavBar(navItems)
                    }
                }
            }
        }
    }
}

/**
 * The leading navigation rail — every non-compact size class renders this same collapsed rail.
 * See [AdaptiveNavScaffold]'s KDoc for why it never expands.
 */
@Composable
private fun NavRail(navItems: List<AdaptiveNavItem>) {
    WideNavigationRail {
        navItems.forEach { item ->
            WideNavigationRailItem(
                icon = { NavItemIcon(item) },
                label = { Text(item.label) },
                selected = item.selected,
                onClick = item.onClick,
                railExpanded = false,
            )
        }
    }
}

private fun sampleNavItems(selectedKey: String) = listOf(
    AdaptiveNavItem(
        key = "files",
        icon = Icons.Default.Folder,
        label = "Files",
        selected = selectedKey == "files",
        onClick = {},
    ),
    AdaptiveNavItem(
        key = "dashboard",
        icon = Icons.Default.Dashboard,
        label = "Dashboard",
        selected = selectedKey == "dashboard",
        onClick = {},
    ),
    AdaptiveNavItem(
        key = "settings",
        icon = Icons.Default.Settings,
        label = "Settings",
        selected = selectedKey == "settings",
        onClick = {},
    ),
)

@Composable
private fun SampleContent(label: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = label,
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Preview
@Composable
private fun AdaptiveNavScaffoldCompactPreview() = PreviewBox(
    windowWidthSizeClass = WindowWidthSizeClass.Compact,
    padding = PaddingValues(0.dp),
) {
    AdaptiveNavScaffold(navItems = sampleNavItems(selectedKey = "files")) {
        SampleContent("Compact — bottom nav bar")
    }
}

@Preview
@Composable
private fun AdaptiveNavScaffoldMediumPreview() = PreviewBox(
    windowWidthSizeClass = WindowWidthSizeClass.Medium,
    padding = PaddingValues(0.dp),
) {
    AdaptiveNavScaffold(navItems = sampleNavItems(selectedKey = "dashboard")) {
        SampleContent("Medium — collapsed rail, no toggle")
    }
}

@Preview
@Composable
private fun AdaptiveNavScaffoldExpandedPreview() = PreviewBox(
    windowWidthSizeClass = WindowWidthSizeClass.Expanded,
    padding = PaddingValues(0.dp),
) {
    AdaptiveNavScaffold(navItems = sampleNavItems(selectedKey = "settings")) {
        SampleContent("Expanded — collapsed rail, no toggle")
    }
}

@Preview
@Composable
private fun AdaptiveNavScaffoldLargePreview() = PreviewBox(
    windowWidthSizeClass = WindowWidthSizeClass.Large,
    padding = PaddingValues(0.dp),
) {
    AdaptiveNavScaffold(navItems = sampleNavItems(selectedKey = "settings")) {
        SampleContent("Large — collapsed rail, no toggle")
    }
}

@Preview
@Composable
private fun AdaptiveNavScaffoldHiddenPreview() = PreviewBox(
    padding = PaddingValues(0.dp),
) {
    AdaptiveNavScaffold(
        navItems = sampleNavItems(selectedKey = "files"),
        showNav = false,
    ) {
        SampleContent("Chrome hidden (e.g., login)")
    }
}

@Preview
@Composable
private fun AdaptiveNavScaffoldDarkPreview() = PreviewBox(
    useDarkTheme = true,
    windowWidthSizeClass = WindowWidthSizeClass.Compact,
    padding = PaddingValues(0.dp),
) {
    AdaptiveNavScaffold(navItems = sampleNavItems(selectedKey = "files")) {
        SampleContent("Compact — dark")
    }
}
