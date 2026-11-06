package app.tenshu.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.tenshu.ui.preview.PreviewBox
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch

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
private fun NavItemIcon(item: AdaptiveNavItem) {
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
 * The rail's expand/collapse control, drawn in the `WideNavigationRail` header.
 *
 * Modelled on AOSP's own `WideNavigationRailResponsiveSample`, which is the reference for this
 * pattern. Details of it that are load-bearing and were all missing when this first shipped (a
 * fourth, its `start` padding, is called out at the `IconButton` below):
 *
 * - **The icon reports direction.** `Menu` when collapsed, `MenuOpen` when expanded — otherwise a
 *   static hamburger gives no clue which way the next click goes. ⚠ `AutoMirrored`, because
 *   `MenuOpen` points at the content and must flip in RTL; `Menu` is symmetric and does not.
 * - **A tooltip names it.** This is the one control in the whole chrome with no visible label —
 *   every `WideNavigationRailItem` draws its own text in both rail states — so without a tooltip
 *   a sighted user has only the glyph.
 * - ⚠⚠ **`stateDescription` carries the state; the content description carries the action.** They
 *   are not interchangeable. A screen reader announces the name as the thing it will do
 *   ("Expand navigation") and the state separately as what the rail *is* — flipping only the name,
 *   as this did before, renames the button between announcements and never reports a state at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavRailToggle(expanded: Boolean, onToggle: () -> Unit) {
    val action = if (expanded) "Collapse navigation" else "Expand navigation"
    TooltipBox(
        // ⚠ `TooltipAnchorPosition.End`, not the sample's `TooltipAnchorPosition.Above`. The
        // sample anchors above because its rail is not at a window corner; ours is — the header
        // is the top-left-most control in the app, so "above" is off-screen and the tooltip
        // renders clipped against both edges (it did, in an earlier `AdaptiveNavRenderTest` PNG).
        // `End` opens it into the body pane, which is the one direction with room, and mirrors
        // correctly in RTL where the rail moves right.
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.End,
        ),
        tooltip = { PlainTooltip { Text(action) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onToggle,
            // ⚠ 24dp, from the same sample, and it is alignment rather than decoration: the
            // header is laid out flush to the rail's leading edge while every item's icon is
            // centred at 48dp. Without it the button hangs off the edge on its own — visible
            // in `AdaptiveNavRenderTest`'s 05/06 PNGs, and invisible to every measurement in
            // `AdaptiveNavMetricsTest`, which reads the body pane and never the header.
            // `start`, not `left`, so it follows the layout direction like `MenuOpen` does.
            modifier = Modifier
                .padding(start = 24.dp)
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
        ) {
            Icon(
                if (expanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Default.Menu,
                contentDescription = action,
            )
        }
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
 * - [WindowWidthSizeClass.Medium] / [WindowWidthSizeClass.Expanded] —
 *   [WideNavigationRail] leading, **collapsed** (96dp).
 * - [WindowWidthSizeClass.Large] / [WindowWidthSizeClass.ExtraLarge] —
 *   the same rail, **expanded**: width-ranged 220–360dp, sized to its
 *   widest item.
 *
 * From [WindowWidthSizeClass.Large] up — and only there — a leading menu
 * button toggles between the two; the size class chooses the default.
 * Supply a [LocalNavRailPreference] to make that choice survive a restart.
 *
 * ### Why the toggle stops at Large
 *
 * ⚠⚠ It used to be offered from [WindowWidthSizeClass.Expanded], on the
 * stated grounds that the default 1024dp desktop window would otherwise be
 * *"icon-only with no way to reach labels at all"*. **That premise was
 * false.** A [WideNavigationRailItem] *"always displays labels (if they
 * exist) when selected and unselected"* — collapsed merely stacks the label
 * under the icon instead of beside it, which is exactly why the collapsed
 * wide rail is 96dp and not the classic rail's 80dp. Nothing was hidden, so
 * nothing needed reaching.
 *
 * What the button actually bought, then, was a re-flow of identical content
 * in exchange for 124–235dp of body width — which is what "expanding shows
 * me nothing" felt like from the outside. Material's expanded rail earns its
 * width by *revealing secondary destinations the collapsed rail hides* and
 * by hosting a header FAB or logo; every app here passes one flat list of
 * 2–5 destinations to both states, all inside the three-to-seven range where
 * M3 says the collapsed rail is the right component. Until an app has
 * secondary destinations to reveal, the honest control is no control.
 *
 * At Large and up the choice is a real one — spend 220dp+ on wider labels
 * or don't — so the toggle stays there.
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
 * announced identically across compact/medium/expanded. The permanent
 * drawer is always visible on expanded, so Tab navigation reaches it
 * before the content area.
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
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    content: @Composable () -> Unit,
) {
    val sizeClass = LocalWindowWidthSizeClass.current
    // Compact puts the chrome in a bottom bar; every wider class puts it in a leading rail. Both
    // are siblings of the content, so which one is drawn — or neither — never reaches [content].
    val showBar = showNav && sizeClass == WindowWidthSizeClass.Compact
    val showRail = showNav && sizeClass != WindowWidthSizeClass.Compact

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
                .padding(innerPadding),
        ) {
            if (showRail) NavRail(navItems, sizeClass)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The window margin belongs to the rail layout only: compact takes none, and a
                    // chrome-less screen is deliberately edge-to-edge.
                    .then(if (showRail) Modifier.appWindowMargin() else Modifier),
            ) {
                content()
            }
        }
    }
}

