package app.tenshu.fit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.ChartMath

/**
 * A goal-progress ring (fit-agent.md §1.2) — a thin Canvas renderer over
 * [ChartMath.ringSweep]; the [content] slot is stacked as a centred column at the ring's
 * middle (value above, unit/label below). Sweep starts at 12 o'clock and fills clockwise
 * with a rounded cap.
 */
@Composable
fun Ring(
    value: Int,
    goal: Int,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 196.dp,
    // 12.dp, not 18: at 18 a full ring was the heaviest mass on Home and read as a hard,
    // dark block (fit-app.md §18.17). Onboarding's 140.dp ring was already 12.
    stroke: Dp = 12.dp,
    content: @Composable () -> Unit = {},
) {
    val sweep = ChartMath.ringSweep(value, goal)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx),
            )
            if (sweep > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}
