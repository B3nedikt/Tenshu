package app.tenshu.calendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Minutes since midnight, ticking.
 *
 * ⚠⚠ **The only ticking clock in Calendar, and it is deliberately in the UI
 * layer.** Everything beneath takes its "now" as a parameter so it can be
 * tested without one (`CalendarViewModel`'s `today`); this is not a decision,
 * it is an *animation* — the current-time line has to creep down the grid, and
 * a value that never changed would freeze it wherever the screen opened.
 *
 * ⚠ Wakes once a minute, not once a second: the line moves less than a pixel a
 * second at any sane hour height, so a faster tick would be recomposition for
 * nothing.
 */
@Composable
fun rememberCurrentMinute(): State<Int> = produceState(initialValue = currentMinute()) {
    while (true) {
        value = currentMinute()
        delay(TICK_MILLIS)
    }
}

private fun currentMinute(): Int {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return now.hour * 60 + now.minute
}

private const val TICK_MILLIS = 60_000L
