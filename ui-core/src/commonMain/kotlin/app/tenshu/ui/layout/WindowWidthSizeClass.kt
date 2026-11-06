package app.tenshu.ui.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tenshu window-width size class, aligned with the Material 3
 * adaptive breakpoints:
 *
 * - [Compact]    — `< 600dp`. Phones in portrait.
 * - [Medium]     — `600dp – 839dp`. Phones in landscape, small tablets,
 *                  narrow desktop windows.
 * - [Expanded]   — `840dp – 1199dp`. Tablets in landscape, and the default
 *                  desktop window (1024dp — see `WindowStatePersistence`).
 * - [Large]      — `1200dp – 1599dp`. Maximised desktop windows.
 * - [ExtraLarge] — `≥ 1600dp`. Wide and ultra-wide desktop displays.
 *
 * Consumers read the current size class via [LocalWindowWidthSizeClass]
 * and branch on it — e.g., bottom bar vs. collapsed rail vs. expanded rail.
 *
 * ### ⚠ Compare with `>=`, not `==`
 *
 * The constants are declared in ascending width order, and enums are
 * [Comparable] by ordinal, so `sizeClass >= Expanded` is the idiom for
 * "expanded **and above**". **That ordering is load-bearing — do not
 * reorder the constants.**
 *
 * Prefer it over equality: an `== Expanded` check stops firing the moment
 * a wider class is added above it. That is exactly what [Large] and
 * [ExtraLarge] did to `Modifier.appContentWidth` — under `==` it drops its
 * 840dp reading cap on precisely the widest windows that need it most.
 *
 * ⚠ `ContentWidthOrderTest` does catch that particular one, but only by
 * luck: it renders at 1440dp, which this change moved from [Expanded] to
 * [Large]. Had it picked 1000dp the regression would have been silent.
 * Don't read the existing coverage as a net — verified by reverting the
 * comparison and watching exactly one test fail.
 *
 * ### Why not the androidx type
 *
 * This is intentionally a small enum rather than a wrapper around
 * `androidx.compose.material3.windowsizeclass.WindowSizeClass`, because
 * that artifact is Android-only and we need the abstraction to work
 * on Desktop (Compose Multiplatform) and iOS too.
 *
 * Breakpoints match `androidx.window.core.layout.WindowSizeClass`
 * (`MEDIUM=600`, `EXPANDED=840`, `LARGE=1200`, `EXTRA_LARGE=1600`); see
 * `docs/proposals/ui-adaptive-navigation.md` §0.3.
 */
enum class WindowWidthSizeClass {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge;

    companion object {
        /**
         * Classifies a raw width into a [WindowWidthSizeClass].
         */
        fun fromWidth(width: Dp): WindowWidthSizeClass = when {
            width < 600.dp -> Compact
            width < 840.dp -> Medium
            width < 1200.dp -> Expanded
            width < 1600.dp -> Large
            else -> ExtraLarge
        }
    }
}

/**
 * Composition local exposing the current [WindowWidthSizeClass].
 *
 * Provided by [ProvideWindowWidthSizeClass] (wrapped inside
 * [TenshuTheme]). Defaults to [WindowWidthSizeClass.Compact] so that
 * unprovided previews render sanely as a phone.
 */
val LocalWindowWidthSizeClass = staticCompositionLocalOf {
    WindowWidthSizeClass.Compact
}

/**
 * Computes the current window width size class via [BoxWithConstraints]
 * and exposes it through [LocalWindowWidthSizeClass].
 *
 * This is wired into [TenshuTheme] so every consumer has a value
 * without extra setup. The layout is a zero-cost wrapper — it passes
 * its constraints straight through to [content].
 */
@Composable
fun ProvideWindowWidthSizeClass(
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier) {
        val sizeClass = WindowWidthSizeClass.fromWidth(maxWidth)
        CompositionLocalProvider(
            LocalWindowWidthSizeClass provides sizeClass,
        ) {
            content()
        }
    }
}