/**
 * The leading navigation rail — every non-compact size class renders this same component and
 * differs only in whether it starts expanded. That is deliberate: it is one rail that grows, not
 * three chrome variants, so a window dragged across a breakpoint animates instead of swapping
 * components.
 *
 * Extracted from [AdaptiveNavScaffold] so the rail's own state lives and dies with the rail,
 * leaving the scaffold body a single unconditional path down to `content()`.
 */
@Composable
private fun RowScope.NavRail(
    navItems: List<AdaptiveNavItem>,
    sizeClass: WindowWidthSizeClass,
) {
    val preference = LocalNavRailPreference.current
    // Read once per preference instance: this is the stored *user* choice, and it
    // must not be re-read on every recomposition or it would fight the live state.
    val storedExpanded = remember(preference) { preference?.read() }
    val honoursStored = storedExpanded != null &&
        sizeClass >= WindowWidthSizeClass.Large

    val railState = rememberWideNavigationRailState(
        initialValue = if (honoursStored) {
            if (storedExpanded == true) {
                WideNavigationRailValue.Expanded
            } else {
                WideNavigationRailValue.Collapsed
            }
        } else {
            railValueFor(sizeClass)
        },
    )
    val scope = rememberCoroutineScope()

    // Re-apply the size-class default when the window crosses a breakpoint.
    // ⚠ Keyed on the size class, which also fires on first composition — so the
    // first pass is skipped when a stored choice was honoured above, or the
    // restored preference would be overwritten before it was ever visible.
    var firstPass by remember { mutableStateOf(true) }
    LaunchedEffect(sizeClass) {
        val skip = firstPass && honoursStored
        firstPass = false
        if (!skip) railState.snapTo(railValueFor(sizeClass))
    }

    val expanded = railState.targetValue == WideNavigationRailValue.Expanded

    WideNavigationRail(
        state = railState,
        // The toggle exists only where expanding is the *default* — Large and
        // up. Below that the size class is the whole rule and the rail is
        // always collapsed. See [AdaptiveNavScaffold]'s KDoc for why offering it at
        // Expanded was a net negative.
        header = if (sizeClass >= WindowWidthSizeClass.Large) {
            {
                NavRailToggle(
                    expanded = expanded,
                    onToggle = {
                        scope.launch { railState.toggle() }
                        // Only an explicit toggle is persisted. A window
                        // resize changes the rail too, but that is the
                        // size class talking, not the user.
                        preference?.write(!expanded)
                    },
                )
            }
        } else {
            null
        },
    ) {
        navItems.forEach { item ->
            WideNavigationRailItem(
                icon = { NavItemIcon(item) },
                label = { Text(item.label) },
                selected = item.selected,
                onClick = item.onClick,
                railExpanded = expanded,
            )
        }
    }
}

/**
 * The rail's *default* state for a window size class — collapsed up to
 * [WindowWidthSizeClass.Expanded], expanded from [WindowWidthSizeClass.Large] on.
 *
 * Matches the Material 3 breakpoint guidance: bar (compact), collapsed rail (medium and
 * expanded), expanded rail (large and extra-large). A stored [NavRailPreference] overrides
 * it from [WindowWidthSizeClass.Large] up, where the toggle exists — and nowhere else, or a
 * stored "expanded" would take 220dp of an 840dp window with no control left to undo it.
 */
private fun railValueFor(sizeClass: WindowWidthSizeClass): WideNavigationRailValue =
    if (sizeClass >= WindowWidthSizeClass.Large) {
        WideNavigationRailValue.Expanded
    } else {
        WideNavigationRailValue.Collapsed
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
        SampleContent("Large — expanded rail + menu toggle, sized to content")
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
