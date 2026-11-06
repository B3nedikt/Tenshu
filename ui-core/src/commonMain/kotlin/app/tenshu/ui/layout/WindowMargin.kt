package app.tenshu.ui.layout

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Material 3 window margin for a size class — the breathing room between the body pane and
 * the edges of the window.
 *
 * `0dp` on [WindowWidthSizeClass.Compact], `24dp` from [WindowWidthSizeClass.Medium] up.
 *
 * ⚠ **Compact is deliberately zero, and that is not the same as "M3 says zero".** The 24dp figure
 * for medium and above is confirmed by the spec; the widely-repeated 16dp compact margin could
 * **not** be verified from a primary source (m3.material.io is JS-rendered and unfetchable, and
 * the Android codelab explicitly declines to give per-class values). Rather than write an
 * unverified number into the token layer, compact keeps the per-screen spacing it already has —
 * which also means phones are byte-for-byte unchanged by this. See
 * `docs/proposals/ui-adaptive-navigation.md` §1 D5, open question Q2.
 */
fun windowMarginFor(sizeClass: WindowWidthSizeClass): Dp =
    if (sizeClass >= WindowWidthSizeClass.Medium) 24.dp else 0.dp

/**
 * Applies the Material 3 window margin to a body pane.
 *
 * ### When to use
 *
 * [AdaptiveNavScaffold] already applies this to the content it hosts, so screens do **not** need
 * it. Reach for it directly only when building a shell that does not go through the scaffold.
 *
 * ### Why horizontal only
 *
 * ⚠⚠ This deliberately pads the **start and end** edges and leaves top and bottom alone. Vertical
 * insets are already owned by each screen's top chrome: `AppTopAppBar` / `AppSearchBar` consume
 * the status-bar inset themselves, and `ui-agent.md` §8 records that adding a second vertical
 * inset on top of them is a real, previously-shipped bug ("the opposite mistake — overriding
 * `contentWindowInsets` *and* having top chrome — doubles the inset"). A top margin here would
 * reintroduce it across every app at once.
 *
 * The start edge matters even though navigation chrome sits there: Material models the rail as
 * the window perimeter, placed *outside* the panes, so the gap between rail and content is the
 * pane's margin to apply, not the rail's.
 *
 * ### Accessibility
 *
 * Purely decorative spacing — no semantics, no focus implications.
 */
@Composable
fun Modifier.appWindowMargin(): Modifier =
    this.padding(horizontal = windowMarginFor(LocalWindowWidthSizeClass.current))
