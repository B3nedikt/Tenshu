package app.tenshu.fit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tenshu.fit.model.ChartMath
import kotlinx.datetime.LocalDate
import kotlin.math.abs

/** One trend bar: its [date] (for the axis initial) and raw [value]. */
data class TrendBar(val date: LocalDate, val value: Int)

private val BarGap = 6.dp
private val CalloutGap = 6.dp

/**
 * The 7-day (or windowed) trend chart (fit-agent.md §1.2/§1.7): normalized bars with a
 * dashed goal line and single-letter weekday ticks. Today (the last bar) is accented;
 * the rest are a pale tint of the same [accent], so the chart reads as one colour with today
 * emphasised rather than grey beside purple (fit-app.md §18.17). Heights come from
 * [ChartMath.barHeights].
 *
 * Touching a bar inspects it ("scrubbing", as in Google Fit and Apple Health): while the
 * finger is down a callout above the plot shows that bar's value ([valueLabel]) and date,
 * joined to it by a dashed guide line; sliding sideways moves it across bars, and lifting
 * the finger hides it. A vertical drag is left to the enclosing scroll.
 */
@Composable
fun TrendBars(
    bars: List<TrendBar>,
    goal: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 96.dp,
    valueLabel: @Composable (Int) -> String = { groupThousands(it) },
) {
    if (bars.isEmpty()) return
    val values = bars.map { it.value }
    val heights = ChartMath.barHeights(values, goal)
    val goalFraction = ChartMath.goalLineFraction(values, goal)
    // A translucent accent, not an opaque role: it tints whatever card the chart sits on.
    val neutral = accent.copy(alpha = 0.24f)
    val outline = MaterialTheme.colorScheme.outline
    val guide = MaterialTheme.colorScheme.onSurfaceVariant
    val haptics = LocalHapticFeedback.current

    // ⚠ Not keyed on `bars`: the gesture below runs in `pointerInput(Unit)`, which keeps the
    // first composition's closure, so a re-keyed state object would orphan it and scrubbing
    // would silently stop after the data changes. Selection clears on lift anyway, and a
    // shrunken list is guarded by `sel in bars.indices`.
    var selected by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    // The bar Row mirrors in RTL (bar 0 on the right); the hit-test, guide line and pill must too.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Narrower gaps for dense ranges (Year = 365 bars), so every column keeps a width.
        val gapPx = ChartMath.fittedGap(constraints.maxWidth.toFloat(), bars.size, with(density) { BarGap.toPx() })
        val gapDp = with(density) { gapPx.toDp() }
        val count by rememberUpdatedState(bars.size)
        val gap by rememberUpdatedState(gapPx)
        val isRtl by rememberUpdatedState(rtl)
        fun select(i: Int?, haptic: Boolean) {
            // A tick per bar is useful across a week; across a month or a year it is a buzz.
            if (haptic && i != null && i != selected && count <= 31) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            selected = i
        }
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(plotHeight)) {
                // Scrubbing: the callout follows the finger and goes when it lifts. A drag that
                // turns out vertical hands the gesture back to the page's scroll.
                val touch = Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        fun at(x: Float) = ChartMath.barIndexAt(x, size.width.toFloat(), count, gap, isRtl)
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // No haptic yet: this touch may still turn out to be a page scroll.
                        select(at(down.position.x), haptic = false)
                        var scrubbing = false
                        var travel = Offset.Zero
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (!scrubbing) {
                                if (change.isConsumed) break // the scroll container claimed it
                                travel += change.positionChange()
                                val slop = viewConfiguration.touchSlop
                                if (abs(travel.y) > slop && abs(travel.y) > abs(travel.x)) break
                                if (abs(travel.x) > slop) scrubbing = true
                            }
                            if (scrubbing) {
                                change.consume()
                                select(at(change.position.x), haptic = true)
                            }
                        }
                        selected = null
                    }
                }

                Box(Modifier.fillMaxSize().then(touch)) {
                    if (goal > 0) {
                        Canvas(Modifier.fillMaxSize()) {
                            val y = size.height * (1f - goalFraction)
                            drawLine(
                                color = outline.copy(alpha = 0.6f),
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(gapDp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        heights.forEachIndexed { i, frac ->
                            // A selection takes the accent from today, so one bar is lit at a time.
                            val lit = selected?.let { it == i } ?: (i == heights.lastIndex)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                                Box(
                                    Modifier
                                        .widthIn(max = 20.dp)
                                        .fillMaxWidth(0.72f)
                                        .fillMaxHeight(frac.coerceAtLeast(0.03f))
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (lit) accent else neutral),
                                )
                            }
                        }
                    }

                    val sel = selected
                    if (sel != null && sel in bars.indices) {
                        val frac = heights[sel].coerceAtLeast(0.03f)
                        // Guide line from the callout (above the plot) down to the bar's top.
                        Canvas(Modifier.fillMaxSize()) {
                            val x = ChartMath.barCenterX(sel, size.width, bars.size, gapPx, rtl)
                            val top = size.height * (1f - frac)
                            drawLine(
                                color = guide.copy(alpha = 0.7f),
                                start = Offset(x, -CalloutGap.toPx()),
                                end = Offset(x, top - 3.dp.toPx()),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                            )
                        }
                        BarCallout(
                            index = sel,
                            count = bars.size,
                            gapPx = gapPx,
                            value = valueLabel(bars[sel].value),
                            date = dateLong(bars[sel].date),
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(20.dp),
                horizontalArrangement = Arrangement.spacedBy(gapDp),
            ) {
                bars.forEachIndexed { i, bar ->
                    val lit = selected?.let { it == i } ?: (i == bars.lastIndex)
                    Text(
                        text = weekdayInitial(bar.date.dayOfWeek),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (lit) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * The selected bar's "value · date" pill. Sits just above the plot (over the card header,
 * like Google Fit's), centred on the bar and clamped so it never leaves the plot's width.
 */
@Composable
private fun BarCallout(index: Int, count: Int, gapPx: Float, value: String, date: String) {
    val colors = MaterialTheme.colorScheme
    Layout(
        content = {
            Surface(
                shape = RoundedCornerShape(50),
                color = colors.inverseSurface,
                shadowElevation = 3.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.inversePrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.inverseOnSurface,
                        maxLines = 1,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val pill = measurables.single().measure(Constraints(maxWidth = constraints.maxWidth))
        val width = constraints.maxWidth
        val centre = ChartMath.barCenterX(index, width.toFloat(), count, gapPx, layoutDirection == LayoutDirection.Rtl)
        val x = (centre - pill.width / 2f).toInt().coerceIn(0, (width - pill.width).coerceAtLeast(0))
        val y = -pill.height - CalloutGap.roundToPx()
        layout(width, constraints.maxHeight) { pill.place(x, y) }
    }
}
