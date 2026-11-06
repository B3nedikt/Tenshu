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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.ChartMath
import app.tenshu.fit.model.HrPoint

/**
 * Heart-rate-over-time curve (fit-agent.md §1.5): a filled area under a smooth polyline,
 * plus a dashed average guide line. Geometry from [ChartMath.hrPolyline] /
 * [ChartMath.hrAverageY] — y follows the Canvas convention (0 = top).
 */
@Composable
fun HrChart(
    series: List<HrPoint>,
    avgBpm: Int,
    color: Color,
    modifier: Modifier = Modifier,
    guideColor: Color,
    height: Dp = 120.dp,
) {
    if (series.size < 2) return
    val points = ChartMath.hrPolyline(series)
    val avgY = ChartMath.hrAverageY(series, avgBpm)

    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        fun px(x: Float) = x * w
        fun py(y: Float) = y * h

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
                0f to color.copy(alpha = 0.22f),
                1f to color.copy(alpha = 0f),
            ),
        )
        drawLine(
            color = guideColor,
            start = Offset(0f, py(avgY)),
            end = Offset(w, py(avgY)),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)),
        )
        drawPath(
            path = line,
            color = color,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
