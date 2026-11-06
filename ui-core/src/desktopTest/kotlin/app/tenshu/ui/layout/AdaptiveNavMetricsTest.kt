package app.tenshu.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
 * Every number below was read off a real render rather than copied from the spec, which matters
 * more than it sounds: see [expandedRail_clampsToItsMinimumUntilLabelsEarnMore], where the obvious
 * test would have been wrong.
 *
 * ⚠ Density is 1.0 in `runDesktopComposeUiTest`, but bounds are converted through [LocalDensity]
 * anyway, so a future density change surfaces as a real failure rather than an off-by-a-scale
 * mystery.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveNavMetricsTest {

    private val shortLabels = listOf("Home", "Files", "Fit")
    private val longLabels = listOf("Home", "Shared with me", "Recently deleted")
    private val veryLongLabels =
        listOf("Home", "Shared with me and other people", "Recently deleted items")

    /** Left and right edges of the body pane, in dp. */
    private data class Body(val left: Float, val right: Float)

    private fun measureBody(
        windowWidthPx: Int,
        labels: List<String>,
        preference: NavRailPreference? = null,
    ): Body {
        var density = 0f
        var body = Body(-1f, -1f)
        runDesktopComposeUiTest(width = windowWidthPx, height = 900) {
            setContent {
                CompositionLocalProvider(LocalNavRailPreference provides preference) {
                    TenshuTheme {
                        density = LocalDensity.current.density
                        AdaptiveNavScaffold(navItems = navItems(labels)) {
                            Box(Modifier.fillMaxSize().testTag(BODY))
                        }
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
    fun mediumAndExpanded_railIsCollapsedAt96dp() {
        // `WideNavigationRailCollapsedTokens.ContainerWidth`. 1024 is the load-bearing case: it is
        // the default desktop window (WindowStatePersistence) and the width at which the old
        // drawer rendered 300 dp of mostly-empty sheet.
        listOf(800, 1024, 1199).forEach { windowWidth ->
            assertEquals(
                RAIL_COLLAPSED + MARGIN,
                measureBody(windowWidth, shortLabels).left,
                "rail must be collapsed to 96 dp (+24 dp margin) at window width $windowWidth",
            )
        }
    }

    @Test
    fun expandedRail_clampsToItsMinimumUntilLabelsEarnMore() {
        // ⚠ The obvious version of this test is wrong, which is why it is spelled out. The
        // expanded rail is width-*ranged* (220–360 dp), not width-*fitting*: "Recently deleted"
        // still fits inside the 220 dp minimum, so short and merely-longish label sets measure
        // identically. Asserting they differ would fail for a reason that has nothing to do with
        // the behaviour under test.
        listOf(1280, 1700).forEach { windowWidth ->
            assertEquals(
                RAIL_EXPANDED_MIN + MARGIN,
                measureBody(windowWidth, shortLabels).left,
                "expanded rail must sit at its 220 dp minimum at $windowWidth",
            )
            assertEquals(
                RAIL_EXPANDED_MIN + MARGIN,
                measureBody(windowWidth, longLabels).left,
                "labels that fit inside the minimum must not widen the rail at $windowWidth",
            )
        }
    }

    @Test
    fun expandedRail_growsWithItsWidestLabel() {
        // The property the fixed-width drawer never had: past the 220 dp floor the rail tracks
        // its content, capped at 360 dp.
        val rail = measureBody(1280, veryLongLabels).left - MARGIN
        assertTrue(
            rail > RAIL_EXPANDED_MIN && rail <= RAIL_EXPANDED_MAX,
            "expanded rail must grow with its widest label, within 220–360 dp (was $rail)",
        )
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

    // --- Expand / collapse toggle -----------------------------------------------------------

    @Test
    fun railIsToggleable_fromLargeUpButNotBelowIt() {
        // ⚠⚠ This assertion moved, and the reason it moved is the whole point. It used to put
        // the toggle at Expanded (1024) because the alternative was believed to leave that
        // window "icon-only with no way to reach labels". A `WideNavigationRailItem` always
        // draws its label — collapsed just stacks it under the icon — so there was nothing to
        // reach, and the button spent 124 dp of body width re-flowing text the user could
        // already read. Below Large the size class is now the entire rule.
        listOf(800, 1024, 1199).forEach { windowWidth ->
            runDesktopComposeUiTest(width = windowWidth, height = 900) {
                setContent {
                    TenshuTheme { AdaptiveNavScaffold(navItems = navItems(shortLabels)) {} }
                }
                waitForIdle()
                onAllNodesWithContentDescription(EXPAND).assertCountEquals(0)
                onAllNodesWithContentDescription(COLLAPSE).assertCountEquals(0)
            }
        }
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent { TenshuTheme { AdaptiveNavScaffold(navItems = navItems(shortLabels)) {} } }
            waitForIdle()
            // Large starts expanded, so the control on offer is the collapse direction.
            onAllNodesWithContentDescription(COLLAPSE).assertCountEquals(1)
        }
    }

    @Test
    fun toggle_collapsesAndReExpandsTheRailAtLarge() {
        // The choice the toggle exists to offer at Large: spend 220 dp on wider labels, or take
        // it back for the body. Both directions, because only the collapse direction is
        // reachable from the default state and a one-way control is a trap.
        var density = 0f
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent {
                TenshuTheme {
                    density = LocalDensity.current.density
                    AdaptiveNavScaffold(navItems = navItems(shortLabels)) {
                        Box(Modifier.fillMaxSize().testTag(BODY))
                    }
                }
            }
            waitForIdle()
            fun bodyLeft() = onNodeWithTag(BODY).fetchSemanticsNode().boundsInRoot.left / density

            assertEquals(
                RAIL_EXPANDED_MIN + MARGIN,
                bodyLeft(),
                "rail starts expanded at Large",
            )

            onNodeWithContentDescription(COLLAPSE).performClick()
            // The rail animates, so settle on the value rather than reading it immediately.
            waitUntil(timeoutMillis = 5_000) { bodyLeft() <= RAIL_COLLAPSED + MARGIN }
            assertEquals(
                RAIL_COLLAPSED + MARGIN,
                bodyLeft(),
                "toggling must collapse the rail to 96 dp",
            )

            onNodeWithContentDescription(EXPAND).performClick()
            waitUntil(timeoutMillis = 5_000) { bodyLeft() >= RAIL_EXPANDED_MIN }
            assertEquals(
                RAIL_EXPANDED_MIN + MARGIN,
                bodyLeft(),
                "toggling back must restore the 220 dp minimum",
            )
        }
    }

    @Test
    fun toggle_reportsItsStateSeparatelyFromItsAction() {
        // ⚠⚠ The two a11y properties are not interchangeable and the button needs both. The
        // content description is the *action* ("Collapse navigation" — what a click does); the
        // state description is what the rail *is*. Flipping only the description, as this did
        // before, renames the control between announcements and never reports a state at all.
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent { TenshuTheme { AdaptiveNavScaffold(navItems = navItems(shortLabels)) {} } }
            waitForIdle()

            onNodeWithContentDescription(COLLAPSE)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))

            onNodeWithContentDescription(COLLAPSE).performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithContentDescription(EXPAND).fetchSemanticsNodes().size == 1
            }
            onNodeWithContentDescription(EXPAND)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        }
    }

    // --- Header alignment -------------------------------------------------------------------

    @Test
    fun menuButtonSharesTheIconColumnWithTheDestinations() {
        // `WideNavigationRail` places its header placeable at x=0 and pads it not at all, so an
        // unpadded `IconButton` renders 24 dp to the left of the icons it heads — the "uncentered"
        // menu button. The 36 dp is not a spec figure to trust on sight: it is
        // `ItemHorizontalPadding` (20) + the leading space (16), and it happens to be the same in
        // both rail states, which is why one static start padding can align both.
        //
        // ⚠ 1280, not 1024: the header toggle only exists from `Large` up
        // (`railIsToggleable_fromLargeUpButNotBelowIt`), and 1024 is `Expanded` — this measured a
        // button that is not rendered there and failed on `assertExists`. Every other toggle test
        // in this file uses 1280 for the same reason.
        var density = 0f
        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent {
                TenshuTheme {
                    density = LocalDensity.current.density
                    AdaptiveNavScaffold(navItems = navItems(shortLabels)) {}
                }
            }
            waitForIdle()
            // Unmerged: `selectable` merges the icon's description into the item, whose bounds are
            // the whole 96 dp cell and would tell us nothing about the icon.
            fun iconLeft(description: String) =
                onNodeWithContentDescription(description, useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot.left / density

            // ⚠ Starts EXPANDED, not collapsed: at Large the size-class default is an expanded
            // rail (see `storedPreference_survivesRestartAtLarge`), so the toggle offers
            // "Collapse". The original order assumed the opposite and looked for a button that
            // reads "Expand" on the first frame.
            assertEquals(ICON_COLUMN, iconLeft("Home"), "expanded destination icon column")
            assertEquals(ICON_COLUMN, iconLeft(COLLAPSE), "menu icon must share that column")

            onNodeWithContentDescription(COLLAPSE).performClick()
            // Wait on the toggle's own name flipping, not on a measurement that is already equal —
            // `iconLeft("Home") == ICON_COLUMN` holds in both states, so waiting on it waits for
            // nothing and the assertions below could read the pre-click frame.
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithContentDescription(EXPAND, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }

            assertEquals(ICON_COLUMN, iconLeft("Home"), "collapsed destination icon column")
            assertEquals(ICON_COLUMN, iconLeft(EXPAND), "menu icon must share it when collapsed")
        }
    }

    // --- Persisted preference ---------------------------------------------------------------

    private class FakePreference(private var stored: Boolean?) : NavRailPreference {
        val writes = mutableListOf<Boolean>()
        override fun read(): Boolean? = stored
        override fun write(expanded: Boolean) {
            stored = expanded
            writes += expanded
        }
    }

    @Test
    fun storedPreference_survivesRestartAtLarge() {
        // A stored "collapsed" must beat the size-class default, which at 1280 is expanded.
        // ⚠ This is the assertion that catches the LaunchedEffect(sizeClass) footgun: that effect
        // also fires on first composition, so without its first-pass skip it would immediately
        // snap the rail back to the default and the restored preference would never be seen.
        assertEquals(
            RAIL_COLLAPSED + MARGIN,
            measureBody(1280, shortLabels, FakePreference(stored = false)).left,
            "a stored collapsed choice must be restored at Large",
        )
        assertEquals(
            RAIL_EXPANDED_MIN + MARGIN,
            measureBody(1280, shortLabels, FakePreference(stored = true)).left,
            "a stored expanded choice must be restored too",
        )
    }

    @Test
    fun storedPreference_isIgnoredBelowLargeWhereThereIsNoToggle() {
        // ⚠ The stored value has to be ignored *wherever the toggle is absent*, not just at
        // Medium — the two conditions are one condition and drifting them apart is how a user
        // ends up with 220 dp of an 840 dp window spent on navigation and no control to undo
        // it. 1024 is the case that would have been missed: it kept its toggle until now, so a
        // "true" written by an older build is still on disk for real users.
        listOf(800, 1024, 1199).forEach { windowWidth ->
            assertEquals(
                RAIL_COLLAPSED + MARGIN,
                measureBody(windowWidth, shortLabels, FakePreference(stored = true)).left,
                "width $windowWidth must stay collapsed regardless of the stored choice",
            )
        }
    }

    @Test
    fun onlyAnExplicitToggleIsPersisted() {
        // Opening the app must not write anything — otherwise a size-class default would be
        // recorded as though the user had chosen it.
        val preference = FakePreference(stored = null)
        measureBody(1280, shortLabels, preference)
        assertTrue(preference.writes.isEmpty(), "rendering alone must not write a preference")

        runDesktopComposeUiTest(width = 1280, height = 900) {
            setContent {
                CompositionLocalProvider(LocalNavRailPreference provides preference) {
                    TenshuTheme { AdaptiveNavScaffold(navItems = navItems(shortLabels)) {} }
                }
            }
            waitForIdle()
            onNodeWithContentDescription(COLLAPSE).performClick()
            waitForIdle()
        }
        assertEquals(listOf(false), preference.writes, "the toggle must record the new state")
    }

    private companion object {
        const val BODY = "adaptive-nav-body"
        const val EXPAND = "Expand navigation"
        const val COLLAPSE = "Collapse navigation"

        /** `WideNavigationRailCollapsedTokens.ContainerWidth`. */
        const val RAIL_COLLAPSED = 96f

        /** `NavigationRailExpandedTokens.ContainerWidthMinimum` / `…Maximum`. */
        const val RAIL_EXPANDED_MIN = 220f
        const val RAIL_EXPANDED_MAX = 360f

        /** The Material window margin from medium up — see `windowMarginFor`. */
        const val MARGIN = 24f

        /**
         * Where a rail icon's leading edge sits, collapsed *and* expanded:
         * `ItemHorizontalPadding` (20 dp) + `NavigationRailHorizontalItemTokens`'
         * `FullWidthLeadingSpace` (16 dp), which is also
         * `(ActiveIndicatorWidth 56 − IconSize 24) / 2` for the collapsed top-icon layout.
         */
        const val ICON_COLUMN = 36f
    }
}
