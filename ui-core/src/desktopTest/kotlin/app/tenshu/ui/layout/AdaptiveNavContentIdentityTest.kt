package app.tenshu.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins that [AdaptiveNavScaffold] **keeps its content subtree alive** when the chrome changes —
 * when `showNav` flips, and when the window crosses a size-class breakpoint.
 *
 * ### Why this exists
 *
 * ⚠⚠ Every app but Drive passes `showNav = current is Destination.TopLevel`, so the chrome
 * disappears the moment you open a detail screen and comes back when you pop. The scaffold used to
 * call `content()` from **three** source positions (a no-chrome early return, the compact branch,
 * the rail branch), and a Compose group's identity is its *source position* — so each flip threw the
 * entire content subtree away and rebuilt it.
 *
 * What that cost, concretely, on Fit's History tab (2026-08-12): the nav host under the scaffold
 * lost its `rememberViewModelStoreNavEntryDecorator`, so **every screen's ViewModel was cleared and
 * reconstructed** on the way into a workout and again on the way back. The week roll-up visibly
 * counted up from 0, the paged feed refreshed from page 0, and the user's scroll position was gone.
 * Drive was the one app that never reproduced it — for the single reason that it never passes
 * `showNav`.
 *
 * ⚠ A `remember` in the content is the honest probe here: it is exactly what a `ViewModelStore`
 * decorator, a `SaveableStateHolder` and a `LazyListState` are all built on. Counting *compositions*
 * would not do — the content is expected to recompose freely; what must not happen is for it to be
 * **discarded and re-entered**.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveNavContentIdentityTest {

    /** Increments once per *fresh* content subtree, never on a mere recomposition. */
    private class Instances {
        var count = 0
    }

    @Composable
    private fun Probe(instances: Instances) {
        remember { instances.count++ }
        Box(Modifier.fillMaxSize())
    }

    private fun navItems() = listOf("Home", "Files", "Fit").mapIndexed { index, label ->
        AdaptiveNavItem(
            key = "key$index",
            icon = Icons.Outlined.Home,
            label = label,
            selected = index == 0,
            onClick = {},
        )
    }

    private fun assertContentSurvives(windowWidthPx: Int) {
        val instances = Instances()
        runDesktopComposeUiTest(width = windowWidthPx, height = 900) {
            var showNav by mutableStateOf(true)
            setContent {
                TenshuTheme {
                    AdaptiveNavScaffold(navItems = navItems(), showNav = showNav) {
                        Probe(instances)
                    }
                }
            }
            waitForIdle()
            assertEquals(1, instances.count, "content must be created exactly once")

            // Push a detail screen: the chrome goes away.
            showNav = false
            waitForIdle()
            assertEquals(
                1,
                instances.count,
                "hiding the chrome must not rebuild the content subtree",
            )

            // Pop back to the tab: the chrome returns.
            showNav = true
            waitForIdle()
            assertEquals(
                1,
                instances.count,
                "restoring the chrome must not rebuild the content subtree",
            )
        }
    }

    @Test
    fun compact_contentSurvivesTheChromeComingAndGoing() = assertContentSurvives(599)

    @Test
    fun expanded_contentSurvivesTheChromeComingAndGoing() = assertContentSurvives(1024)

    /**
     * The same defect the other way round: a desktop window dragged across the compact breakpoint
     * swapped the bottom bar for the rail, which used to be a *different* `content()` call site and
     * so was just as destructive — silently, because nobody navigates while resizing.
     */
    @Test
    fun contentSurvivesCrossingTheCompactBreakpoint() {
        val instances = Instances()
        runDesktopComposeUiTest(width = 1024, height = 900) {
            var sizeClass by mutableStateOf(WindowWidthSizeClass.Compact)
            setContent {
                // ⚠ Inside `TenshuTheme`, not around it: the theme wraps its content in
                // `ProvideWindowWidthSizeClass`, which would overwrite the local with the size class
                // the real 1024 px test window implies — and the test would pin nothing.
                TenshuTheme {
                    CompositionLocalProvider(LocalWindowWidthSizeClass provides sizeClass) {
                        AdaptiveNavScaffold(navItems = navItems()) {
                            Probe(instances)
                        }
                    }
                }
            }
            waitForIdle()
            assertEquals(1, instances.count, "content must be created exactly once")

            sizeClass = WindowWidthSizeClass.Expanded
            waitForIdle()
            assertEquals(
                1,
                instances.count,
                "swapping the bottom bar for the rail must not rebuild the content subtree",
            )

            sizeClass = WindowWidthSizeClass.Compact
            waitForIdle()
            assertEquals(
                1,
                instances.count,
                "swapping the rail back for the bottom bar must not rebuild the content subtree",
            )
        }
    }
}
