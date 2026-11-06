package app.tenshu.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.theme.spacing
import androidx.compose.ui.tooling.preview.Preview

/**
 * The maximum reading-ergonomic width for body content on large screens.
 *
 * Aligned with the Material 3 Expanded breakpoint (840dp) so that content
 * stops expanding exactly when [AdaptiveNavScaffold] starts giving the
 * window over to leading navigation chrome.
 */
val AppContentMaxWidth: Dp = 840.dp

/**
 * Caps content width for readability on expanded screens **and wider**
 * (tablets in landscape, desktop). On [WindowWidthSizeClass.Compact] and
 * [WindowWidthSizeClass.Medium] the modifier is a no-op — content fills
 * the available width.
 *
 * ### When to use
 *
 * On the root container of a screen's main content — lists, detail
 * views, forms. Prevents long lines of text and stretched controls on
 * ultra-wide desktop windows.
 *
 * ```
 * Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
 *     Column(
 *         modifier = Modifier
 *             .appContentWidth()   // ⚠ BEFORE the fill, never after
 *             .fillMaxSize()
 *     ) { … }
 * }
 * ```
 *
 * ⚠⚠ **Order is load-bearing, and getting it wrong fails silently.**
 * `fillMaxSize()`/`fillMaxWidth()` measure their child with a *fixed*
 * width, so a `widthIn(max = …)` placed after one receives
 * `minWidth == maxWidth == parentWidth` and `Constraints.constrain`
 * coerces the cap straight back up. Nothing throws — the content just
 * spans the whole window, exactly as if the modifier were absent.
 * `ContentWidthOrderTest` pins both halves of that.
 *
 * The surrounding `Box` with a top-centre alignment is what actually
 * centres the capped column; without it the content caps but hugs the
 * leading edge.
 *
 * ### Custom caps
 *
 * Pass a tighter [max] for specific screens. Login / sign-up forms
 * typically cap around 480dp so they don't feel sparse.
 *
 * ### Accessibility
 *
 * Capping width also improves readability for users with low vision or
 * cognitive differences — shorter line lengths reduce the chance of
 * losing one's place.
 *
 * @param max   cap applied on expanded screens. Defaults to
 *              [AppContentMaxWidth].
 */
@Composable
fun Modifier.appContentWidth(max: Dp = AppContentMaxWidth): Modifier {
    val sizeClass = LocalWindowWidthSizeClass.current
    // ⚠ `>=`, not `==` — the cap must also apply at Large and ExtraLarge,
    // which are the widest windows and so the ones that need it most.
    // See the "Compare with `>=`" note on `WindowWidthSizeClass`.
    return if (sizeClass >= WindowWidthSizeClass.Expanded) {
        this.widthIn(max = max)
    } else {
        this
    }
}

@Composable
private fun ContentWidthDemo(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(MaterialTheme.spacing.m),
            style = MaterialTheme.typography.labelMedium,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .appContentWidth()
                .height(120.dp)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Text(
                text = "Content area",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(MaterialTheme.spacing.m),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Preview
@Composable
private fun AppContentWidthCompactPreview() = PreviewBox(
    windowWidthSizeClass = WindowWidthSizeClass.Compact,
) {
    ContentWidthDemo("Compact — no cap, fills width")
}

@Preview
@Composable
private fun AppContentWidthMediumPreview() = PreviewBox(
    windowWidthSizeClass = WindowWidthSizeClass.Medium,
) {
    ContentWidthDemo("Medium — no cap, fills width")
}

@Preview
@Composable
private fun AppContentWidthExpandedPreview() = PreviewBox(
    windowWidthSizeClass = WindowWidthSizeClass.Expanded,
) {
    ContentWidthDemo("Expanded — capped at 840dp")
}
