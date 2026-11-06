package app.tenshu.fit.ui.components

import androidx.compose.runtime.Composable
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.DistanceForm
import app.tenshu.fit.model.FitFormat
import app.tenshu.fit.model.Source
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_activity_cycling
import app.tenshu.fit.shared.resources.fit_activity_other
import app.tenshu.fit.shared.resources.fit_activity_running
import app.tenshu.fit.shared.resources.fit_activity_strength
import app.tenshu.fit.shared.resources.fit_activity_walking
import app.tenshu.fit.shared.resources.fit_dash
import app.tenshu.fit.shared.resources.fit_duration_hours
import app.tenshu.fit.shared.resources.fit_duration_hours_minutes
import app.tenshu.fit.shared.resources.fit_duration_minutes
import app.tenshu.fit.shared.resources.fit_imported_from_file
import app.tenshu.fit.shared.resources.fit_recorded_in_fit
import app.tenshu.fit.shared.resources.fit_source_apple_health
import app.tenshu.fit.shared.resources.fit_source_detected
import app.tenshu.fit.shared.resources.fit_source_file
import app.tenshu.fit.shared.resources.fit_source_health_connect
import app.tenshu.fit.shared.resources.fit_source_manual
import app.tenshu.fit.shared.resources.fit_source_recorded
import app.tenshu.fit.shared.resources.fit_unit_kcal
import app.tenshu.fit.shared.resources.fit_unit_km
import app.tenshu.fit.shared.resources.fit_unit_m
import app.tenshu.fit.shared.resources.fit_unit_per_km
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Presentational formatting for Fit (Ph4, docs/proposals/fit-app.md §5). The Compose-free
 * [FitFormat] carries the locale-independent *decisions* (duration form, pace parts,
 * m-vs-km, kJ→kcal); this layer picks the matching `strings.xml` template — "logic emits
 * data, UI localises" (ADR 0016). Every metric renders with tabular numerals
 * ([app.tenshu.fit.ui.theme.tabular]).
 */

/** A metric split into its numeric [value] and a short [unit] for a two-part stat cell. */
data class ValueUnit(val value: String, val unit: String?)

/** Locale-independent thousands grouping (mock uses en-US comma grouping). */
fun groupThousands(n: Int): String {
    val neg = n < 0
    val digits = kotlin.math.abs(n).toString()
    val sb = StringBuilder()
    for ((i, ch) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
        sb.append(ch)
    }
    return if (neg) "-$sb" else sb.toString()
}

/** Zero-pads to two digits without a width format specifier (CMP formatter is limited). */
private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

@Composable
fun activityName(type: ActivityType): String = stringResource(
    when (type) {
        ActivityType.Running -> Res.string.fit_activity_running
        ActivityType.Walking -> Res.string.fit_activity_walking
        ActivityType.Cycling -> Res.string.fit_activity_cycling
        ActivityType.Strength -> Res.string.fit_activity_strength
        ActivityType.Other -> Res.string.fit_activity_other
    },
)

@Composable
fun sourceLabel(source: Source): String = stringResource(
    when (source) {
        Source.Recorded -> Res.string.fit_source_recorded
        Source.HealthConnect -> Res.string.fit_source_health_connect
        Source.AppleHealth -> Res.string.fit_source_apple_health
        Source.File -> Res.string.fit_source_file
        Source.Manual -> Res.string.fit_source_manual
        Source.Detected -> Res.string.fit_source_detected
    },
)

/** Workout title, falling back to the activity name when the recorder left it blank. */
@Composable
fun workoutTitle(title: String, type: ActivityType): String =
    title.ifBlank { activityName(type) }

/**
 * Provenance line: the device when one is known, else where the workout came from.
 *
 * ⚠ A blank device is not "recorded here": a GPX/TCX import carries none either, and labelling it
 * "Recorded in Fit" claimed a recording that never happened.
 */
@Composable
fun deviceLabel(device: String, source: Source): String =
    device.ifBlank {
        when (source) {
            Source.Recorded -> stringResource(Res.string.fit_recorded_in_fit)
            Source.File -> stringResource(Res.string.fit_imported_from_file)
            else -> sourceLabel(source)
        }
    }

/** Long form: "45 min", "1 h", "1 h 12 min". */
@Composable
fun durationLong(totalSec: Int): String {
    // Round sub-minute recordings up to 1 min so a fresh recording never reads "0 min".
    val minutes = (totalSec + 59) / 60
    val parts = FitFormat.durationParts(minutes)
    return when (parts.form) {
        app.tenshu.fit.model.DurationForm.MinutesOnly ->
            stringResource(Res.string.fit_duration_minutes, parts.minutes)
        app.tenshu.fit.model.DurationForm.HoursOnly ->
            stringResource(Res.string.fit_duration_hours, parts.hours)
        app.tenshu.fit.model.DurationForm.HoursAndMinutes ->
            stringResource(Res.string.fit_duration_hours_minutes, parts.hours, parts.minutes)
    }
}

/** Clock form for elapsed/lap times: "5:56" or "1:05:56". */
fun durationClock(totalSec: Int): String {
    val p = FitFormat.clockParts(totalSec)
    return if (p.showHours) "${p.hours}:${pad2(p.minutes)}:${pad2(p.seconds)}"
    else "${p.minutes}:${pad2(p.seconds)}"
}

/** Distance as one string: "540 m" / "3.2 km". */
@Composable
fun distanceText(distanceM: Int?): String {
    if (distanceM == null) return stringResource(Res.string.fit_dash)
    val vu = distanceValueUnit(distanceM)
    return "${vu.value} ${vu.unit}"
}

/** Distance split for a stat cell. */
@Composable
fun distanceValueUnit(distanceM: Int): ValueUnit {
    val parts = FitFormat.distanceParts(distanceM)
    return when (parts.form) {
        DistanceForm.Meters -> ValueUnit(parts.meters.toString(), stringResource(Res.string.fit_unit_m))
        DistanceForm.Kilometers -> {
            val whole = parts.kmTenths / 10
            val tenth = parts.kmTenths % 10
            ValueUnit("$whole.$tenth", stringResource(Res.string.fit_unit_km))
        }
    }
}

/** Pace split: "5:56" + "/km"; a non-positive pace renders a dash with no unit. */
@Composable
fun paceValueUnit(secPerKm: Int?): ValueUnit {
    if (secPerKm == null || secPerKm <= 0) return ValueUnit(stringResource(Res.string.fit_dash), null)
    val p = FitFormat.paceParts(secPerKm)
    return ValueUnit("${p.minutes}:${pad2(p.seconds)}", stringResource(Res.string.fit_unit_per_km))
}

/** Energy split: kcal number + "kcal"; null renders a dash. */
@Composable
fun energyValueUnit(kj: Int?): ValueUnit {
    if (kj == null) return ValueUnit(stringResource(Res.string.fit_dash), null)
    return ValueUnit(groupThousands(FitFormat.kjToKcal(kj)), stringResource(Res.string.fit_unit_kcal))
}

/** Plain integer metric (bpm, ascent, steps-per-day) or a dash when absent. */
@Composable
fun intOrDash(value: Int?): String =
    value?.let { groupThousands(it) } ?: stringResource(Res.string.fit_dash)

/** Resolve any bare [StringResource] — a thin wrapper so screens stay import-light. */
@Composable
fun text(res: StringResource): String = stringResource(res)
