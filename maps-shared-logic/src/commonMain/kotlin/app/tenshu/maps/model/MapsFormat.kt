package app.tenshu.maps.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.periodUntil

/**
 * Locale-independent "last visit" structuring (Ph1, docs/proposals/maps-app.md §6).
 *
 * Like `TimelineFormat`, this produces **no user-facing strings** — those are localised in
 * the UI layer (`:maps-shared-ui`) via `stringResource`, per ADR 0016 ("logic emits data,
 * UI localises") and AGENTS.md's string rule. `:maps-shared-logic` is Compose-free, so it
 * can't reach `stringResource` anyway.
 *
 * The visit-history card on the place detail (`maps-agent.md` §1.2) shows "last visit N
 * days/weeks/months ago". This helper owns only the two locale-independent decisions: the
 * bucket ([RelativeForm]) and its magnitude. The UI maps the form → a
 * `Res.string.maps_last_visit_*` template and fills the number (tabular). A null
 * `lastVisit` is "never visited" — a UI state, not a form here, so callers guard it.
 */
object MapsFormat {

    /**
     * Buckets how long before [today] a [visit] happened. Future/zero deltas clamp to
     * [RelativeForm.Today]; magnitudes are floored (6 days → "6 days", 13 days → "1 week").
     */
    fun relativeVisit(today: LocalDate, visit: LocalDate): RelativeVisit {
        val days = visit.daysUntil(today)
        return when {
            days <= 0 -> RelativeVisit(RelativeForm.Today, 0)
            days == 1 -> RelativeVisit(RelativeForm.Yesterday, 0)
            days < 7 -> RelativeVisit(RelativeForm.DaysAgo, days)
            days < 28 -> RelativeVisit(RelativeForm.WeeksAgo, days / 7)
            else -> {
                val period = visit.periodUntil(today)
                val months = period.years * 12 + period.months
                if (months < 12) {
                    RelativeVisit(RelativeForm.MonthsAgo, months.coerceAtLeast(1))
                } else {
                    RelativeVisit(RelativeForm.YearsAgo, period.years.coerceAtLeast(1))
                }
            }
        }
    }
}

/** How a "last visit" should read — the UI picks the matching localized template. */
enum class RelativeForm { Today, Yesterday, DaysAgo, WeeksAgo, MonthsAgo, YearsAgo }

/**
 * A structured relative time. [value] is the magnitude the UI renders (days/weeks/months/
 * years); it is `0` and unused for [RelativeForm.Today] / [RelativeForm.Yesterday].
 */
data class RelativeVisit(val form: RelativeForm, val value: Int)
