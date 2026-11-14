package app.tenshu.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How [AdaptiveNavScaffold] draws its chrome in [WindowWidthSizeClass.Compact]. Wider classes
 * always get the rail; this only chooses what a phone sees.
 */
enum class CompactNavStyle {
    /** The full-width M3 `NavigationBar`, docked to the bottom edge. The default. */
    Bar,

    /**
     * A content-width pill that floats over the content, M3 Expressive's floating-toolbar shape
     * holding `ShortNavigationBarItem`s: the selected one shows its label beside the icon, the
     * others are icon-only.
     *
     * For an app with **two or three** destinations, where a docked 80dp bar spends a whole strip
     * of the screen on very little. The content runs edge to edge underneath, so every scrolling
     * container under it has to read [LocalNavBarClearance] and add it to its bottom
     * `contentPadding`, or its last row ends up behind the pill.
     */
    Floating,
}

/**
 * How far from the content area's bottom edge the floating nav pill reaches: pill, margin and
 * whatever navigation-bar inset is still unconsumed. Add it to a scrolling container's bottom
 * `contentPadding`.
 *
 * ⚠ `0.dp` everywhere except under a [CompactNavStyle.Floating] scaffold that is actually showing
 * the pill, so a screen can read it unconditionally. ⚠ It is measured, so it reads `0.dp` for the
 * first frame the pill appears.
 */
val LocalNavBarClearance = compositionLocalOf<Dp> { 0.dp }

/** Test handle for the floating pill. */
const val FLOATING_NAV_BAR_TAG = "floatingNavBar"

/**
 * Height of the pill: the 40dp active indicator plus 8dp of container above and below — what
 * Google Photos' own floating bar measures on a device. ⚠ The items are centred in it; top-aligned
 * (a `Row`'s default) the indicator hangs off the top edge and the pill reads as too big for it.
 */
internal val FloatingNavBarHeight = 56.dp

/** Gap between the pill and the bottom of the window (above the system navigation bar). */
internal val FloatingNavBarBottomMargin = 16.dp

@Composable
internal fun FloatingNavBar(navItems: List<AdaptiveNavItem>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag(FLOATING_NAV_BAR_TAG),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        // Floats over photographs and lists, so it needs the lift a docked bar does not.
        shadowElevation = 6.dp,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .height(FloatingNavBarHeight)
                .padding(horizontal = 8.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            navItems.forEach { item ->
                ShortNavigationBarItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    icon = { NavItemIcon(item) },
                    // Only the selected tab spells its name, as Google Photos' bar does: the pill
                    // stays short, and every icon still carries the label as its description.
                    label = if (item.selected) ({ Text(item.label) }) else null,
                    iconPosition = NavigationItemIconPosition.Start,
                )
            }
        }
    }
}
