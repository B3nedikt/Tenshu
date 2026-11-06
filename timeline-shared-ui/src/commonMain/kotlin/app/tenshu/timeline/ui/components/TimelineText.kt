package app.tenshu.timeline.ui.components

import androidx.compose.runtime.Composable
import app.tenshu.ui.core.LocalMeasurementSystem
import app.tenshu.timeline.model.DistanceForm
import app.tenshu.timeline.model.DurationForm
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.MovementModes
import app.tenshu.timeline.model.TimelineFormat
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_duration_hours
import app.tenshu.timeline.shared.resources.timeline_duration_hours_minutes
import app.tenshu.timeline.shared.resources.timeline_distance_km
import app.tenshu.timeline.shared.resources.timeline_distance_ft
import app.tenshu.timeline.shared.resources.timeline_distance_m
import app.tenshu.timeline.shared.resources.timeline_distance_mi
import app.tenshu.timeline.shared.resources.timeline_duration_minutes
import app.tenshu.timeline.shared.resources.timeline_movement_cycle
import app.tenshu.timeline.shared.resources.timeline_movement_drive
import app.tenshu.timeline.shared.resources.timeline_movement_flight
import app.tenshu.timeline.shared.resources.timeline_movement_route
import app.tenshu.timeline.shared.resources.timeline_movement_route_from
import app.tenshu.timeline.shared.resources.timeline_movement_route_to
import app.tenshu.timeline.shared.resources.timeline_movement_run
import app.tenshu.timeline.shared.resources.timeline_movement_transit
import app.tenshu.timeline.shared.resources.timeline_movement_unknown
import app.tenshu.timeline.shared.resources.timeline_movement_walk
import app.tenshu.timeline.shared.resources.timeline_now
import app.tenshu.timeline.shared.resources.timeline_place_unnamed
import app.tenshu.timeline.shared.resources.timeline_photos_none
import app.tenshu.timeline.shared.resources.timeline_photos_one
import app.tenshu.timeline.shared.resources.timeline_photos_other
import org.jetbrains.compose.resources.stringResource

/**
 * The UI half of Timeline's localisation split (docs/proposals/timeline-app.md §8): the
 * logic layer emits data (`DurationForm`, raw `Int` counts), and these `@Composable`
 * helpers render the localised strings via `stringResource`. Separators (" · ") are literal
 * punctuation.
 */

@Composable
fun durationText(minutes: Int): String {
    val parts = TimelineFormat.durationParts(minutes)
    return when (parts.form) {
        DurationForm.MinutesOnly -> stringResource(Res.string.timeline_duration_minutes, parts.minutes)
        DurationForm.HoursOnly -> stringResource(Res.string.timeline_duration_hours, parts.hours)
        DurationForm.HoursAndMinutes ->
            stringResource(Res.string.timeline_duration_hours_minutes, parts.hours, parts.minutes)
    }
}

@Composable
fun photoCountText(count: Int): String = when {
    count <= 0 -> stringResource(Res.string.timeline_photos_none)
    count == 1 -> stringResource(Res.string.timeline_photos_one, count)
    else -> stringResource(Res.string.timeline_photos_other, count)
}

/**
 * A movement's travel mode, localised. The raw string comes straight off `MovementTrace.mode`, so an
 * unrecognised value — a mode a newer peer introduced, or `transit` before this build knew it —
 * falls back to the generic label rather than rendering blank.
 */
@Composable
fun movementModeText(mode: String?): String = when (mode) {
    MovementModes.WALK -> stringResource(Res.string.timeline_movement_walk)
    MovementModes.RUN -> stringResource(Res.string.timeline_movement_run)
    MovementModes.CYCLE -> stringResource(Res.string.timeline_movement_cycle)
    MovementModes.DRIVE -> stringResource(Res.string.timeline_movement_drive)
    MovementModes.TRANSIT -> stringResource(Res.string.timeline_movement_transit)
    MovementModes.FLIGHT -> stringResource(Res.string.timeline_movement_flight)
    else -> stringResource(Res.string.timeline_movement_unknown)
}

/**
 * A path length in the user's units — "2.4 km" / "1.5 mi" above the changeover, whole metres or
 * feet below it.
 *
 * The system comes from [LocalMeasurementSystem] rather than a parameter: it is the realm-wide
 * `OwnerPreferences.Units` (`fit-app.md` §18.2), so every call site here would otherwise have to
 * thread an argument it has no opinion about. ⚠ Until FP-6 this rendered kilometres
 * unconditionally, which meant an imperial user got an imperial Fit and a metric Timeline.
 */
@Composable
fun distanceText(distanceM: Long): String {
    val parts = TimelineFormat.distanceParts(distanceM, LocalMeasurementSystem.current)
    return when (parts.form) {
        DistanceForm.Metres -> stringResource(Res.string.timeline_distance_m, parts.metres)
        DistanceForm.Feet -> stringResource(Res.string.timeline_distance_ft, parts.metres)
        // The separator lives in the template, not here — see TimelineFormat.distanceParts.
        DistanceForm.Kilometres ->
            stringResource(Res.string.timeline_distance_km, parts.kilometres, parts.tenths)
        DistanceForm.Miles ->
            stringResource(Res.string.timeline_distance_mi, parts.kilometres, parts.tenths)
    }
}

