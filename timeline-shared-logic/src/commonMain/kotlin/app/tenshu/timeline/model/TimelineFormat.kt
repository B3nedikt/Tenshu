package app.tenshu.timeline.model

import org.tenshu.preference.MeasurementSystem

/**
 * Locale-independent duration structuring (Ph1, docs/proposals/timeline-app.md §5).
 *
 * Deliberately produces **no user-facing strings** — those are localised in the UI layer
 * (`:timeline-shared-ui`) via `stringResource`, per ADR 0016 ("logic emits data, UI
 * localises") and AGENTS.md's string-authoring rule. `:timeline-shared-logic` is
 * Compose-free, so it can't reach `stringResource` anyway.
 *
 * This helper owns only the two locale-independent decisions: the hours/minutes split and
 * *which* form to render. The UI maps [DurationForm] → a `Res.string.timeline_duration_*`
 * template and fills the numbers; photo counts / "now" / "No photos" are likewise chosen
 * in the composable from the raw `Int` count the model already carries.
 */
object TimelineFormat {

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
     * Splits a path length into a presentational form + parts, mirroring [durationParts]: whole
     * metres below a kilometre, kilometres with one decimal above it. Negatives clamp to zero.
     *
     * ⚠ The decimal is emitted as **separate whole + tenths integers**, not a pre-formatted "2.4".
     * The separator is locale-dependent (`2.4 km` / `2,4 km`), so it belongs in the string template
     * where a translator can change it — same reason [DurationForm] exists instead of a formatted
     * duration string.
     */
    fun distanceParts(
        distanceM: Long,
        units: MeasurementSystem = MeasurementSystem.METRIC,
    ): DistanceParts {
        val m = distanceM.coerceAtLeast(0)
        if (units == MeasurementSystem.IMPERIAL) return imperialDistanceParts(m)
        if (m < 1000) return DistanceParts(kilometres = 0, tenths = 0, metres = m.toInt(), form = DistanceForm.Metres)
        // Round to the nearest tenth of a kilometre, carrying into the whole part (999.96 km → 1000.0).
        val totalTenths = (m + 50) / 100
        return DistanceParts(
            kilometres = (totalTenths / 10).toInt(),
            tenths = (totalTenths % 10).toInt(),
            metres = 0,
            form = DistanceForm.Kilometres,
        )
    }

    /**
     * The imperial twin, switching at **0.1 mi** rather than at a mile.
     *
     * ⚠ Not "switch at 1 mi" (the direct mirror of the metric rule): a mile is ~1.6× a kilometre,
     * so that would print "1,847 ft" for a walk the metric build renders as "1.8 km" — precision
     * nobody asked for on a number that is already an estimate. A tenth of a mile is ~528 ft, which
     * puts the changeover at the same *readability* point rather than the same magnitude.
     */
    private fun imperialDistanceParts(m: Long): DistanceParts {
        val feet = (m * 10_000 / 3_048)
        if (feet < FEET_PER_MILE / 10) {
            return DistanceParts(kilometres = 0, tenths = 0, metres = feet.toInt(), form = DistanceForm.Feet)
        }
        val totalTenths = (feet * 10 + FEET_PER_MILE / 2) / FEET_PER_MILE
        return DistanceParts(
            kilometres = (totalTenths / 10).toInt(),
            tenths = (totalTenths % 10).toInt(),
            metres = 0,
            form = DistanceForm.Miles,
        )
    }

    private const val FEET_PER_MILE = 5_280L
}

/** How a duration should read — the UI picks the matching localized template. */
enum class DurationForm { MinutesOnly, HoursOnly, HoursAndMinutes }

data class DurationParts(
    val hours: Int,
    val minutes: Int,
    val form: DurationForm,
)

/**
 * How a distance should read — the UI picks the matching localized template.
 *
 * Four forms, two per system: the small unit below the changeover and the large one above it.
 */
enum class DistanceForm { Metres, Kilometres, Feet, Miles }

/**
 * ⚠ The field names stay metric-flavoured while carrying either system's numbers: [kilometres] +
 * [tenths] hold miles + tenths under [DistanceForm.Miles], and [metres] holds feet under
 * [DistanceForm.Feet]. Deliberate — the alternative is four near-identical fields of which two are
 * always zero, and [form] already tells the caller which template to read them into.
 */
data class DistanceParts(
    val kilometres: Int,
    val tenths: Int,
    val metres: Int,
    val form: DistanceForm,
)
