package app.tenshu.fit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.NormalizedPoint

/**
 * A min–max-scaled trend line for a Body/Vitals sample series (§12 B2) — a filled area under the
 * polyline plus a dot on each reading. Geometry is pre-computed by
 * [app.tenshu.fit.model.ChartMath.minMaxPolyline] (y = 0 at the top, Canvas convention), so this is
 * a pure renderer. Distinct from [TrendBars]: a weight trend needs a value-ranged axis, not the
 * zero-anchored bars that would render 73–75 kg as near-identical full columns.
 */
@Composable
fun SampleTrend(
    points: List<NormalizedPoint>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
) {
    if (points.size < 2) return
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        // Inset vertically so the top/bottom dots aren't clipped at the plot edge.
        val inset = 4.dp.toPx()
        fun px(x: Float) = x * w
        fun py(y: Float) = inset + y * (h - 2 * inset)

        val line = Path().apply {
            points.forEachIndexed { i, p ->
                if (i == 0) moveTo(px(p.x), py(p.y)) else lineTo(px(p.x), py(p.y))
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                0f to color.copy(alpha = 0.20f),
                1f to color.copy(alpha = 0f),
            ),
        )
        drawPath(
            path = line,
            color = color,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        points.forEach { p ->
            drawCircle(color = color, radius = 2.6.dp.toPx(), center = Offset(px(p.x), py(p.y)))
        }
    }
}
