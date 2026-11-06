package app.tenshu.fit.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Locale-independent value structuring (Ph1, docs/proposals/fit-app.md §5).
 *
 * Deliberately produces **no user-facing strings** — those are localised in the UI
 * layer via `stringResource` (ADR 0016, "logic emits data, UI localises"); this module
 * is Compose-free. Units are presentational: storage is SI (`distanceM`, `durationSec`,
 * kJ), display is km / min:sec / kcal at the edge (fit-agent.md §4).
 */
object FitFormat {

    /** Splits a minute total into a presentational form + parts. Negatives clamp to zero. */
    fun durationParts(totalMinutes: Int): DurationParts {
        val m = totalMinutes.coerceAtLeast(0)
        val hours = m / 60
        val minutes = m % 60
        val form = when {
            hours == 0 -> DurationForm.MinutesOnly
            minutes == 0 -> DurationForm.HoursOnly
            else -> DurationForm.HoursAndMinutes
        }
        return DurationParts(hours = hours, minutes = minutes, form = form)
    }

    /**
     * Splits an elapsed-seconds total for clock-style rendering (record live / laps):
     * `MM:SS` under an hour, `H:MM:SS` from there.
     */
    fun clockParts(totalSeconds: Int): ClockParts {
        val s = totalSeconds.coerceAtLeast(0)
        return ClockParts(
            hours = s / 3600,
            minutes = (s % 3600) / 60,
            seconds = s % 60,
            showHours = s >= 3600,
        )
    }

    /** Pace as min:sec per km. Non-positive input yields zero parts (renders "—" in UI). */
    fun paceParts(secPerKm: Int): PaceParts {
        val p = secPerKm.coerceAtLeast(0)
        return PaceParts(minutes = p / 60, seconds = p % 60)
    }

    /**
     * Distance display decision: metres under 1 km, otherwise kilometres with one
     * decimal (two significant digits stay honest for short runs).
     */
    fun distanceParts(distanceM: Int): DistanceParts {
        val d = distanceM.coerceAtLeast(0)
        return if (d < 1000) {
            DistanceParts(form = DistanceForm.Meters, meters = d, kmTenths = 0)
        } else {
            DistanceParts(form = DistanceForm.Kilometers, meters = d, kmTenths = (d / 100.0).roundToInt())
        }
    }

    /** Stored kilojoules → displayed kilocalories (fit-agent.md §4: energy shows as kcal). */
    fun kjToKcal(kj: Int): Int = (kj * 0.2390057).roundToInt()

    /**
     * Week-over-week delta in percent, rounded; `null` when there is no previous week
     * to compare against (UI omits the delta chip).
     */
    fun weekDeltaPercent(current: Int, previous: Int): Int? {
        if (previous <= 0) return null
        return ((current - previous) * 100.0 / previous).roundToInt()
    }

    /** Sub-km lap distances render as "0.x km"; whole laps as "N km". */
    fun lapKmTenths(distanceM: Int): Int = (abs(distanceM) / 100.0).roundToInt()
}

/** How a duration should read — the UI picks the matching localized template. */
enum class DurationForm { MinutesOnly, HoursOnly, HoursAndMinutes }

data class DurationParts(
    val hours: Int,
    val minutes: Int,
    val form: DurationForm,
)

data class ClockParts(
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    val showHours: Boolean,
)

data class PaceParts(
    val minutes: Int,
    val seconds: Int,
)

enum class DistanceForm { Meters, Kilometers }

/** [kmTenths] carries km × 10 so no floating formatting happens in logic. */
data class DistanceParts(
    val form: DistanceForm,
    val meters: Int,
    val kmTenths: Int,
)