/**
 * A movement's route — "Home → Work" when both bracketing visits resolved, "Ride to Work" /
 * "Ride from Home" when only one did, and `null` when neither did (an isolated trip, a capture
 * hole, un-named places). `null` is the signal that the row has no route to show, so the caller
 * falls back to the bare mode; see [momentTitleText].
 */
@Composable
fun movementRouteText(moment: Moment): String? {
    if (moment.kind != MomentKind.Movement) return null
    // D9: a confirmed role outranks the place's own label — "Home → Work" rather than
    // "Street 12 → Building". Only a role this build can *name* wins; an
    // unrecognised one falls through to the label rather than printing a wire token.
    val from = (placeRoleName(moment.originRole) ?: moment.originPlace)?.takeIf { it.isNotBlank() }
    val to = (placeRoleName(moment.destinationRole) ?: moment.destinationPlace)?.takeIf { it.isNotBlank() }
    // §33 L5: the line replaces the *mode* word, never the endpoints — "U5 to Odeonsplatz" rather
    // than "Transit to Odeonsplatz". ⚠ Blank is the ordinary case and simply leaves the mode.
    val mode = moment.movementLine.ifBlank { movementModeText(moment.movementMode) }
    return when {
        // ⚠⚠ The line is NOT prefixed onto the two-endpoint form. "München Ost → Odeonsplatz"
        // already names both ends, and §32.14 measured those endpoints as the readable part; adding
        // "U5 · " in front of a row that also carries mode + distance in its meta line is where this
        // stops being information and starts being clutter. It rides the one-endpoint forms, which
        // are exactly the rows that currently say "Transit".
        from != null && to != null -> stringResource(Res.string.timeline_movement_route, from, to)
        to != null -> stringResource(Res.string.timeline_movement_route_to, mode, to)
        from != null -> stringResource(Res.string.timeline_movement_route_from, mode, from)
        // ⚠ A leg with neither endpoint named still gets its line when one is known — that is the
        // whole point for an underground hop, whose ends are often unnamed.
        else -> moment.movementLine.takeIf { it.isNotBlank() }
    }
}

/**
 * A moment's headline, derived rather than read off `Moment.title`.
 *
 * The stored title is written at ingest — hardcoded `"Visit"` for every captured visit
 * (`VisitDetector`), and a pre-composed `"Walk · 2.4 km"` for a movement (`MovementMapper`). Both
 * are wrong for the feed: a column of identical "Visit" rows carries no information while the place
 * name sits demoted in the meta line, and the movement's mode+distance are then repeated verbatim by
 * [momentMetaText]. Deriving here fixes every already-stored row with no migration, and makes these
 * titles localisable for the first time — the ingest-side strings are unconditionally English.
 *
 * Kinds without a subject of their own (`Activity`, `Event`, `Quiet`) keep the stored title.
 */
@Composable
fun momentTitleText(moment: Moment): String = when (moment.kind) {
    MomentKind.Movement -> movementRouteText(moment) ?: movementModeText(moment.movementMode)
    // D9 again: "Home" reads better than the street address the geocoder found, and it is what the
    // user themselves chose to call the place by confirming the role.
    MomentKind.Visit -> placeRoleName(moment.placeRole)
        ?: moment.place.ifBlank { stringResource(Res.string.timeline_place_unnamed) }
    else -> moment.title
}

/**
 * A moment's summary line — composed from real counts/duration ("3 photos · 1 h 22 min").
 *
 * A **movement** leads with its distance instead of a photo count: "2.4 km · 31 min". A walk has no
 * photos, so the generic head would render the actively-unhelpful "No photos" — and for the same
 * reason a **visit** drops the head entirely when it has none, now that [momentTitleText] has taken
 * the place name out of this line and "No photos" would otherwise lead it.
 *
 * The travel mode appears here only when [momentTitleText] rendered the *route* instead; when the
 * title is already the bare mode, repeating it is the duplication this exists to avoid.
 */
@Composable
fun momentMetaText(moment: Moment): String {
    val head = when {
        // ⚠ Movement is checked *before* isCurrent, unlike every other kind. For a visit, "now"
        // replacing the photo count loses nothing; for a ride in progress the distance so far is
        // the most interesting thing on the row, and the earlier ordering silently dropped it.
        moment.kind == MomentKind.Movement -> listOfNotNull(
            stringResource(Res.string.timeline_now).takeIf { moment.isCurrent },
            // ⚠ The line, not the bare mode, when we have one: the meta line reading "U5 · 2.9 km"
            // says strictly more than "Transit · 2.9 km" for the same width.
            moment.movementLine.ifBlank { movementModeText(moment.movementMode) }
                .takeIf { movementRouteText(moment) != null },
            moment.distanceM?.let { distanceText(it) },
        )
        moment.isCurrent -> listOf(stringResource(Res.string.timeline_now))
        moment.kind == MomentKind.Visit && moment.photos.isEmpty() -> emptyList()
        else -> listOf(photoCountText(moment.photos.size))
    }
    val duration = moment.durationMin?.let { durationText(it) }
    return (head + listOfNotNull(duration)).joinToString(" · ")
}
