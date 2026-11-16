package app.tenshu.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.tenshu.ui.test.NoMotion

/**
 * Pins **how wide the navigation chrome is** at every window size class, how much window margin
 * the body pane gets, and therefore where that pane starts and ends.
 *
 * ### Why this exists
 *
 * The permanent drawer this replaced rendered at a fixed 300 dp for every window width and every
 * label set — `PermanentDrawerSheet`'s `widthIn(min = 240, max = 300)` could never reach its
 * minimum, because `NavigationDrawerItem` applies `fillMaxWidth()` internally and so always took
 * the maximum of the incoming constraints. Nobody noticed for months: no test in this repo
 * measured navigation chrome, and the Android smoke gate only asserts that nothing crashes — a
 * 300 dp sidebar is not a crash.
 *
 * Every number below was read off a real render rather than copied from the spec.
 *
 * ⚠ Density is 1.0 in `runDesktopComposeUiTest`, but bounds are converted through [LocalDensity]
 * anyway, so a future density change surfaces as a real failure rather than an off-by-a-scale
 * mystery.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveNavMetricsTest {

    private val shortLabels = listOf("Home", "Files", "Fit")
    private val veryLongLabels =
        listOf("Home", "Shared with me and other people", "Recently deleted items")

    /** Left and right edges of the body pane, in dp. */
    private data class Body(val left: Float, val right: Float)

    private fun measureBody(
        windowWidthPx: Int,
        labels: List<String>,
    ): Body {
        var density = 0f
        var body = Body(-1f, -1f)
        runDesktopComposeUiTest(width = windowWidthPx, height = 900, effectContext = NoMotion) {
            setContent {
                TenshuTheme {
                    density = LocalDensity.current.density
                    AdaptiveNavScaffold(navItems = navItems(labels)) {
                        Box(Modifier.fillMaxSize().testTag(BODY))
                    }
                }
            }
            waitForIdle()
            val bounds = onNodeWithTag(BODY).fetchSemanticsNode().boundsInRoot
            body = Body(bounds.left / density, bounds.right / density)
        }
        return body
    }

    private fun navItems(labels: List<String>) = labels.mapIndexed { index, label ->
        AdaptiveNavItem(
            key = "key$index",
            icon = Icons.Outlined.Home,
            label = label,
            selected = index == 0,
            onClick = {},
        )
    }

    // --- Navigation chrome width ------------------------------------------------------------

    @Test
    fun compact_hasNoLeadingChromeAndNoMargin() {
        // Compact puts the NavigationBar at the bottom, and takes no window margin — the 24 dp
        // figure is only confirmed for medium and above, so phones are left exactly as they were.
        val body = measureBody(599, shortLabels)
        assertEquals(0f, body.left, "compact body must start at x=0")
        assertEquals(599f, body.right, "compact body must run to the window edge")
    }

    @Test
    fun everyWiderClass_railIsCollapsedAt96dp() {
        // `WideNavigationRailCollapsedTokens.ContainerWidth`, from Medium through ExtraLarge.
        // 1024 is the old default desktop window, where the drawer once rendered 300 dp of
        // mostly-empty sheet; 1280 and 1700 are where the rail used to *start expanded*. Long
        // labels are included because the expanded rail sized to them — the collapsed one must not.
        listOf(800, 1024, 1199, 1280, 1700).forEach { windowWidth ->
            listOf(shortLabels, veryLongLabels).forEach { labels ->
                assertEquals(
                    RAIL_COLLAPSED + MARGIN,
                    measureBody(windowWidth, labels).left,
                    "rail must be collapsed to 96 dp (+24 dp margin) at width $windowWidth " +
                        "with labels $labels",
                )
            }
        }
    }

    // --- Window margin ----------------------------------------------------------------------

    @Test
    fun mediumAndAbove_bodyKeepsA24dpWindowMargin() {
        // M3 specifies a 24 dp window margin from medium up. Before this landed the body ran
        // flush to the window edge at every size class — the "margins at the end of the UI"
        // complaint that opened this work.
        listOf(800, 1024, 1700).forEach { windowWidth ->
            assertEquals(
                windowWidth - MARGIN,
                measureBody(windowWidth, shortLabels).right,
                "body must keep a 24 dp trailing margin at width $windowWidth",
            )
        }
    }

    // --- No expand / collapse toggle --------------------------------------------------------

    @Test
    fun railOffersNoExpandToggleAtAnyWidth() {
        // ⚠⚠ The toggle was offered from Expanded, then from Large, and at both it re-flowed
        // labels the collapsed rail already shows — a `WideNavigationRailItem` always draws its
        // label. With no secondary destinations or header content to reveal it had nothing to
        // do (`ui-adaptive-navigation.md` §10). If this goes red, an app must first have
        // something the expanded rail reveals; read §7.4 (N7) before weakening it.
        listOf(800, 1024, 1280, 1700).forEach { windowWidth ->
            runDesktopComposeUiTest(width = windowWidth, height = 900, effectContext = NoMotion) {
                setContent {
                    TenshuTheme { AdaptiveNavScaffold(navItems = navItems(shortLabels)) {} }
                }
                waitForIdle()
                onAllNodesWithContentDescription(EXPAND, useUnmergedTree = true)
                    .assertCountEquals(0)
                onAllNodesWithContentDescription(COLLAPSE, useUnmergedTree = true)
                    .assertCountEquals(0)
            }
        }
    }

    @Test
    fun destinationIconsSitOnTheCollapsedIconColumn() {
        var density = 0f
        runDesktopComposeUiTest(width = 1280, height = 900, effectContext = NoMotion) {
            setContent {
                TenshuTheme {
                    density = LocalDensity.current.density
                    AdaptiveNavScaffold(navItems = navItems(shortLabels)) {}
                }
            }
            waitForIdle()
            // Unmerged: `selectable` merges the icon's description into the item, whose bounds are
            // the whole 96 dp cell and would tell us nothing about the icon.
            val left = onNodeWithContentDescription("Home", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.left / density
            assertEquals(ICON_COLUMN, left, "collapsed destination icon column")
        }
    }

    private companion object {
        const val BODY = "adaptive-nav-body"
        const val EXPAND = "Expand navigation"
        const val COLLAPSE = "Collapse navigation"

        /** `WideNavigationRailCollapsedTokens.ContainerWidth`. */
        const val RAIL_COLLAPSED = 96f

        /** The Material window margin from medium up — see `windowMarginFor`. */
        const val MARGIN = 24f

        /**
         * Where a collapsed rail icon's leading edge sits: `(ActiveIndicatorWidth 56 − IconSize
         * 24) / 2` (16 dp) + `ItemHorizontalPadding` (20 dp).
         */
        const val ICON_COLUMN = 36f
    }
}
