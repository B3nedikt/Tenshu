package app.tenshu.ui.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.theme.spacing
import androidx.compose.ui.tooling.preview.Preview

/**
 * Standardised sizes for [AppLoadingIndicator].
 *
 * - [Inline] — fits inside a row or list-item trailing slot (20dp).
 * - [Medium] — section-level or in-screen loading (32dp).
 * - [Large]  — splash / full-screen loading (48dp).
 *
 * These three cover every current need across Drive and Control. Add a
 * new size only after it has two real consumers.
 */
enum class AppLoadingSize(internal val diameter: Dp, internal val stroke: Dp) {
    Inline(20.dp, 2.dp),
    Medium(32.dp, 3.dp),
    Large(48.dp, 4.dp),
}

/**
 * App-styled indeterminate progress indicator wrapping Material 3
 * [CircularProgressIndicator].
 *
 * ### When to use
 *
 * For work the user initiated that has no known duration — a network
 * call, a file import, a sync handshake. For work with known progress
 * (uploads, big copies) use a determinate progress indicator instead.
 *
 * ### Variants
 *
 * Pick [size] by the slot the indicator sits in:
 *
 * - **Inline** — inside a row, list-item trailing area, or primary
 *   button; matches text-button sizing.
 * - **Medium** — below a section header or as the "loading…" payload of
 *   a pager footer.
 * - **Large** — on a splash screen or full-screen "waiting" surface.
 *
 * Pass [color] when painting on coloured backgrounds — e.g.,
 * `MaterialTheme.colorScheme.onPrimary` when the indicator sits inside a
 * filled primary button.
 *
 * ### Accessibility
 *
 * The indicator has no accessible name by default; if it is the only
 * affordance on a screen, the host should announce the action via
 * `Modifier.semantics { liveRegion = … }` on the surrounding container.
 *
 * @param size          one of [AppLoadingSize.Inline] / [AppLoadingSize.Medium] /
 *                      [AppLoadingSize.Large].
 * @param color         arc colour.
 * @param trackColor    track colour; defaults to `surfaceContainerHighest`
 *                      so the indicator reads as part of the surface
 *                      rather than floating on top of it.
 */
@Composable
fun AppLoadingIndicator(
    modifier: Modifier = Modifier,
    size: AppLoadingSize = AppLoadingSize.Medium,
    color: Color = ProgressIndicatorDefaults.circularColor,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size.diameter),
        color = color,
        strokeWidth = size.stroke,
        trackColor = trackColor
    )
}

@Preview
@Composable
private fun AppLoadingIndicatorSizesPreview() = PreviewBox {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppLoadingIndicator(size = AppLoadingSize.Inline)
            Text("Inline", style = MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppLoadingIndicator(size = AppLoadingSize.Medium)
            Text("Medium", style = MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppLoadingIndicator(size = AppLoadingSize.Large)
            Text("Large", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Preview
@Composable
private fun AppLoadingIndicatorInsideButtonPreview() = PreviewBox {
    Button(onClick = {}) {
        AppLoadingIndicator(
            size = AppLoadingSize.Inline,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Preview
@Composable
private fun AppLoadingIndicatorDarkPreview() = PreviewBox(useDarkTheme = true) {
    AppLoadingIndicator(size = AppLoadingSize.Medium)
}
