package app.tenshu.timeline.model

import kotlinx.datetime.LocalDate

/**
 * Timeline domain model — Ph1 of docs/proposals/timeline-app.md (§5), ported from the
 * design handoff's `timeline-agent.md` §4 / `mockup/timeline-data.js`.
 *
 * This is *structure*, not a wire format — the real on-device Timeline store replaces
 * the mock later behind `TimelineRepository`. Two deliberate deviations from the mock:
 *
 *  - The presentational **`meta`** strings ("3 photos · 1 h 22 min") are dropped — they
 *    are composed **and localised in the UI layer** from real counts/durations
 *    (`:timeline-shared-ui` `stringResource`, ADR 0016). This module stays Compose-free
 *    and string-free; it only carries the raw `Int` counts and (via [TimelineFormat]) the
 *    locale-independent duration split. Other human-string fields the mock carries
 *    (dateRange, firstVisit, avgDuration, …) are kept as-is for v1; they become typed when
 *    a real store lands.
 *  - Moment category is an explicit [MomentKind] enum, not a title-string heuristic.
 */

/** A photo. `dominantColor` is load-bearing — personalisation tints chrome from it. */
data class Photo(
    val dominantColor: String,
    val uri: String? = null,
    val label: String? = null,
)

data class Movement(
    val distanceKm: Double,
    val steps: Int,
)

/**
 * Drives the moment-detail anatomy (§1.3) and the rail icon.
 *
 * ⚠ [Movement] here is the enum constant — distinct from the day-level
 * [app.tenshu.timeline.model.Movement] rollup above, which is a distance/step summary for a whole
 * day. Same package, so qualify (`MomentKind.Movement`) wherever both are in scope.
 */
enum class MomentKind { Activity, Visit, Event, Quiet, Movement }

/**
 * The standing role a place plays in the user's life — the `PlaceRole.role` strings
 * (`docs/proposals/place-roles.md`). Raw strings for the same reason as [MovementModes]: a newer
 * peer can introduce `gym` or `school`, and this build must carry it through rather than fail.
 */
object PlaceRoleNames {
    const val HOME = "home"
    const val WORK = "work"

    /**
     * No special role. ⚠ Paired with a *user* source this is a **dismissal** — "I am telling you
     * this place has no special role" — which is why it is a value rather than an absent row.
     */
    const val NONE = "none"
}

/**
 * A place's role and whether the user has answered yet.
 *
 * [isSuggestion] is the whole reason this is not a bare string: an inferred role is a **question**
 * the UI asks ("Is this your Home?"), a user-authored one is a **fact** it displays. Rendering a
 * guess as a fact is the failure this feature is designed to avoid.
 */
data class PlaceRoleState(
    val role: String,
    val isSuggestion: Boolean,
) {
    /** A confirmed, non-empty role — the only kind that may stand in for a place's name (D9). */
    val isConfirmed: Boolean get() = !isSuggestion && role != PlaceRoleNames.NONE
}

/** Travel modes a [MomentKind.Movement] moment can carry — the `MovementTrace.mode` strings. */
object MovementModes {
    const val WALK = "walk"
    const val RUN = "run"
    const val CYCLE = "cycle"
    const val DRIVE = "drive"
    const val TRANSIT = "transit"
    const val FLIGHT = "flight"
    const val UNKNOWN = "unknown"
}

