// marks/Outline.kt — Tenshu cat mark, primary (outline). Compose Multiplatform.
// Use this wherever it reads cleanly. See ../brand-agent.md for usage rules.
//
// Inherits color via the supplied tint (defaults to LocalContentColor) so it
// respects the surrounding text style. The "surface" parameter fills the head
// (so whiskers visually tuck under at the rim); pass MaterialTheme.colorScheme.surface.
//
// CODIFIED from docs/design/brand/marks/Outline.kt per brand-agent.md
// "How to codify → KMP → Marks" (copy verbatim). Do not edit values here;
// round-trip through the brand folder.

package app.tenshu.ui.components.marks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MarkOutline(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    stroke: Float = 2.5f,
    tint: Color = LocalContentColor.current,
    surface: Color = MaterialTheme.colorScheme.surface,
) {
    Canvas(modifier = modifier.size(size)) {
        // Source units are 64x64. Scale to the actual draw size.
        val s = size.toPx() / 64f
        val sw = stroke * s

        val head = Path().apply {
            // M 14 50 A 18 22 0 0 1 50 50 Z
            moveTo(14f * s, 50f * s)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    left = (32f - 18f) * s,
                    top = (50f - 22f) * s,
                    right = (32f + 18f) * s,
                    bottom = (50f + 22f) * s,
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            close()
        }
        drawPath(head, color = surface)
        drawPath(
            head,
            color = tint,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Triangle "pagoda-eave" ears, reinstated in v7 (see ADR 0004).
        // EAR_L = "M 28.8 24.2 L 18 14.1 L 15.1 31.7 C 15.1 33.9 18.4 25.9 28.8 24.2 Z"
        // EAR_R = "M 35.1 23.9 C 38.9 20.3 42.6 17.1 45.8 13.8 L 48.7 31.4 C 48.7 33.6 45.4 25.5 35.1 23.9 Z"
        val ears = Path().apply {
            moveTo(28.8f * s, 24.2f * s)
            lineTo(18f * s, 14.1f * s)
            lineTo(15.1f * s, 31.7f * s)
            cubicTo(15.1f * s, 33.9f * s, 18.4f * s, 25.9f * s, 28.8f * s, 24.2f * s)
            close()
            moveTo(35.1f * s, 23.9f * s)
            cubicTo(38.9f * s, 20.3f * s, 42.6f * s, 17.1f * s, 45.8f * s, 13.8f * s)
            lineTo(48.7f * s, 31.4f * s)
            cubicTo(48.7f * s, 33.6f * s, 45.4f * s, 25.5f * s, 35.1f * s, 23.9f * s)
            close()
        }
        drawPath(ears, color = surface)
        drawPath(
            ears,
            color = tint,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        fun curve(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float): Path =
            Path().apply {
                moveTo(x1 * s, y1 * s)
                quadraticTo(cx * s, cy * s, x2 * s, y2 * s)
            }

        val whiskerStroke = Stroke(
            width = sw * 0.55f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        // Left whiskers
        drawPath(curve(20f, 39f, 13f, 37f, 4f, 35f), tint, style = whiskerStroke)
        drawPath(curve(20f, 41f, 12f, 41f, 3f, 41f), tint, style = whiskerStroke)
        drawPath(curve(20f, 43f, 13f, 45f, 4f, 48f), tint, style = whiskerStroke)
        // Right whiskers
        drawPath(curve(44f, 39f, 51f, 37f, 60f, 35f), tint, style = whiskerStroke)
        drawPath(curve(44f, 41f, 52f, 41f, 61f, 41f), tint, style = whiskerStroke)
        drawPath(curve(44f, 43f, 51f, 45f, 60f, 48f), tint, style = whiskerStroke)

        // Mouth: M 27 43 Q 29 46 31 44 Q 32 43 33 44 Q 35 46 37 43
        val mouth = Path().apply {
            moveTo(27f * s, 43f * s)
            quadraticTo(29f * s, 46f * s, 31f * s, 44f * s)
            quadraticTo(32f * s, 43f * s, 33f * s, 44f * s)
            quadraticTo(35f * s, 46f * s, 37f * s, 43f * s)
        }
        drawPath(
            mouth,
            color = tint,
            style = Stroke(width = sw * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Eyes
        drawCircle(tint, radius = 2.1f * s, center = Offset(26.5f * s, 36f * s))
        drawCircle(tint, radius = 2.1f * s, center = Offset(37.5f * s, 36f * s))
        // Catchlights
        drawCircle(surface, radius = 0.55f * s, center = Offset(27.2f * s, 35.3f * s))
        drawCircle(surface, radius = 0.55f * s, center = Offset(38.2f * s, 35.3f * s))
    }
}
