package app.tenshu.fit.ui.components

import androidx.compose.runtime.Composable
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_date_long
import app.tenshu.fit.shared.resources.fit_date_medium
import app.tenshu.fit.shared.resources.fit_date_year
import app.tenshu.fit.shared.resources.fit_day_today
import app.tenshu.fit.shared.resources.fit_day_yesterday
import app.tenshu.fit.shared.resources.fit_month_apr
import app.tenshu.fit.shared.resources.fit_month_aug
import app.tenshu.fit.shared.resources.fit_month_dec
import app.tenshu.fit.shared.resources.fit_month_feb
import app.tenshu.fit.shared.resources.fit_month_jan
import app.tenshu.fit.shared.resources.fit_month_jul
import app.tenshu.fit.shared.resources.fit_month_jun
import app.tenshu.fit.shared.resources.fit_month_mar
import app.tenshu.fit.shared.resources.fit_month_may
import app.tenshu.fit.shared.resources.fit_month_nov
import app.tenshu.fit.shared.resources.fit_month_oct
import app.tenshu.fit.shared.resources.fit_month_sep
import app.tenshu.fit.shared.resources.fit_updated_days_ago
import app.tenshu.fit.shared.resources.fit_weekday_fri
import app.tenshu.fit.shared.resources.fit_weekday_mon
import app.tenshu.fit.shared.resources.fit_weekday_sat
import app.tenshu.fit.shared.resources.fit_weekday_sun
import app.tenshu.fit.shared.resources.fit_weekday_thu
import app.tenshu.fit.shared.resources.fit_weekday_tue
import app.tenshu.fit.shared.resources.fit_weekday_wed
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import org.jetbrains.compose.resources.stringResource

/**
 * Localized calendar formatting for Fit (Ph4). Short weekday / month names come from
 * `strings.xml` (no `java.time` locale dependency — this compiles on every target). The
 * `daysAgo` relative labels key off the dataset's today ([app.tenshu.fit.data.FitRepository.today])
 * carried on each VM state, never the system clock.
 */

@Composable
private fun monthShort(month: Month): String = stringResource(
    when (month) {
        Month.JANUARY -> Res.string.fit_month_jan
        Month.FEBRUARY -> Res.string.fit_month_feb
        Month.MARCH -> Res.string.fit_month_mar
        Month.APRIL -> Res.string.fit_month_apr
        Month.MAY -> Res.string.fit_month_may
        Month.JUNE -> Res.string.fit_month_jun
        Month.JULY -> Res.string.fit_month_jul
        Month.AUGUST -> Res.string.fit_month_aug
        Month.SEPTEMBER -> Res.string.fit_month_sep
        Month.OCTOBER -> Res.string.fit_month_oct
        Month.NOVEMBER -> Res.string.fit_month_nov
        Month.DECEMBER -> Res.string.fit_month_dec
        else -> Res.string.fit_month_jan
    },
)

@Composable
fun weekdayShort(day: DayOfWeek): String = stringResource(
    when (day) {
        DayOfWeek.MONDAY -> Res.string.fit_weekday_mon
        DayOfWeek.TUESDAY -> Res.string.fit_weekday_tue
        DayOfWeek.WEDNESDAY -> Res.string.fit_weekday_wed
        DayOfWeek.THURSDAY -> Res.string.fit_weekday_thu
        DayOfWeek.FRIDAY -> Res.string.fit_weekday_fri
        DayOfWeek.SATURDAY -> Res.string.fit_weekday_sat
        DayOfWeek.SUNDAY -> Res.string.fit_weekday_sun
        else -> Res.string.fit_weekday_mon
    },
)

/** The single-letter trend-axis tick. */
@Composable
fun weekdayInitial(day: DayOfWeek): String = weekdayShort(day).take(1)

/** "Mon, May 24". */
@Composable
fun dateLong(date: LocalDate): String =
    stringResource(Res.string.fit_date_long, weekdayShort(date.dayOfWeek), monthShort(date.month), date.day)

/** "May 24". */
@Composable
fun dateMedium(date: LocalDate): String =
    stringResource(Res.string.fit_date_medium, monthShort(date.month), date.day)

/** "May 24, 1992". */
@Composable
fun dateWithYear(date: LocalDate): String =
    stringResource(Res.string.fit_date_year, monthShort(date.month), date.day, date.year)

/** "HH:mm" (24-hour). */
fun clock(time: LocalDateTime): String {
    val h = if (time.hour < 10) "0${time.hour}" else time.hour.toString()
    val m = if (time.minute < 10) "0${time.minute}" else time.minute.toString()
    return "$h:$m"
}

/** Today / Yesterday / "N days ago" / the medium date for older days. */
@Composable
fun dayLabel(daysAgo: Int, date: LocalDate): String = when {
    daysAgo <= 0 -> stringResource(Res.string.fit_day_today)
    daysAgo == 1 -> stringResource(Res.string.fit_day_yesterday)
    daysAgo < 7 -> stringResource(Res.string.fit_updated_days_ago, daysAgo)
    else -> dateMedium(date)
}