data class Moment(
    val id: String,
    val time: String,               // "HH:mm"
    val title: String,
    val place: String,
    val kind: MomentKind,
    val durationMin: Int? = null,
    val photos: List<Photo> = emptyList(),
    /** The live/ongoing moment ("● now"). */
    val isCurrent: Boolean = false,
    val cover: Photo? = null,
    /**
     * Travel mode for a [MomentKind.Movement] moment — a [MovementModes] value; `null` otherwise.
     * A raw string, not an enum: the underlying `MovementTrace.mode` is a plain wire string so a
     * newer peer can introduce a mode this build has never heard of, and the UI must fall back
     * rather than fail.
     */
    val movementMode: String? = null,
    /** Path length in metres for a [MomentKind.Movement] moment; `null` otherwise. */
    val distanceM: Long? = null,
    /**
     * The transit line(s) this leg ran on — `"U5"`, or `"U3/U6"` where a shared trunk serves both
     * ends (`timeline-movement-capture.md` §33 L5). Blank when unknown, which is the ordinary case.
     *
     * ⚠⚠ Blank is **ignorance, never "no line"**: no overlay for the region, no artefact, a fetch
     * that failed. The row must render exactly as it did before overlays existed rather than assert
     * anything, so every consumer treats this as an optional prefix and nothing more.
     */
    val movementLine: String = "",
    /**
     * The place a [MomentKind.Movement] moment departed from / arrived at, as a display name;
     * `null` when unknown (no bracketing visit, an un-named place, or a capture hole). Derived at
     * projection time from the visits that immediately bracket the movement in time — a movement
     * carries no stored endpoint reference, and deliberately gains none: the bracketing visits
     * already are the answer, so a stored copy could only drift from them.
     *
     * Lets the feed render the route ("Home → Work") instead of the mode, which is the same string
     * the meta line already carries.
     */
    val originPlace: String? = null,
    val destinationPlace: String? = null,
    /**
     * A **confirmed** role for this moment's place, and for a movement's two endpoints; `null` when
     * the place has none or the role is still only a suggestion (place-roles.md D9).
     *
     * ⚠ The *token* (`home`), not a display name — the localised string is resolved in the UI, so
     * "Home → Work" reads correctly in every locale. Carrying the name here would re-introduce the
     * unlocalised-title bug §11 just fixed.
     */
    val placeRole: String? = null,
    val originRole: String? = null,
    val destinationRole: String? = null,
    /**
     * The place this moment is about, for a [MomentKind.Visit] — its `placeId`; `null` otherwise.
     * Lets the visit detail tap through to the place and correct its name (§4). Not the display name.
     */
    val placeId: String? = null,
    /**
     * The trace this moment is about, for a [MomentKind.Movement] — its `traceId`; `null` otherwise.
     * Lets the movement detail fetch the trace's decoded path for the route-over-basemap hero (M5);
     * the feed itself never reads the polyline blob.
     */
    val traceId: String? = null,
    /**
     * **Every** trace this movement is about, in time order — one id for an ordinary movement, several
     * for a ride whose in-ride station stops the projector collapsed away
     * (`TimelineProjection.collapseTransitStationStops`).
     *
     * ⚠ [traceId] alone cannot express a collapsed ride, and drawing only its first leg would put a
     * map on screen that contradicts the card's combined distance. The hero concatenates these in
     * this order; the feed still never reads a polyline.
     */
    val traceIds: List<String> = emptyList(),
    /** The place's E7 coordinates (degrees × 1e7) when known, for tap-through + place suggestions; `0` = unknown. */
    val latE7: Long = 0,
    val lngE7: Long = 0,
    /**
     * How many raw stays were folded into this one (1 = untouched). When >1, several consecutive
     * same-place [MomentKind.Visit] fragments — the artefact of the user wandering a few metres
     * within one place, which pushes the dwell centroid across the placeId grid cell — were merged
     * into a single span. Surfaced in the visit detail so the merge is legible.
     */
    val mergedStayCount: Int = 1,
    /**
     * Short movements absorbed into a merged [MomentKind.Visit] — the "walking around" between the
     * merged fragments. Empty for un-merged moments. Rendered in the visit detail (there is no
     * feed-row treatment yet); the day feed shows only the merged visit itself.
     */
    val containedMoves: List<ContainedMove> = emptyList(),
)

/**
 * A short movement that happened *within* a merged visit (§2b) — the user moving a little between
 * two same-place stay fragments. Carries just what the detail row needs; the full path stays
 * unread (the heavy polyline blob is never decoded for this).
 */
data class ContainedMove(
    /** A [MovementModes] value; a raw string so an unknown newer mode still renders. */
    val mode: String,
    val distanceM: Long,
    val durationMin: Int? = null,
)

