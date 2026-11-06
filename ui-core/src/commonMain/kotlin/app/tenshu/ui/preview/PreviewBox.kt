package app.tenshu.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.ui.layout.LocalWindowWidthSizeClass
import app.tenshu.ui.layout.WindowWidthSizeClass
import app.tenshu.ui.theme.TenshuTheme

/**
 * Project-wide harness wrapper for `@Preview` composables.
 *
 * Every `@Preview` across the app modules (`ui-core`, `drive-shared`,
 * `control-shared`, and any future UI module) should use this. It is
 * NOT ui-core-local — it lives here because ui-core already owns the
 * theme and adaptive-layout primitives it depends on.
 *
 * Wraps [content] in [TenshuTheme] so the preview uses the real tokens
 * (colors, typography, shapes, spacing), and optionally overrides the
 * window-size-class composition local so previews can exercise
 * adaptive behaviour without actually resizing the IDE panel.
 *
 * NOT for use inside UI tests — `runComposeUiTest` bodies wrap in
 * [TenshuTheme] directly (see `docs/testing.md` §1).
 *
 * Standard usage — one `@Preview` per variant:
 *
 * ```
 * @Preview
 * @Composable
 * private fun AppFooPreviewLight() = PreviewBox { AppFoo(...) }
 *
 * @Preview
 * @Composable
 * private fun AppFooPreviewDark() = PreviewBox(useDarkTheme = true) { AppFoo(...) }
 *
 * @Preview
 * @Composable
 * private fun AppFooPreviewExpanded() = PreviewBox(
 *     windowWidthSizeClass = WindowWidthSizeClass.Expanded,
 * ) { AppFoo(...) }
 * ```
 *
 * The preview frame is painted with `colorScheme.background` so dark
 * and light variants are visibly distinct in the preview panel, and
 * padded so the content is not flush with the frame edge. Pass
 * `padding = PaddingValues(0.dp)` for components whose own layout
 * dictates the outer margin (e.g., full-screen empty states).
 *
 * @param useDarkTheme          render in the dark color scheme.
 * @param windowWidthSizeClass  value for [LocalWindowWidthSizeClass].
 *                              Defaults to [WindowWidthSizeClass.Compact]
 *                              to match what an unprovided preview
 *                              would see at runtime.
 * @param padding               outer padding around [content].
 */
@Composable
fun PreviewBox(
    useDarkTheme: Boolean = false,
    windowWidthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    TenshuTheme(useDarkTheme = useDarkTheme) {
        CompositionLocalProvider(
            LocalWindowWidthSizeClass provides windowWidthSizeClass,
        ) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding),
            ) {
                content()
            }
        }
    }
}
