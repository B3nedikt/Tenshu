package app.tenshu.maps.ui.text

import androidx.compose.runtime.Composable
import app.tenshu.maps.model.MapsFormat
import app.tenshu.maps.model.RelativeForm
import app.tenshu.maps.shared.resources.Res
import app.tenshu.maps.shared.resources.maps_visit_days_ago
import app.tenshu.maps.shared.resources.maps_visit_months_ago_one
import app.tenshu.maps.shared.resources.maps_visit_months_ago_other
import app.tenshu.maps.shared.resources.maps_visit_never
import app.tenshu.maps.shared.resources.maps_visit_today
import app.tenshu.maps.shared.resources.maps_visit_weeks_ago_one
import app.tenshu.maps.shared.resources.maps_visit_weeks_ago_other
import app.tenshu.maps.shared.resources.maps_visit_years_ago_one
import app.tenshu.maps.shared.resources.maps_visit_years_ago_other
import app.tenshu.maps.shared.resources.maps_visit_yesterday
import app.tenshu.maps.shared.resources.maps_date_medium
import app.tenshu.maps.shared.resources.maps_month_1
import app.tenshu.maps.shared.resources.maps_month_10
import app.tenshu.maps.shared.resources.maps_month_11
import app.tenshu.maps.shared.resources.maps_month_12
import app.tenshu.maps.shared.resources.maps_month_2
import app.tenshu.maps.shared.resources.maps_month_3
import app.tenshu.maps.shared.resources.maps_month_4
import app.tenshu.maps.shared.resources.maps_month_5
import app.tenshu.maps.shared.resources.maps_month_6
import app.tenshu.maps.shared.resources.maps_month_7
import app.tenshu.maps.shared.resources.maps_month_8
import app.tenshu.maps.shared.resources.maps_month_9
import app.tenshu.maps.shared.resources.maps_visits_none
import app.tenshu.maps.shared.resources.maps_visits_one
import app.tenshu.maps.shared.resources.maps_visits_other
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The UI half of Maps' localisation split (docs/proposals/maps-app.md §6 / ADR 0016): the
 * logic layer emits data (`MapsFormat.RelativeForm` + raw `Int` counts), and these
 * `@Composable` helpers render the localised strings via `stringResource`. Plurals ride as
 * two keys (n=1 shape) until the ADR 0016 n=2 ICU catalogue lands.
 */

/** "Today" / "3 days ago" / … from a visit date, or "Not visited yet" when null. */
@Composable
fun relativeVisitText(today: LocalDate, visit: LocalDate?): String {
    if (visit == null) return stringResource(Res.string.maps_visit_never)
    val rel = MapsFormat.relativeVisit(today, visit)
    return when (rel.form) {
        RelativeForm.Today -> stringResource(Res.string.maps_visit_today)
        RelativeForm.Yesterday -> stringResource(Res.string.maps_visit_yesterday)
        RelativeForm.DaysAgo -> stringResource(Res.string.maps_visit_days_ago, rel.value)
        RelativeForm.WeeksAgo ->
            if (rel.value == 1) stringResource(Res.string.maps_visit_weeks_ago_one, rel.value)
            else stringResource(Res.string.maps_visit_weeks_ago_other, rel.value)
        RelativeForm.MonthsAgo ->
            if (rel.value == 1) stringResource(Res.string.maps_visit_months_ago_one, rel.value)
            else stringResource(Res.string.maps_visit_months_ago_other, rel.value)
        RelativeForm.YearsAgo ->
            if (rel.value == 1) stringResource(Res.string.maps_visit_years_ago_one, rel.value)
            else stringResource(Res.string.maps_visit_years_ago_other, rel.value)
    }
}

/** "No visits yet" / "1 visit" / "23 visits". */
@Composable
fun visitCountText(count: Int): String = when {
    count <= 0 -> stringResource(Res.string.maps_visits_none)
    count == 1 -> stringResource(Res.string.maps_visits_one, count)
    else -> stringResource(Res.string.maps_visits_other, count)
}

private val monthNames: List<StringResource> = listOf(
    Res.string.maps_month_1, Res.string.maps_month_2, Res.string.maps_month_3,
    Res.string.maps_month_4, Res.string.maps_month_5, Res.string.maps_month_6,
    Res.string.maps_month_7, Res.string.maps_month_8, Res.string.maps_month_9,
    Res.string.maps_month_10, Res.string.maps_month_11, Res.string.maps_month_12,
)

/** A medium date — "September 1, 2023" (en). Month name localised; order is ICU at n=2. */
@Composable
fun mediumDateText(date: LocalDate): String {
    val month = stringResource(monthNames[date.month.number - 1])
    return stringResource(Res.string.maps_date_medium, month, date.day, date.year)
}
