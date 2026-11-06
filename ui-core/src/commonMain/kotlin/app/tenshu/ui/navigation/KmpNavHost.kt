package app.tenshu.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Multi-platform navigation host. Renders the top entry of [backStack] and
 * wires platform back-press semantics (Android system back, Esc on desktop).
 *
 * This is navigation infrastructure, not a design-system primitive — it
 * has no visual surface of its own; the appearance of each destination is
 * defined by whatever the [entryProvider] produces. See `chrome/` for
 * composables that *do* have a visual specification.
 *
 * `expect`/`actual` is used so each target can plug into its platform's
 * transition APIs without dragging the others' dependencies along.
 *
 * ⚠⚠ **Never capture state *by value* in an entry's content lambda — pass the `State` object and
 * read it inside.** The two actuals do not agree on when an entry is rebuilt, and only one of them
 * forgives this:
 *
 * - **Android** builds a `NavEntry` per back-stack key through `NavDisplay`, which **caches** it.
 *   An entry created while `x == A` keeps rendering `A` forever, however many times its parent
 *   recomposes, because the lambda is never rebuilt.
 * - **Desktop** calls [entryProvider] again inside its own content lambda on every recomposition,
 *   so a by-value capture is refreshed and looks perfectly correct.
 *
 * ⚠ That asymmetry is what makes this dangerous: the broken form **passes every desktop test and
 * every desktop click-through**, and fails only on a device. It shipped once (2026-08-11, Timeline's
 * Home tabs: the nav chrome moved on every tap while the content pane stayed frozen — indis-
 * tinguishable from an unresponsive app). Reading a `State`/`MutableState` inside the entry keeps
 * the read in that entry's own composition scope, where the snapshot system can invalidate it, and
 * is correct under both actuals.
 *
 * @param backStack      navigation stack, top element shown.
 * @param modifier       host modifier.
 * @param onBack         called when the platform wants to pop (back press,
 *                       Esc, back swipe). Callers typically drop the top
 *                       element of [backStack].
 * @param entryProvider  maps a key to a [KmpNavEntry] describing its UI.
 */
@Composable
expect fun <T : Any> KmpNavHost(
    backStack: List<T>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = { },
    entryProvider: (T) -> KmpNavEntry<T>
)
