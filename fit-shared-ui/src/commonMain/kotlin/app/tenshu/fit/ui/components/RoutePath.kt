package app.tenshu.fit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import app.tenshu.fit.model.ChartMath
import app.tenshu.fit.model.RoutePoint

/**
 * The abstract route (fit-agent.md §0.5 / §1.5) — a polyline on a neutral gridded surface,
 * **never a map**. Coordinates are already 0..1-normalized; [ChartMath.fitRoute] re-centres
 * and scales them into the frame. A hollow ring marks the start, a filled dot the end.
 */
@Composable
fun RoutePath(
    route: List<RoutePoint>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
) {
    if (route.size < 2) return
    val points = ChartMath.fitRoute(route)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh

    Surface(
        modifier = modifier.fillMaxWidth().height(height),
        shape = MaterialTheme.shapes.medium,
        color = surface,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                drawLine(grid.copy(alpha = 0.4f), Offset(0f, h * f), Offset(w, h * f), 0.75.dp.toPx())
                drawLine(grid.copy(alpha = 0.4f), Offset(w * f, 0f), Offset(w * f, h), 0.75.dp.toPx())
            }
            fun px(x: Float) = x * w
            fun py(y: Float) = y * h
            val path = Path().apply {
                points.forEachIndexed { i, p ->
                    if (i == 0) moveTo(px(p.x), py(p.y)) else lineTo(px(p.x), py(p.y))
                }
            }
            drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val start = points.first()
            val end = points.last()
            drawCircle(surface, radius = 4.5.dp.toPx(), center = Offset(px(start.x), py(start.y)))
            drawCircle(color, radius = 4.5.dp.toPx(), center = Offset(px(start.x), py(start.y)), style = Stroke(width = 2.5.dp.toPx()))
            drawCircle(color, radius = 5.dp.toPx(), center = Offset(px(end.x), py(end.y)))
        }
    }
}
