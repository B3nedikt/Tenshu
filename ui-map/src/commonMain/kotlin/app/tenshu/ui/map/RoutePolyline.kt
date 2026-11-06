package app.tenshu.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import app.tenshu.basemap.normalizeRoute

/**
 * A route polyline overlaid on the basemap (`maps-region-delivery.md` R6 — Timeline trip routes;
 * Maps map-of-all-places). Pass it inside [OfflineBasemap]'s content slot.
 *
 * **v1 draws a normalized *sketch*** of the route within the surface: the offline schematic basemap
 * is not geographically precise, so the polyline is fitted to the surface (via [normalizeRoute])
 * rather than projected onto real tiles. A geographically-anchored MapLibre line layer over a
 * downloaded region is the follow-up (needs the live line-layer API + a resolved region). The
 * shape, endpoints, and ordering of the route read correctly today; the underlay is schematic.
 */
@Composable
fun BoxScope.RoutePolyline(
    route: List<BasemapCoord>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 3.dp,
    inset: Dp = 20.dp,
) {
    if (route.size < 2) return
    val normalized = normalizeRoute(route)
    Canvas(modifier.matchParentSize().padding(inset)) {
        if (size.minDimension <= 0f) return@Canvas
        val points = normalized.map { (x, y) -> Offset((x * size.width).toFloat(), (y * size.height).toFloat()) }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Endpoint dots so start/end read clearly.
        val dot = strokeWidth.toPx() * 1.6f
        drawCircle(color, radius = dot, center = points.first())
        drawCircle(color, radius = dot, center = points.last())
    }
}