data class Day(
    val date: LocalDate,
    val dayLabel: String,           // "Today" / "Yesterday" / weekday
    val dateLong: String,           // "Sunday, May 24"
    val place: String,
    val weather: String? = null,
    val movement: Movement? = null,
    val moments: List<Moment> = emptyList(),
)

/**
 * A trip. **Recursive**: [contents] mixes days, nested trips, and collapse markers, so a
 * trip can contain trips (Lisbon holds a Sintra day-trip). Modelled for arbitrary nesting
 * even though the mock nests one level.
 */
/** A geographic point (lat/lng degrees) on a trip's route — the map-surface seam (`maps-region-delivery.md` R6). */
data class GeoPoint(val latitude: Double, val longitude: Double)

data class Trip(
    val id: String,
    val title: String,
    val dateRange: String,          // "Apr 12 – 17"
    val daysSpan: Int,
    val placesCount: Int,
    val photosCount: Int,
    val cover: Photo,
    val year: String? = null,
    val momentsCount: Int? = null,
    val tags: List<String> = emptyList(),
    val contents: List<TripEntry> = emptyList(),
    /**
     * The trip's route over an offline basemap (the trip's ordered Visit-place coordinates) — the
     * "route-over-basemap" surface (`maps-app.md` §2 step 3). Empty until derived; rendered by
     * `TripRouteMap` when it has ≥2 points. `TimelineProjection.trip(...)` derives it from the member
     * moments' synced place coords (chronological, consecutive-dedup, coordinate-less dropped); the
     * mock supplies an equivalent hand-authored sample.
     */
    val route: List<GeoPoint> = emptyList(),
)

/** One row inside a trip's [Trip.contents]. */
sealed interface TripEntry {
    /** A day-with-moments inside a multi-day trip. */
    data class DayEntry(
        val dayNum: Int,
        val dayLabel: String,       // "Day 1"
        val dateLong: String,       // "Sat · Apr 12"
        val moments: List<Moment>,
    ) : TripEntry

    /** A nested trip occupying a day slot — renders the same detail screen recursively. */
    data class NestedTrip(
        val dayNum: Int,
        val dayLabel: String,
        val dateLong: String,
        val trip: Trip,
    ) : TripEntry

    /** A "+N more days" expander. */
    data class Collapsed(
        val daysRemaining: Int,
        val label: String,
    ) : TripEntry

    /** A flat moment — single-day trips list their moments directly. */
    data class MomentItem(val moment: Moment) : TripEntry
}

/** Auto-detected trip suggestion — drives the Interlude card. */
data class TripSuggestion(
    val id: String,
    val title: String,
    val dateRange: String,
    val daysSpan: Int,
    val placesCount: Int,
    val photosCount: Int,
    val cover: Photo,
    val confidence: Double,
)

data class Place(
    val id: String,
    val name: String,
    val neighborhood: String,
    val city: String,
    val visitsCount: Int,
    val photosCount: Int,
    val firstVisit: String,
    val lastVisit: String,
    val cover: Photo,
    val tags: List<String> = emptyList(),
    val avgDuration: String? = null,
    val history: List<PlaceVisit> = emptyList(),
    /** E7 coordinates (degrees × 1e7), for the place-detail map hero; `0` when unknown. */
    val latE7: Long = 0,
    val lngE7: Long = 0,
    /**
     * This place's role, or `null` when it has none. A [PlaceRoleState.isSuggestion] value drives
     * the confirm prompt; a confirmed one renders as a badge (place-roles.md PR-3).
     */
    val role: PlaceRoleState? = null,
)

data class PlaceVisit(
    val id: String,
    val date: String,
    val time: String,
    val label: String,
    val duration: String,
    val photosCount: Int,
    val photos: List<Photo> = emptyList(),
)

/** One row on the Activities tab (aggregate roll-up). */
data class ActivityRollup(
    val kind: String,
    val label: String,
    val icon: String,
    val count: Int,
    val suffix: String,             // "sessions" / "visits" / …
    val secondary: String,
    val lastDate: String,
    val dominantColor: String,
)
