package app.tenshu.timeline.data.projection

import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND
import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND
import app.tenshu.timeline.data.capture.movementTitle
import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.ContainedMove
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.MovementModes
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.PlaceVisit
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripEntry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant
import org.tenshu.core.place.LabelSource

/**
 * Pure mapping from the three backend services' data → the Timeline app's presentation model
 * (`docs/proposals/timeline-services.md` §6). The heart of the real `TimelineRepository`: the
 * *lazy* stored entities become *materialised* projections (`Day`s grouped from `Moment`s,
 * `ActivityRollup`s aggregated from `Workout`s, a `Place`'s visit history derived from the
 * `Visit`-moments that point at it) — the lazy-vs-materialised seam (AGENTS.md "Open items")
 * in concrete form.
 *
 * Deliberately **pure Kotlin over lightweight [MomentInput]/[TripInput]/[PlaceInput]/
 * [WorkoutInput]** — no Tenshu entity types, no client — so it unit-tests in isolation.
 * `TimelineProjectionRepository` extracts these inputs from the entities it reads over the
 * client and calls these functions.
 *
 * **Subject hydration (§1.1 / §5.1).** A `Moment` references its domain subject by a
 * polymorphic `{kind, id}` [SubjectRef][org.tenshu.core.timeline.SubjectRef]: a `Visit` points
 * at a `Place`, an `Activity` at a `Workout`. The projector resolves that reference through one
 * uniform dispatch ([subtitleResolver]), filling the moment's `place` subtitle (a place name /
 * a workout's activity) and — for places — deriving the visit history the mock hand-authored.
 *
 * **Scope note:** the scaffold service entities still carry no photos / weather, so those
 * presentation fields stay honest empties (`photosCount = 0`), derived for real once the deferred
 * slices land. A trip's `contents` / day-chapters / `route` *are* derived here from its membership +
 * the moment/place stores (nested-trip recursion excepted — the `Trip` scaffold has no nested-trip
 * ids yet). Presentational strings (`dayLabel`, `dateLong`, visit dates/durations) are derived
 * minimally; ADR 0016 localisation of these moves to the UI layer later.
 */
object TimelineProjection {

    data class MomentInput(
        val id: String,
        val timestampMs: Long,
        val kind: String,
        val title: String,
        val durationSec: Long,
        /** The subject reference's kind — see [Subject]; [Subject.NONE] when the moment has none. */
        val subjectKind: String = Subject.NONE,
        /** The owning service's id for the subject (a `placeId` / `workoutId`); `""` when none. */
        val subjectId: String = "",
        /** Set when a reconciliation pass folded this moment into a canonical (I6, §7.1) — then hidden. */
        val supersededBy: String = "",
        /**
         * Provenance tags (`MomentSourceTag`) read off `Moment.sources`. Feeds
         * [org.tenshu.timeline.common.ReconcilableMoment.source] so canonical election can prefer a
         * source instead of falling through to earliest-start (§12.3).
         */
        val sources: List<String> = emptyList(),
    )

    /**
     * One photo the user took, as `photosCapturedBetween` reports it (`timeline-photos.md` T-P3).
     *
     * ⚠ Lightweight on purpose, like every other input here: a path, a time and the provenance
     * flag. No bytes — the feed decides *which* photos a row shows; the UI decides which of those
     * are on screen and worth fetching a blob for.
     */
    data class PhotoInput(
        val path: String,
        val capturedAtMs: Long,
        val backedUp: Boolean,
        /** Where it was taken, E7; `0`/`0` when the photo carries no coordinate (T-P4). */
        val latE7: Long = 0,
        val lngE7: Long = 0,
    ) {
        val hasCoordinate: Boolean get() = latE7 != 0L || lngE7 != 0L
    }

    data class TripInput(
        val id: String,
        val title: String,
        val dateRange: String,
        val year: String,
        /** The trip's member moments by [Moment.momentId] (`Trip.membership`, §5.2); empty when unset. */
        val membership: List<String> = emptyList(),
    )
    data class PlaceInput(
        val id: String,
        val label: String,
        val latE7: Long = 0,
        val lngE7: Long = 0,
        val city: String = "",
        val countryCode: String = "",
        /**
         * Provenance of [label] — a [LabelSource] token. Blank / unrecognised degrades to
         * `geocoded`, the never-protected reading, exactly as `Place.labelSource` does.
         */
        val labelSource: String = "",
    ) {
        /**
         * The user named this place themselves — mirrors
         * [org.tenshu.core.place.PlaceClustering.Candidate.hasUserLabel]. A user-named place is
         * *meaningful by definition*, which is what stops [collapseTransitStationStops] swallowing it.
         */
        val hasUserLabel: Boolean get() = LabelSource.isUser(labelSource) && label.isNotBlank()
    }
    /**
     * A movement trace — the subject a `MOVEMENT` moment points at. Only the two fields the card
     * renders; the heavy `polyline` blob stays unread (that is what the §2.1 summary-vs-blob split
     * is for, and the feed must not decode a path per row).
     */
    data class TraceInput(
        val id: String,
        val mode: String,
        val distanceM: Long,
        /**
         * Which classifier produced [mode] — see `MovementTrace.classifierVersion`. Read here for one
         * reason only: it is the single bit that separates a **metro** leg from the surface rail leg
         * beside it, which [collapseTransitStationStops] needs to tell a transfer from a pass-through
         * stop (§32.12). `0` = unknown / not carried.
         */
        val classifierVersion: Int = 0,
        /**
         * The named transit stops this leg ran **between** (§32.14), or blank.
         *
         * ⚠ Resolved in the repository, not here — it is a tile lookup and this object is pure. Blank
         * is the ordinary case: filled only for a `transit` leg whose endpoints sit near a named stop.
         */
        val originStop: String = "",
        val destinationStop: String = "",
        /**
         * The transit line(s) serving both ends, `"U3/U6"`-style, or blank (§33 L5).
         *
         * ⚠ Resolved in the repository like [originStop] — it is a catalogue+artefact lookup and
         * this object is pure.
         */
        val line: String = "",
        /** The leg's own first/last point (E7) — what that lookup is run against. */
        val startLatE7: Long = 0,
        val startLngE7: Long = 0,
        val endLatE7: Long = 0,
        val endLngE7: Long = 0,
    )

    data class WorkoutInput(val id: String, val activityType: String)

    /**
     * The journey the user is **currently on** — `OngoingMoveState`, projected
     * (`timeline-movement-capture.md` §12.6). Device-local live capture state, *not* a stored row:
     * §9's emit-on-close decision keeps movement moments immutable, so an in-flight leg has no
     * `Moment`/`MovementTrace` of its own until it closes.
     *
     * [days] turns this into a synthetic feed row that behaves like any other movement — it brackets
     * against the visit before it, it can be folded into a collapsed transit ride — and that
     * **disappears into the real row** the moment capture writes one ([ONGOING_MOVE_ID]).
     *
     * [mode] is already confidence-gated by `OngoingMove.provisionalMode`; the projector renders
     * whatever it is handed, so an unsettled classification arrives here as
     * [org.tenshu.timeline.model.MovementModes.UNKNOWN] and reads as the plain "Movement" every
     * other unknown-mode leg does.
     */
    data class OngoingMoveInput(
        val startMs: Long,
        val distanceM: Long,
        val mode: String,
    )

    /**
     * The id the synthetic in-progress movement row carries.
     *
     * Reserved by construction — every stored movement is content-addressed `cap-move:<startMs>` /
     * an importer's own scheme, so no real row can collide. That is what lets `isCurrent` for a
     * movement be read off the id alone instead of threading a second "which one is live" argument
     * through every projection helper.
     */
    const val ONGOING_MOVE_ID = "ongoing-move"

    /** The trace subject of [ONGOING_MOVE_ID] — resolvable for mode/distance, never fetchable. */
    private const val ONGOING_TRACE_ID = "ongoing-trace"

    /**
     * One maintained per-activity count (`WorkoutActivityRollup`) — the lifetime aggregate the
     * Activities surface reads instead of grouping the workout table.
     */
    data class ActivityCountInput(val activityType: String, val count: Int)

    /** Presentation categories, matching `org.tenshu.core.timeline.Moment.MomentKind` strings. */
    object Kind {
        const val ACTIVITY = "Activity"
        const val VISIT = "Visit"
        const val EVENT = "Event"
        const val QUIET = "Quiet"
        const val MOVEMENT = "Movement"
    }

    /** Subject kinds, matching `org.tenshu.core.timeline.SubjectKind` strings (§5.1). */
    object Subject {
        const val NONE = "none"
        const val PLACE = "place"
        const val WORKOUT = "workout"
        const val TRACE = "trace"
    }

    /** Amethyst-ish placeholder tint until real photos + dominant-colour extraction land. */
    private const val DEFAULT_COLOR = "#8B7BB8"

    /**
     * Maps a single moment. [subtitle] resolves its subject reference into the `place` line
     * (a place name for a `Visit`, the activity for an `Activity`); defaults to empty so the
     * single-moment path and unit tests can opt out of hydration.
     */
    fun moment(
        m: MomentInput,
        subtitle: (MomentInput) -> String = { "" },
        zone: TimeZone = TimeZone.currentSystemDefault(),
        /** Arrival of the place the user is currently at (device-local live state, §11.2); `null` = none. */
        ongoingArrivalMs: Long? = null,
        /** "Now" in epoch-ms, for the live duration of the current place. */
        nowMs: Long = 0,
        /** Resolves a `MOVEMENT` moment's trace subject into its mode + distance; `null` for others. */
        trace: (MomentInput) -> TraceInput? = { null },
        /** Resolves a `Visit`'s place coordinates (latE7 to lngE7); `null` when unknown/not a place. */
        coords: (MomentInput) -> Pair<Long, Long>? = { null },
        /**
         * A `MOVEMENT` moment's `(origin, destination)` place names from [movementEndpoints];
         * defaults to unknown so the single-moment path — which has no neighbours to bracket
         * against — keeps rendering the plain mode.
         */
        endpoints: Pair<Endpoint?, Endpoint?> = NO_ENDPOINTS,
        /** `placeId → confirmed role token`; a Visit stamps its own, a Movement its two ends. */
        roles: Map<String, String> = emptyMap(),
    ): Moment {
        val local = Instant.fromEpochMilliseconds(m.timestampMs).toLocalDateTime(zone)
        // The current, ongoing place ticks its duration live (now - arrival) and shows the "● now" dot,
        // instead of the frozen value written at confirmation — no per-minute re-sync (§11.2). The
        // in-progress *journey* (§12.6) ticks the same way; it is identified by its reserved id
        // rather than a timestamp, because unlike a visit it has no row to match against.
        val isCurrent = (ongoingArrivalMs != null && m.kind == Kind.VISIT && m.timestampMs == ongoingArrivalMs) ||
            (m.kind == Kind.MOVEMENT && m.id == ONGOING_MOVE_ID)
        val durationMin = when {
            isCurrent -> ((nowMs - m.timestampMs).coerceAtLeast(0) / 60_000L).toInt()
            m.durationSec > 0 -> (m.durationSec / 60).toInt()
            else -> null
        }
        val movementTrace = if (m.kind == Kind.MOVEMENT) trace(m) else null
        val placeId = m.subjectId.takeIf { m.subjectKind == Subject.PLACE && it.isNotEmpty() }
        // ⚠ The ongoing leg's trace subject resolves (mode + distance) but is deliberately not
        // surfaced as a fetchable id: no `MovementTrace` row exists yet, so a detail hero pointed at
        // it would load nothing. The row gains its real trace when the leg flushes.
        val traceId = m.subjectId
            .takeIf { m.subjectKind == Subject.TRACE && it.isNotEmpty() && it != ONGOING_TRACE_ID }
        val coord = if (placeId != null) coords(m) else null
        return Moment(
            id = m.id,
            time = twoDigit(local.hour) + ":" + twoDigit(local.minute),
            title = m.title,
            place = subtitle(m),
            kind = momentKind(m.kind),
            durationMin = durationMin,
            photos = emptyList(),
            isCurrent = isCurrent,
            cover = null,
            movementMode = movementTrace?.mode,
            distanceM = movementTrace?.distanceM,
            movementLine = movementTrace?.line.orEmpty(),
            originPlace = endpoints.first?.name,
            destinationPlace = endpoints.second?.name,
            placeRole = placeId?.let { roles[it] },
            originRole = endpoints.first?.role,
            destinationRole = endpoints.second?.role,
            placeId = placeId,
            traceId = traceId,
            traceIds = listOfNotNull(traceId),
            latE7 = coord?.first ?: 0,
            lngE7 = coord?.second ?: 0,
        )
    }

    /**
     * Groups moments into day-chapters, newest day first, moments within a day oldest first.
     * [places] / [workouts] hydrate each moment's subject subtitle (§1.1) — a `Visit` shows its
     * place's name, an `Activity` its workout's activity.
     *
     * Consecutive same-place `Visit` fragments are merged into a single span first (§2b,
     * [mergeConsecutiveVisits]) so a stay the capture layer split (the user wandering a few metres
     * pushes the dwell centroid across the placeId grid cell) reads as one row. A short
     * pass-through stop *inside* a transit ride is then collapsed away
     * ([collapseTransitStationStops]) so a train journey reads as one ride, not ride/stay/ride.
     */
    fun days(
        moments: List<MomentInput>,
        places: List<PlaceInput> = emptyList(),
        workouts: List<WorkoutInput> = emptyList(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
        ongoingArrivalMs: Long? = null,
        nowMs: Long = 0,
        traces: List<TraceInput> = emptyList(),
        /**
         * `placeId → role token` for places carrying a **confirmed** role (place-roles.md D9). Only
         * confirmed ones: a suggestion must not rename the user's rows before they have answered.
         */
        roles: Map<String, String> = emptyMap(),
        /**
         * Places carrying **any** `PlaceRole` row — confirmed *or* still only a suggestion. Wider
         * than [roles] on purpose, and only ever read as a veto: [collapseTransitStationStops] must
         * not swallow a place the inference pass already thinks is Home, merely because the user has
         * not answered the prompt yet. Defaults to [roles]' keys so every existing caller keeps the
         * guard it already had.
         */
        rolePlaceIds: Set<String> = roles.keys,
        /**
         * The journey in progress (§12.6), or `null`. Rendered as a synthetic row — never stored —
         * and suppressed the instant the real one exists; see [ongoingMoveRows].
         */
        ongoingMove: OngoingMoveInput? = null,
        /**
         * The photos the user took across the span being projected (T-P3), unassigned. Each moment
         * claims the ones inside its own window here, so the feed row and the detail it opens can
         * never disagree about which photos belong to it — they are the same projection.
         */
        photos: List<PhotoInput> = emptyList(),
    ): List<Day> {
        val (liveMoment, liveTrace) = ongoingMoveRows(ongoingMove, moments, nowMs)
        @Suppress("NAME_SHADOWING") val moments = moments + listOfNotNull(liveMoment)
        @Suppress("NAME_SHADOWING") val traces = traces + listOfNotNull(liveTrace)
        val subtitle = subtitleResolver(places, workouts)
        val trace = traceResolver(traces)
        val coords = coordResolver(places)
        // ⚠ Order matters. The visit merge runs FIRST so a station stay the capture layer split into
        // fragments presents as one span to the stop-collapse (and so its *total* dwell is what the
        // duration bound judges). The collapse then runs BEFORE [movementEndpoints], so the surviving
        // ride brackets against the real origin/destination rather than against the station it passed
        // through — which is exactly the "Home → Work, not Home → Station" reading we want.
        val merged = collapseTransitStationStops(
            mergeConsecutiveVisits(moments.sortedBy { it.timestampMs }, places, trace),
            places, trace, rolePlaceIds, ongoingArrivalMs,
        )
        // Computed over the whole merged sequence, before the day grouping — a movement's
        // destination is routinely on the next calendar day (a night drive), and bracketing within
        // a day would drop exactly those.
        val endpoints = movementEndpoints(merged, subtitle, roles, trace)
        return merged
            .groupBy { localDate(it.startMs, zone) }
            .toList()
            .sortedByDescending { it.first }
            .map { (date, dayMerged) ->
                Day(
                    date = date,
                    dayLabel = titleCase(date.dayOfWeek.name),
                    dateLong = dateLong(date),
                    place = "",
                    weather = null,
                    movement = null,
                    moments = dayMerged.sortedBy { it.startMs }
                        .map { mm ->
                            mergedToUi(
                                mm, subtitle, zone, ongoingArrivalMs, nowMs, trace, coords,
                                endpoints[mm.representative.id] ?: NO_ENDPOINTS,
                                roles,
                            ).copy(photos = photosWithin(photos, mm.startMs, mm.endMs, coords(mm.representative)))
                        },
                )
            }
    }

    /**
     * The synthetic `(moment, trace)` pair for the journey in progress — or `(null, null)`.
     *
     * ⚠⚠ **The no-duplicate guarantee lives here.** A captured leg is content-addressed by its
     * `startMs` (`MovementMapper`: `cap-move:$startMs`, `timestampMs = startMs`), so the moment the
     * flush lands there is a real `MOVEMENT` row with exactly this start — and the live row is
     * dropped. Capture also clears `OngoingMoveState` before it writes, which alone would be
     * enough in a single process; this check makes the guarantee independent of that ordering, of
     * write latency, and of a peer's copy of the leg arriving first.
     *
     * `durationSec` is set from [nowMs] so the row spans up to *now* for everything that reasons
     * about a moment's window — endpoint bracketing, the station-stop collapse, day grouping. The
     * duration actually rendered is recomputed live in [moment], which is what makes it tick.
     */
    private fun ongoingMoveRows(
        ongoing: OngoingMoveInput?,
        moments: List<MomentInput>,
        nowMs: Long,
    ): Pair<MomentInput?, TraceInput?> {
        if (ongoing == null) return null to null
        val alreadyStored = moments.any { it.kind == Kind.MOVEMENT && it.timestampMs == ongoing.startMs }
        if (alreadyStored) return null to null
        val moment = MomentInput(
            id = ONGOING_MOVE_ID,
            timestampMs = ongoing.startMs,
            kind = Kind.MOVEMENT,
            title = movementTitle(ongoing.mode, ongoing.distanceM),
            durationSec = ((nowMs - ongoing.startMs).coerceAtLeast(0)) / 1000,
            subjectKind = Subject.TRACE,
            subjectId = ONGOING_TRACE_ID,
        )
        return moment to TraceInput(ONGOING_TRACE_ID, ongoing.mode, ongoing.distanceM)
    }

    /** One end of a movement: the bracketing visit's place name, and its confirmed role if any. */
    data class Endpoint(val name: String, val role: String?)

    /** No known origin/destination — the shared empty value, so the lookup never allocates a pair. */
    private val NO_ENDPOINTS: Pair<Endpoint?, Endpoint?> = null to null

    /**
     * How far apart a movement and a bracketing visit may sit and still count as its endpoint.
     * Capture holes are routine (an OEM kills the collector, GPS drops indoors), and the visit on
     * the far side of a two-hour hole is not where this trip started — naming it as the origin
     * would be a confident guess, so the endpoint stays unknown and the row falls back to its mode.
     */
    private const val ENDPOINT_MAX_GAP_MS = 30 * 60 * 1000L

    /**
     * A movement's `(origin, destination)` place names, keyed by the movement's moment id — read
     * off the visits that **immediately** bracket it in the merged sequence (§1.1).
     *
     * Immediately is deliberate: scanning past an intervening movement would let an unrecorded stop
     * collapse ("Home → shop → Work" rendering as "Home → Work"). Consecutive movements therefore
     * resolve no endpoint on their inner sides and fall back to the plain mode, which is honest —
     * the M1 segmenter already folds sub-threshold pauses, so single moves between dwells are the
     * common shape.
     *
     * An un-named place resolves to `""` through [subtitleResolver] and is treated as unknown: a
     * route to "" reads worse than the bare mode.
     */
    /**
     * An endpoint the map named, unless the user has already named it themselves.
     *
     * ⚠ Order is **role → stop → place label**. A confirmed role is the user's own word for a place
     * and outranks anything derived (D9); the stop then outranks the *label*, because for a transit
     * leg the bracketing dwell is frequently not the station at all.
     */
    private fun Endpoint?.orStop(stop: String?): Endpoint? {
        val named = stop?.takeIf { it.isNotBlank() } ?: return this
        if (this?.role != null) return this
        return Endpoint(named, null)
    }

    /**
     * A leg's trace iff it is `transit`; `null` otherwise — the §32.14 mode gate, which is the whole
     * safety argument for renaming an endpoint after a station (a walk that happens to finish near
     * one must not be).
     */
    private fun transitStopsOf(leg: MomentInput, trace: (MomentInput) -> TraceInput?): TraceInput? =
        trace(leg)?.takeIf { it.mode == MovementModes.TRANSIT }

    private fun movementEndpoints(
        merged: List<MergedMoment>,
        subtitle: (MomentInput) -> String,
        roles: Map<String, String>,
        trace: (MomentInput) -> TraceInput?,
    ): Map<String, Pair<Endpoint?, Endpoint?>> {
        val out = mutableMapOf<String, Pair<Endpoint?, Endpoint?>>()
        merged.forEachIndexed { i, mm ->
            if (mm.representative.kind != Kind.MOVEMENT) return@forEachIndexed
            val before = merged.getOrNull(i - 1)?.takeIf { mm.startMs - it.endMs <= ENDPOINT_MAX_GAP_MS }
            val after = merged.getOrNull(i + 1)?.takeIf { it.startMs - mm.endMs <= ENDPOINT_MAX_GAP_MS }
            // §32.14: a transit leg is named by the STOPS it ran between, not by the dwells that
            // happen to bracket it. On the reported journey those were a signal hold 2.5 km short of
            // Ostbahnhof and a stay 200 m from Odeonsplatz — neither is where the rider boarded or
            // alighted, and there was no Ostbahnhof dwell at all because they changed without staying.
            //
            // ⚠⚠ §36.5: the two ends are asked of two DIFFERENT legs whenever this is a collapsed
            // ride. `mm.representative` is only the first leg, so one trace can answer where the
            // journey began and never where it ended — a fold was named "Mühldorf → Schwindegg" for
            // an 82.5 km ride because Schwindegg is where its *first* leg stopped. An un-collapsed
            // movement has `ride == null` and both ends resolve to the same trace, exactly as before.
            val originStops = transitStopsOf(mm.representative, trace)
            val destinationStops = transitStopsOf(mm.ride?.lastLeg ?: mm.representative, trace)
            val origin = before?.let { visitLabel(it, subtitle, roles) }.orStop(originStops?.originStop)
            val destination =
                after?.let { visitLabel(it, subtitle, roles) }.orStop(destinationStops?.destinationStop)
            if (origin != null || destination != null) out[mm.representative.id] = origin to destination
        }
        return out
    }

    /**
     * A merged entry's endpoint iff it is a `Visit` with a non-blank label; `null` otherwise.
     *
     * ⚠ A place with a confirmed role but **no name** still yields no endpoint. The role travels
     * *alongside* the name rather than replacing it here, because the UI needs the name as the
     * fallback whenever the role is absent — and a nameless place has nothing to fall back to.
     */
    private fun visitLabel(
        mm: MergedMoment,
        subtitle: (MomentInput) -> String,
        roles: Map<String, String>,
    ): Endpoint? {
        val rep = mm.representative.takeIf { it.kind == Kind.VISIT } ?: return null
        val name = subtitle(rep).takeIf { it.isNotBlank() } ?: return null
        return Endpoint(name, roles[rep.subjectId])
    }

    /** A `Visit`'s place coordinates (latE7 to lngE7) from the place store; `null` when unknown. */
    private fun coordResolver(places: List<PlaceInput>): (MomentInput) -> Pair<Long, Long>? {
        val byId = places.associate { it.id to (it.latE7 to it.lngE7) }
        return { m -> if (m.subjectKind == Subject.PLACE) byId[m.subjectId] else null }
    }

    // ── consecutive-same-place visit merge (§2b) ──────────────────────────────

    /** Two visit fragments within this radius (metres) of each other count as the same place. */
    private const val SAME_PLACE_RADIUS_M = 200.0

    /** Fragments more than this far apart in time never merge (a genuine gap, not a wander). */
    private const val MERGE_MAX_GAP_MS = 15 * 60_000L

    /** A `Movement` between two fragments this long (metres) or more is real travel — blocks the merge. */
    private const val ABSORB_MOVE_MAX_M = 300L

    /** One merged span: a representative fragment plus the window + the movement folded into it. */
    private data class MergedMoment(
        val representative: MomentInput,
        val startMs: Long,
        val endMs: Long,
        val mergedCount: Int,
        val contained: List<ContainedMove>,
        /** Set only by [collapseTransitStationStops] — several transit legs read as one ride. */
        val ride: CollapsedRide? = null,
    )

    /**
     * The whole-journey facts of a ride whose in-ride station stops were collapsed away. Carried
     * separately from [MergedMoment.representative] because **every one of these must be recomputed**:
     * the representative is only the *first leg*, and rendering its stored title ("Train · 34.2 km")
     * beside a combined ride is a visible contradiction on the card.
     */
    private data class CollapsedRide(
        val mode: String,
        val distanceM: Long,
        /** Every leg's trace id, in time order — the hero concatenates them in exactly this order. */
        val traceIds: List<String>,
        /**
         * The ride's **last** leg, so [movementEndpoints] can ask it where the journey ended.
         *
         * ⚠⚠ The endpoints belong on this "must be recomputed" list exactly as the title and the
         * distance do, and were the one member of it that was missed (§36.5). Reading the *stops* off
         * [MergedMoment.representative] gives the **first** leg's, so a folded ride was named
         * `<first leg's origin> → <first leg's end>`: whole-journey length, intermediate destination.
         * Measured on the 2026-08-14 Mühldorf → München Ost journey, which rendered as
         * "Mühldorf (Oberbay) → Schwindegg · 82.5 km" — Schwindegg being the first station it passed.
         */
        val lastLeg: MomentInput,
        /**
         * The ride's **last** leg is the one still in progress (§12.6). A collapse that swallowed
         * the live leg without carrying this would silently un-mark the ongoing ride — the row
         * would go back to a finished-looking ride the moment a station stop appeared behind it.
         */
        val isCurrent: Boolean = false,
    )

    /**
     * Collapses runs of consecutive same-place `Visit` moments into one span, folding any short
     * `Movement` between them into the span (surfaced later as [Moment.containedMoves]). A run
     * breaks on: a time gap over [MERGE_MAX_GAP_MS], a `Movement` of [ABSORB_MOVE_MAX_M]+ (real
     * travel), a different place (coords over [SAME_PLACE_RADIUS_M] from the run's anchor, or a
     * different `subjectId` when coords are missing), or any non-visit moment. Non-visit moments
     * and unabsorbed movements pass through unchanged (`mergedCount == 1`).
     *
     * [ascending] must be sorted by `timestampMs`. Anchoring "same place" to the run's *first*
     * fragment bounds a merged span's spatial extent, so slow drift can't chain into one blob.
     */
    private fun mergeConsecutiveVisits(
        ascending: List<MomentInput>,
        places: List<PlaceInput>,
        trace: (MomentInput) -> TraceInput?,
    ): List<MergedMoment> {
        val coordById = places.associate { it.id to (it.latE7 to it.lngE7) }
        val result = mutableListOf<MergedMoment>()
        val group = mutableListOf<MomentInput>()        // current run of same-place visits
        val absorbed = mutableListOf<ContainedMove>()   // movements folded into the current run
        val pendingMoves = mutableListOf<MomentInput>() // movements seen since the last fragment

        fun emitPending() {
            pendingMoves.forEach { result.add(MergedMoment(it, it.timestampMs, endOf(it), 1, emptyList())) }
            pendingMoves.clear()
        }
        fun flushGroup() {
            if (group.isEmpty()) { emitPending(); return }
            val rep = group.maxByOrNull { it.durationSec } ?: group.first()
            result.add(
                MergedMoment(rep, group.minOf { it.timestampMs }, group.maxOf { endOf(it) }, group.size, absorbed.toList()),
            )
            group.clear(); absorbed.clear()
            emitPending() // movements after the run but before the element that broke it
        }

        for (m in ascending) {
            when (m.kind) {
                Kind.MOVEMENT -> pendingMoves.add(m)
                Kind.VISIT -> {
                    if (group.isEmpty()) {
                        emitPending()
                        group.add(m)
                    } else {
                        // Negative = the fragments overlap (two dwells for one stay) — merge those too.
                        val gapMs = m.timestampMs - group.maxOf { endOf(it) }
                        val canMerge = gapMs <= MERGE_MAX_GAP_MS &&
                            // ⚠ The in-progress leg is never absorbable. It normally cannot be —
                            // it is the newest moment, and nothing follows it to close a run — but
                            // a peer's synced visit can sort after it, and a swallowed live row
                            // would take the whole "you are moving right now" signal with it.
                            pendingMoves.none { it.id == ONGOING_MOVE_ID } &&
                            pendingMoves.all { moveDistanceM(it, trace) < ABSORB_MOVE_MAX_M } &&
                            sameArea(group.first(), m, coordById)
                        if (canMerge) {
                            pendingMoves.forEach { absorbed.add(containedMoveOf(it, trace)) }
                            pendingMoves.clear()
                            group.add(m)
                        } else {
                            flushGroup()
                            group.add(m)
                        }
                    }
                }
                else -> {
                    flushGroup()
                    result.add(MergedMoment(m, m.timestampMs, endOf(m), 1, emptyList()))
                }
            }
        }
        flushGroup()
        return result
    }

    private fun endOf(m: MomentInput): Long = m.timestampMs + m.durationSec * 1000

    /** A movement's path length; unknown ⇒ treated as far, so we never silently swallow a real trip. */
    private fun moveDistanceM(m: MomentInput, trace: (MomentInput) -> TraceInput?): Long =
        trace(m)?.distanceM ?: Long.MAX_VALUE

    private fun containedMoveOf(m: MomentInput, trace: (MomentInput) -> TraceInput?): ContainedMove {
        val t = trace(m)
        return ContainedMove(
            mode = t?.mode ?: MovementModes.UNKNOWN,
            distanceM = t?.distanceM ?: 0,
            durationMin = if (m.durationSec > 0) (m.durationSec / 60).toInt() else null,
        )
    }

    /** Same place iff coords are within [SAME_PLACE_RADIUS_M]; falling back to `subjectId` equality. */
    private fun sameArea(anchor: MomentInput, other: MomentInput, coordById: Map<String, Pair<Long, Long>>): Boolean {
        val a = coordById[anchor.subjectId]
        val b = coordById[other.subjectId]
        if (a != null && b != null && (a.first != 0L || a.second != 0L) && (b.first != 0L || b.second != 0L)) {
            return haversineM(a.first / 1e7, a.second / 1e7, b.first / 1e7, b.second / 1e7) <= SAME_PLACE_RADIUS_M
        }
        return anchor.subjectId.isNotEmpty() && anchor.subjectId == other.subjectId
    }

    // ── in-ride station-stop collapse (the dual of the visit merge) ───────────

    /**
     * How long an interposed stay may last and still read as *passing through* rather than *being
     * somewhere*.
     *
     * The useful window is narrow and bounded from below by capture: nothing shorter than
     * `VisitDetectionConfig.minDwellMs` (**5 min**) is ever recorded as a `Visit` at all, so this
     * constant only decides where in `[5 min, …)` the line falls. 12 min buys the shapes a station
     * stop actually takes — a terminus turnaround, a cross-platform connection, a held train — plus
     * headroom for the segmenter's departure-detection lag (the dwell is *confirmed* at 5 min and
     * its recorded duration runs to the first fix outside the anchor radius, so a 6-minute stop
     * routinely records as 8–9).
     *
     * Above it we deliberately take the false *negative*: a spurious station row is a small,
     * self-explanatory blemish, while swallowing a real 20-minute errand between two train legs
     * deletes something the user did. Same posture as `TransitKinematics` — bias hard toward the
     * status quo.
     */
    private const val STATION_STOP_MAX_MS = 12 * 60_000L

    /**
     * The same cap once the stop is **confirmed to be a station** — the arriving leg and the
     * departing leg name the *same* transit stop ([stationStopCapFor], TR-2 §38.4).
     *
     * ### Why the reasoning above changes when the station is known
     *
     * [STATION_STOP_MAX_MS]'s 12 minutes is set against *"swallowing a real 20-minute errand between
     * two train legs"* — a risk priced without knowing where the stop was. Naming the platform on
     * both sides does not remove that risk (one can leave a station and come back), but it moves the
     * question from *"was this an errand or a station?"* to *"how long does a train sit at a
     * platform?"* — and on single-track regional lines, where a crossing wait is scheduled rather
     * than exceptional, that runs past 12 minutes.
     *
     * ⚠ Measured, and it is why this exists: the reported Hörlkofen stop was **10.0 minutes** against
     * a 12-minute cap. The fold worked with two minutes to spare; a slightly longer crossing wait —
     * the same journey on a worse day — would have split the ride for a reason that has nothing to do
     * with the journey.
     *
     * ⚠⚠ **The weakest-reasoned constant in this phase, and it wants corpus calibration.** 20 minutes
     * is argued from operational holds rather than measured from stops, because the corpus has one
     * station wait in it. The guard that makes it survivable is that it applies *only* on positive
     * evidence — the `hasUserLabel` and `PlaceRole` vetoes still run, and a stop that accrues meaning
     * over time stops qualifying regardless of its length.
     */
    private const val CONFIRMED_STATION_STOP_MAX_MS = 20 * 60_000L

    /**
     * Two same-mode legs closer together than this, with no dwell between them, are one ride (§33.6).
     *
     * ⚠ Deliberately tight. Legs the segmenter emits from one flush are contiguous — 0 ms apart on the
     * measured journey — so this only has to tolerate rounding. A generous value would fold across a
     * capture hole, inventing a continuity nobody observed.
     */
    private const val ADJACENT_LEG_MAX_GAP_MS = 60_000L

    /**
     * Collapses `movement → short stop → movement` into a single ride, when the stop is a station
     * the user merely passed through (`timeline-movement-capture.md` §13.4).
     *
     * The dual of [mergeConsecutiveVisits], and read-time for the same reason it is: it is
     * reversible, it mutates nothing captured, and the underlying `MovementTrace`s stay intact so
     * `RailRefinementSweep` keeps refining each leg's classification on its own.
     *
     * **The problem.** `VisitDetectionConfig.minDwellMs` is 5 minutes and a train's station stop is
     * routinely exactly that, so the stop becomes a `Visit` **and** splits the journey into two
     * traces that nothing ever rejoins. The feed then reads ride → a "stay" at a platform the user
     * never left → ride. It also starves the classifier: `TransitKinematics`' stop-structure rule
     * counts well-spaced stops *within* one trace, so splitting a ride removes the very signal that
     * identifies it as transit (`TransitKinematics` KDoc, "what this tier cannot do").
     *
     * **Collapses only when every one of these holds** — each is a veto, and dropping any one of
     * them readmits a shape that is not a station stop:
     *  1. exactly one `Visit` sits between the two movements, contiguous within [ENDPOINT_MAX_GAP_MS]
     *     on both sides (the same tolerance [movementEndpoints] uses to call a visit a movement's
     *     endpoint — the question is literally the same one);
     *  2. **both movements resolve a trace whose mode is `transit`.** v1 is transit-only: a 5-minute
     *     stop during a *drive* is far more likely a real errand, and a walk/run/cycle has no station
     *     concept at all. An unresolvable trace counts as not-transit, mirroring [moveDistanceM]'s
     *     "unknown ⇒ never silently swallow". ⚠ Since TR-3 this constrains the two **ends** of a
     *     span; a `drive` leg may sit *between* them under the separate, stricter guards in
     *     [isBridgeableLeg], and nothing else may;
     *  3. the stay is at most [STATION_STOP_MAX_MS] long — or [CONFIRMED_STATION_STOP_MAX_MS] when
     *     the legs either side name the same transit stop, which is the one thing that establishes
     *     *where* the stop was (TR-2, [stationStopCapFor]);
     *  4. the stop's place is **not user-meaningful** — no user-authored label
     *     ([PlaceInput.hasUserLabel]) and no `PlaceRole` row of any confidence ([rolePlaceIds]).
     *     ⚠ A place we cannot *find* (dangling id, or no `subjectId` at all) is treated as
     *     meaningful: we cannot prove it is a nowhere, so it survives. This guard is the one that
     *     makes the feature safe, and it fails closed;
     *  5. the stay is not the user's *current* one — swallowing "you are here now" would be the
     *     worst available failure, and an ongoing stay that is also mid-ride is contradictory state.
     *
     * ⚠ A collapsed stop's own absorbed short movements (§2b `containedMoves` — walking between
     * platforms) go with it. That is intentional: they describe the inside of a stop the row no
     * longer shows, and the underlying moments are untouched.
     */
    private fun collapseTransitStationStops(
        merged: List<MergedMoment>,
        places: List<PlaceInput>,
        trace: (MomentInput) -> TraceInput?,
        rolePlaceIds: Set<String>,
        ongoingArrivalMs: Long?,
    ): List<MergedMoment> {
        val placeById = places.associateBy { it.id }
        val out = mutableListOf<MergedMoment>()
        var i = 0
        while (i < merged.size) {
            val head = merged[i]
            val mode = transitModeOf(head, trace)
            if (mode == null) { out.add(head); i++; continue }
            // Walk forward for as long as every guard holds, over ANY of three shapes:
            //
            //   leg → stop → leg   (§33.6, shape 1) the pass-through station stop this was built for
            //   leg → leg          (§33.6, shape 2) two legs the segmenter emitted back to back
            //   leg → drive → leg  (§38.4, shape 3) a leg of the same ride the map has not vouched
            //                      for yet — see [isBridgeableLeg] for why that qualifier is the
            //                      difference between closing a gap and overruling the map
            //
            // ⚠⚠ The second shape is not hypothetical and is not rare. `ModeCoalescer` decides
            // whether to join runs at CAPTURE time, before any tile is available, so a ride whose
            // runs classify as `drive` then `transit` is split — and the rail sweep, which later
            // agrees they are both `transit`, can relabel a trace but has no way to un-split one.
            // Measured on the 2026-08-14 Mühldorf → München Ost journey: one flush emitted
            // `drive 17516m` + `transit 35084m`, the sweep refined both to `transit` 17 minutes
            // later, and the ride rendered as two rows because the old walk could only step over a
            // VISIT. Folding here makes the capture-time decision harmless rather than permanent.
            var last = i
            val legIndices = mutableListOf(i)
            while (true) {
                val nextIndex = last + 1
                if (nextIndex >= merged.size) break
                val candidate = merged[nextIndex]

                // ── shape 2: the very next entry is itself a leg of the same mode ──
                if (transitModeOf(candidate, trace) == mode) {
                    // ⚠ Bounded by a tight gap, not by [ENDPOINT_MAX_GAP_MS]. Legs from one flush are
                    // contiguous (0 ms apart); a longer hole means capture missed something, and
                    // folding across it would invent a continuity nobody observed.
                    if (candidate.startMs - merged[last].endMs > ADJACENT_LEG_MAX_GAP_MS) break
                    // §32.12 still applies: a change of vehicle is two rows, stop or no stop.
                    if (isVehicleChange(merged[last], candidate, trace)) break
                    legIndices += nextIndex
                    last = nextIndex
                    continue
                }

                // ── shapes 1 and 3: leg → something → leg ──
                //
                //   leg → stop → leg   (1) the pass-through station stop this was built for
                //   leg → drive → leg  (3, TR-1's sibling, §38.4) a leg of the same ride the map has
                //                      not yet vouched for
                val afterIndex = last + 2
                if (afterIndex >= merged.size) break
                val next = merged[afterIndex]
                if (transitModeOf(next, trace) != mode) break
                // §32.12: a transfer is not a pass-through, however station-like the stop looks.
                if (isVehicleChange(merged[last], next, trace)) break
                val bridged = if (candidate.representative.kind == Kind.VISIT) {
                    isPassThroughStop(
                        candidate, merged[last], next, placeById, rolePlaceIds, ongoingArrivalMs, trace,
                    )
                } else {
                    isBridgeableLeg(candidate, merged[last], next, trace)
                }
                if (!bridged) break
                // ⚠ A bridged *movement* carries distance and a trace id; a bridged stop does not.
                // Adding it keeps the ride's length honest — leaving it out would report a 60 km
                // journey as 52 km — and puts its geometry on the route the row draws.
                if (candidate.representative.kind != Kind.VISIT) legIndices += nextIndex
                legIndices += afterIndex
                last = afterIndex
            }
            if (legIndices.size == 1) { out.add(head); i++; continue }
            // ⚠ Collected as we walk rather than re-derived as `(i..last step 2)`: with two shapes
            // the stride is no longer constant, and that expression would silently take the wrong
            // entries — a stop counted as a leg, and a leg dropped from the distance.
            val legs = legIndices.map { merged[it] }
            out.add(
                MergedMoment(
                    representative = head.representative,
                    startMs = head.startMs,
                    endMs = merged[last].endMs,
                    mergedCount = 1,
                    contained = emptyList(),
                    ride = CollapsedRide(
                        mode = mode,
                        distanceM = legs.sumOf { trace(it.representative)?.distanceM ?: 0L },
                        // The live leg has no fetchable trace — same reason as [moment]'s.
                        traceIds = legs.map { it.representative.subjectId }.filter { it != ONGOING_TRACE_ID },
                        lastLeg = legs.last().representative,
                        isCurrent = legs.last().representative.id == ONGOING_MOVE_ID,
                    ),
                ),
            )
            i = last + 1
        }
        return out
    }

    /** A movement whose trace resolves to `transit`; `null` for anything else (guard 2). */
    private fun transitModeOf(mm: MergedMoment, trace: (MomentInput) -> TraceInput?): String? {
        if (mm.representative.kind != Kind.MOVEMENT) return null
        return trace(mm.representative)?.mode?.takeIf { it == MovementModes.TRANSIT }
    }

    /**
     * Did this leg run **underground**? `null` when nothing says either way.
     *
     * Both a metro hop and the surface train beside it are `transit`, so the mode cannot separate
     * them. The classifier version can: `UNDERGROUND` is stamped at capture by the §27 frozen-anchor
     * test, and `RAIL_REFINED_UNDERGROUND` by the sweep once the blind interval's endpoints are
     * confirmed on subway alignment. Everything else that reached `transit` did so on surface
     * evidence.
     */
    private fun ranUndergroundOf(mm: MergedMoment, trace: (MomentInput) -> TraceInput?): Boolean? =
        when (val version = trace(mm.representative)?.classifierVersion) {
            null, 0 -> null
            MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND,
            MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND,
            -> true
            else -> false
        }

    /**
     * **A change of vehicle is two rows** (§32.12, maintainer decision).
     *
     * `collapseTransitStationStops` folds a stop the user *passed through without leaving the
     * vehicle*. A **transfer** is the opposite — they got off one and onto another — and folding it
     * hides the interchange entirely: measured on the reported journey with an ordinary 6-minute
     * connection wait, train + wait + U-Bahn became one `Transit · 74.2 km` row and the München Ost
     * row was deleted.
     *
     * ⚠ Fails toward folding, deliberately. Only a **positive disagreement** blocks: one leg known
     * underground and the other known not. Unknown on either side keeps today's behaviour, so a leg
     * the sweep has not reached yet never changes the feed it was already producing.
     *
     * ⚠ Known gap, accepted with the decision: a train → train interchange is surface on both sides
     * and still folds. The kinds the sweep matches (`kinds=[subway, rail]`) would separate it, but
     * they live on `SnappedRoute` and persisting them reshapes a stored composite. Revisit if and
     * when the "condense" display toggle lands — that toggle wants the un-collapsed data anyway.
     */
    private fun isVehicleChange(
        before: MergedMoment,
        after: MergedMoment,
        trace: (MomentInput) -> TraceInput?,
    ): Boolean {
        val a = ranUndergroundOf(before, trace) ?: return false
        val b = ranUndergroundOf(after, trace) ?: return false
        return a != b
    }

    /**
     * **Shape 3** — may a `drive` leg between two `transit` legs be read as part of the same ride?
     * (TR-3, `timeline-movement-capture.md` §38.4.)
     *
     * ### The gap this closes
     *
     * Reassembly requires *N* independent classifications to **agree**: `ModeCoalescer` joins runs
     * only on equal modes, and shape 2 above folds only same-mode legs. So one leg of a journey
     * landing on `drive` — because `TransitEvidence.usableLegCount` fell under `MIN_TRANSIT_LEGS` on
     * a short stretch, or because the sweep has not reached it yet — splits the whole journey.
     * Measured on the 2026-09-01 Mühldorf ride: `transit 30.0 km`, `drive 8.7 km`, `transit 21.8 km`,
     * contiguous to the millisecond, rendered as three rows.
     *
     * ### ⚠⚠ Why this does not override the map
     *
     * `classifierVersion >= MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED` means **the map looked and said
     * this is not rail** — either confirming a drive or walking a `transit` back. Bridging that would
     * take the one verdict the tier stack lets the map *demote* and quietly undo it at read time.
     * So the bridge applies only while the map has not ruled: not yet swept, or swept with no tiles
     * available (`refine` leaves the version unadvanced precisely so "retry later" is expressible).
     *
     * ⚠ The honest consequence, accepted: a ride folded before the sweep can split again after it, if
     * the map then refuses the middle. That is new evidence changing an answer, which is the whole
     * point of the tier order — not a flapping UI to be damped.
     *
     * ### The guards, each of which fails closed
     *
     *  - the middle is a **movement**, and one whose trace resolves — unknown ⇒ never swallow, the
     *    same posture [moveDistanceM] takes;
     *  - its mode is **`drive`** and nothing else. `cycle`/`walk`/`run` are exactly the shapes a real
     *    "got off and pedalled" journey has, and bridging one would delete a genuine leg;
     *  - it is contiguous with both neighbours within [ADJACENT_LEG_MAX_GAP_MS]. ⭐ This is the
     *    load-bearing one: **changing vehicle requires stopping**, so legs with no gap between them
     *    cannot be two vehicles, whatever their modes say;
     *  - it is **shorter than the confirmed rail on either side of it combined**. The fold claims the
     *    whole span was one transit ride; if the unvouched-for middle outweighed the legs the map did
     *    confirm, the row would assert more than its evidence supports. ⚠ Reasoned, not fitted — it
     *    is a statement about where the evidence sits, and it is what stops a 2 km + 80 km + 2 km
     *    train-taxi-train from becoming one 84 km "Transit".
     */
    private fun isBridgeableLeg(
        middle: MergedMoment,
        before: MergedMoment,
        after: MergedMoment,
        trace: (MomentInput) -> TraceInput?,
    ): Boolean {
        if (middle.representative.kind != Kind.MOVEMENT) return false
        val middleTrace = trace(middle.representative) ?: return false
        if (middleTrace.mode != MovementModes.DRIVE) return false
        if (middleTrace.classifierVersion >= MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED) return false
        if (middle.startMs - before.endMs > ADJACENT_LEG_MAX_GAP_MS) return false
        if (after.startMs - middle.endMs > ADJACENT_LEG_MAX_GAP_MS) return false
        val confirmedM = (trace(before.representative)?.distanceM ?: 0L) +
            (trace(after.representative)?.distanceM ?: 0L)
        return middleTrace.distanceM <= confirmedM
    }

    /**
     * How long this stop may last and still read as a pass-through (TR-2, §38.4).
     *
     * ### ⭐ The station is already known, and nothing was reading it
     *
     * `TimelineProjectionRepository.withTransitStops` resolves every transit leg's `originStop` and
     * `destinationStop` from the tiles' POI layer (§32.14). So when the leg **arriving** names the
     * same stop the leg **departing** names, the stay between them is *that station* — established
     * without a new lookup, a new cache, or a byte of extra egress, all of which a fresh
     * `nearestTransitStop` call on the stay would have cost.
     *
     * ⚠⚠ **This may only ever widen.** Blank is returned for *both* "no stop nearby" and "could not
     * tell" (§36.6a), and a leg whose endpoint sits mid-line between two stations is legitimately
     * nameless and very common — so requiring station evidence would stop folding the ordinary case.
     * Unknown is not elsewhere: no evidence ⇒ [STATION_STOP_MAX_MS] ⇒ exactly today's behaviour.
     *
     * ⚠ Two **different** names still refuse. That is what keeps this evidence about *this stay*
     * rather than about the legs: a different station either side means the traveller covered ground
     * during the stop, which is not a pass-through however station-like both ends look.
     *
     * ### ⚠⚠ §39.8 — one named end and one blank end is NOT a contradiction
     *
     * This asked both ends to name the station, which reads a blank as disagreement — and the
     * paragraph above already says why it is not one. Blank means *no stop within
     * `TRANSIT_STOP_RADIUS_M`*; it is silence, not a competing claim, so pairing it with a name is
     * one confirmation and one unknown.
     *
     * **Measured on the 2026-09-07 Mühldorf → München Ost ride**, where the 16.5-minute Schwindegg
     * crossing wait split the journey:
     *
     * | endpoint | distance to the station | resolves to |
     * |---|---|---|
     * | arriving leg's **end** | **217 m** | `Schwindegg` |
     * | departing leg's **start** | **531 m** | `""` |
     *
     * The departing endpoint is a fix reporting **±300 m accuracy**, kept as a terminal fix by §32.6
     * precisely because dropping it would truncate the journey. So the ride was split by a coordinate
     * missing the radius by 231 m — less than that fix's own stated uncertainty — while the other end
     * named the platform from 217 m away.
     *
     * ⚠ Deliberately **not** fixed by widening `TRANSIT_STOP_RADIUS_M` or by raising either cap. The
     * radius' own calibration records 300 m as where the first false positive appears (an office sits
     * 252 m from a station), and raising the caps would price an *unexplained* stop the same as a
     * *confirmed* one — which is the distinction the two constants exist to draw.
     *
     * ⚠ What still bounds this: the stay is a `Visit`, so the phone did not move during it, and the
     * `hasUserLabel` / `PlaceRole` vetoes and the mode and vehicle-change guards all still run.
     */
    private fun stationStopCapFor(
        before: MergedMoment,
        after: MergedMoment,
        trace: (MomentInput) -> TraceInput?,
    ): Long {
        val arriving = trace(before.representative)?.destinationStop.orEmpty()
        val departing = trace(after.representative)?.originStop.orEmpty()
        return when {
            // Neither end can name a stop — no evidence at all, so exactly the pre-TR-2 behaviour.
            arriving.isEmpty() && departing.isEmpty() -> STATION_STOP_MAX_MS
            // One names the platform, the other is silent. Unknown is not elsewhere (§39.8).
            arriving.isEmpty() || departing.isEmpty() -> CONFIRMED_STATION_STOP_MAX_MS
            // Both spoke, so they have to agree.
            arriving == departing -> CONFIRMED_STATION_STOP_MAX_MS
            else -> STATION_STOP_MAX_MS
        }
    }

    /** Guards 1, 3, 4 and 5 of [collapseTransitStationStops]; every one of them fails closed. */
    private fun isPassThroughStop(
        stop: MergedMoment,
        before: MergedMoment,
        after: MergedMoment,
        placeById: Map<String, PlaceInput>,
        rolePlaceIds: Set<String>,
        ongoingArrivalMs: Long?,
        trace: (MomentInput) -> TraceInput?,
    ): Boolean {
        val rep = stop.representative
        if (rep.kind != Kind.VISIT) return false
        if (stop.endMs - stop.startMs > stationStopCapFor(before, after, trace)) return false
        if (stop.startMs - before.endMs > ENDPOINT_MAX_GAP_MS) return false
        if (after.startMs - stop.endMs > ENDPOINT_MAX_GAP_MS) return false
        if (ongoingArrivalMs != null && stop.startMs == ongoingArrivalMs) return false
        val placeId = rep.subjectId.takeIf { rep.subjectKind == Subject.PLACE && it.isNotEmpty() } ?: return false
        if (placeId in rolePlaceIds) return false
        // Unknown place ⇒ unprovable ⇒ meaningful. Fail closed.
        val place = placeById[placeId] ?: return false
        return !place.hasUserLabel
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(s)))
    }

    /**
     * Maps a [MergedMoment] to its UI [Moment]. An un-merged one (`mergedCount == 1`, nothing
     * absorbed) goes through [moment] unchanged — preserving movement mode/distance and every other
     * behaviour; a merged visit spans the whole window and carries its folded movements.
     */

    /**
     * The photos belonging to a span, best first, capped at [PhotoSelection.MAX_PER_MOMENT].
     *
     * ⚠ The window is the **merged** span, not the representative fragment's duration — a stay the
     * capture layer split into three rows presents as one moment, so it must claim the photos taken
     * across the whole stay. The same [PhotoSelection.window] rule (floored, half-open) applies, so
     * a moment and its neighbour cannot both claim a photo taken exactly on their boundary.
     *
     * ⚠ Ordering repeats the proxy's: a photograph you took ahead of an image that merely shares
     * the minute, then newest. It matters again here because the cap is applied after it — a stay
     * with forty screenshots and two photographs shows the photographs.
     */
    internal fun photosWithin(
        photos: List<PhotoInput>,
        startMs: Long,
        endMs: Long,
        /** Where the moment happened, E7; `null` for a moment with no place (T-P4). */
        at: Pair<Long, Long>? = null,
    ): List<Photo> {
        if (photos.isEmpty()) return emptyList()
        val window = PhotoSelection.window(
            startMs = startMs,
            durationSec = ((endMs - startMs).coerceAtLeast(0)) / 1_000L,
            minWindowMs = PhotoSelection.MIN_WINDOW_MS,
        )
        return photos.asSequence()
            .filter { it.capturedAtMs in window }
            // ⚠⚠ T-P4 — the one place a *wrong* photo gets dropped rather than merely out-ranked.
            // A photo that says where it was taken and says somewhere else was not taken at this
            // moment, whatever the clock says: that is a screenshot saved during a visit, or a
            // photo received in a chat. A photo with no coordinate is kept, because "unknown" is
            // the common case and must never be read as "elsewhere".
            .filter { at == null || !it.hasCoordinate || nearby(it, at) }
            .sortedWith(
                // Photographs taken *here* first — the coordinate is stronger evidence than the
                // folder a file happens to sit in — then provenance, then newest.
                compareByDescending<PhotoInput> { at != null && it.hasCoordinate && nearby(it, at) }
                    .thenByDescending { it.backedUp }
                    .thenByDescending { it.capturedAtMs }
            )
            .take(PhotoSelection.MAX_PER_MOMENT)
            .map { Photo(dominantColor = PhotoSelection.PLACEHOLDER_COLOR, uri = it.path) }
            .toList()
    }


    /**
     * Whether a photo was taken close enough to a moment's place to belong to it.
     *
     * A generous radius on purpose ([PhotoSelection.NEARBY_RADIUS_M]): a visit's coordinate is a
     * dwell cluster's centre and a photo may be taken from across the square. What it excludes is
     * the *other side of town* — which is the whole point, since the alternative is time alone.
     *
     * Equirectangular rather than haversine: at these distances the error is centimetres, and this
     * runs per photo per moment.
     */
    private fun nearby(photo: PhotoInput, at: Pair<Long, Long>): Boolean {
        val latDegrees = (photo.latE7 - at.first) / 1e7
        val lngDegrees = (photo.lngE7 - at.second) / 1e7
        val latMetres = latDegrees * METRES_PER_DEGREE
        val lngMetres = lngDegrees * METRES_PER_DEGREE *
            kotlin.math.cos((at.first / 1e7) * kotlin.math.PI / 180.0)
        return latMetres * latMetres + lngMetres * lngMetres <=
            PhotoSelection.NEARBY_RADIUS_M.toDouble() * PhotoSelection.NEARBY_RADIUS_M
    }

    private const val METRES_PER_DEGREE = 111_320.0

    /** How many photos fall inside a span — the count an aggregate row shows, uncapped. */
    internal fun photoCountWithin(photos: List<PhotoInput>, startMs: Long, endMs: Long): Int {
        if (photos.isEmpty()) return 0
        val window = PhotoSelection.window(
            startMs = startMs,
            durationSec = ((endMs - startMs).coerceAtLeast(0)) / 1_000L,
            minWindowMs = PhotoSelection.MIN_WINDOW_MS,
        )
        return photos.count { it.capturedAtMs in window }
    }

    /** One moment's own span, from its stored start + duration. */
    internal fun spanOf(m: MomentInput): Pair<Long, Long> =
        m.timestampMs to (m.timestampMs + m.durationSec * 1_000L)

    private fun mergedToUi(
        mm: MergedMoment,
        subtitle: (MomentInput) -> String,
        zone: TimeZone,
        ongoingArrivalMs: Long?,
        nowMs: Long,
        trace: (MomentInput) -> TraceInput?,
        coords: (MomentInput) -> Pair<Long, Long>?,
        /** Only ever populated for a movement, which is never itself a merge target (visits are). */
        endpoints: Pair<Endpoint?, Endpoint?> = NO_ENDPOINTS,
        /** `placeId → confirmed role token`; a Visit stamps its own, a Movement its two ends. */
        roles: Map<String, String> = emptyMap(),
    ): Moment {
        // ⚠ Ahead of the un-merged fast path: a collapsed ride also reports `mergedCount == 1`, and
        // falling through would render the FIRST LEG's stored title + distance beside the whole ride.
        mm.ride?.let { return collapsedRideToUi(mm, it, zone, endpoints) }
        if (mm.mergedCount == 1 && mm.contained.isEmpty()) {
            return moment(
                mm.representative, subtitle, zone, ongoingArrivalMs, nowMs, trace, coords,
                endpoints, roles,
            )
        }
        val rep = mm.representative
        val local = Instant.fromEpochMilliseconds(mm.startMs).toLocalDateTime(zone)
        val isCurrent = ongoingArrivalMs != null && rep.kind == Kind.VISIT && mm.startMs == ongoingArrivalMs
        val durationMin = when {
            isCurrent -> ((nowMs - mm.startMs).coerceAtLeast(0) / 60_000L).toInt()
            mm.endMs > mm.startMs -> ((mm.endMs - mm.startMs) / 60_000L).toInt()
            else -> null
        }
        val placeId = rep.subjectId.takeIf { rep.subjectKind == Subject.PLACE && it.isNotEmpty() }
        val coord = if (placeId != null) coords(rep) else null
        return Moment(
            id = rep.id,
            time = twoDigit(local.hour) + ":" + twoDigit(local.minute),
            title = rep.title,
            place = subtitle(rep),
            kind = momentKind(rep.kind),
            durationMin = durationMin,
            photos = emptyList(),
            isCurrent = isCurrent,
            cover = null,
            movementMode = null,
            distanceM = null,
            placeId = placeId,
            placeRole = placeId?.let { roles[it] },
            latE7 = coord?.first ?: 0,
            lngE7 = coord?.second ?: 0,
            mergedStayCount = mm.mergedCount,
            containedMoves = mm.contained,
        )
    }

    /**
     * The UI row for a ride whose in-ride station stops were collapsed ([collapseTransitStationStops]).
     *
     * ⚠⚠ **The title is regenerated, never inherited.** A `MOVEMENT` moment stores its title as
     * e.g. `"Train · 34.2 km"` and the card renders that string *next to* the mode + distance meta —
     * so keeping the first leg's title would put one leg's distance on screen beside the combined
     * ride. [movementTitle] is the same generator the capture side uses, so a collapsed
     * ride and a natively-whole one produce byte-identical titles.
     *
     * Duration spans the whole journey — station stops included, which is what a train ride's
     * duration means, and for a ride still in progress it runs to *now* (the live leg's window ends
     * there by construction, [ongoingMoveRows]).
     *
     * `isCurrent` comes from the ride, not the stay: an ongoing *stay* is never collapsed (guard 5),
     * but an ongoing *ride* very much can be — a train that has already made one station stop is
     * exactly the shape this collapse exists for.
     */
    private fun collapsedRideToUi(
        mm: MergedMoment,
        ride: CollapsedRide,
        zone: TimeZone,
        endpoints: Pair<Endpoint?, Endpoint?>,
    ): Moment {
        val local = Instant.fromEpochMilliseconds(mm.startMs).toLocalDateTime(zone)
        return Moment(
            id = mm.representative.id,
            time = twoDigit(local.hour) + ":" + twoDigit(local.minute),
            title = movementTitle(ride.mode, ride.distanceM),
            place = "",
            kind = MomentKind.Movement,
            durationMin = ((mm.endMs - mm.startMs) / 60_000L).toInt().takeIf { it > 0 },
            photos = emptyList(),
            isCurrent = ride.isCurrent,
            cover = null,
            movementMode = ride.mode,
            distanceM = ride.distanceM,
            originPlace = endpoints.first?.name,
            destinationPlace = endpoints.second?.name,
            originRole = endpoints.first?.role,
            destinationRole = endpoints.second?.role,
            traceId = ride.traceIds.firstOrNull(),
            traceIds = ride.traceIds,
        )
    }

    /**
     * A place, with its visit history derived from the `Visit`-moments that point at it
     * ([visits] — the caller filters `subjectKind == PLACE && subjectId == place.id`). Counts,
     * first/last-visit labels, average duration and the per-visit rows all fall out of those
     * moments; the mock hand-authored them.
     */
    fun place(
        p: PlaceInput,
        visits: List<MomentInput> = emptyList(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
        /** Photos across the visits' span (T-P3) — each visit claims its own window's. */
        photos: List<PhotoInput> = emptyList(),
    ): Place {
        val newestFirst = visits.sortedByDescending { it.timestampMs }
        return Place(
            id = p.id,
            name = p.label,
            // City-level is all geocoding stores (G4); a finer neighborhood is a later provider.
            neighborhood = "",
            city = p.city,
            visitsCount = visits.size,
            // Summed per visit rather than counted over the place's whole date range: two visits
            // months apart must not claim everything photographed in between.
            photosCount = visits.sumOf { v -> spanOf(v).let { photoCountWithin(photos, it.first, it.second) } },
            firstVisit = visits.minByOrNull { it.timestampMs }?.let { monthYear(it.timestampMs, zone) } ?: "",
            lastVisit = newestFirst.firstOrNull()?.let { monthDay(it.timestampMs, zone) } ?: "",
            cover = Photo(dominantColor = DEFAULT_COLOR),
            tags = emptyList(),
            avgDuration = averageDuration(visits),
            history = newestFirst.map { placeVisit(it, zone, photos) },
            latE7 = p.latE7,
            lngE7 = p.lngE7,
        )
    }

    /**
     * A trip, projected from its [membership][TripInput.membership] over the moment/place/workout
     * stores (`docs/proposals/timeline-services.md` §5). The `Trip.contents` walker the UI consumes
     * is derived here — never stored: member moments are grouped into ascending **day-chapters**
     * ([TripEntry.DayEntry], "Day 1"…) for a multi-day trip, or listed flat ([TripEntry.MomentItem])
     * for a single-day one. Counts (`daysSpan` / distinct-place `placesCount` / `momentsCount`) and
     * the ordered Visit-place [route][Trip.route] all fall out of the members; a member id with no
     * matching moment (dangling) is dropped. `photosCount` stays an honest `0` until photos land.
     *
     * Nested-trip recursion and `Collapsed` markers are **not** produced: the `Trip` scaffold carries
     * no nested-trip ids yet (deferred slice, `Trip` KDoc), so v1 renders a flat chapter list.
     */
    fun trip(
        t: TripInput,
        moments: List<MomentInput> = emptyList(),
        places: List<PlaceInput> = emptyList(),
        workouts: List<WorkoutInput> = emptyList(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
        /** Photos across the trip's span (T-P3) — counted per member moment, never per date range. */
        photos: List<PhotoInput> = emptyList(),
    ): Trip {
        val memberIds = t.membership.toSet()
        // Members, oldest first — chronology drives day chapters, route order and day numbering.
        val members = moments.filter { it.id in memberIds }.sortedBy { it.timestampMs }
        val subtitle = subtitleResolver(places, workouts)
        val byDay = members.groupBy { localDate(it.timestampMs, zone) }.toList().sortedBy { it.first }

        val contents: List<TripEntry> = when {
            byDay.isEmpty() -> emptyList()
            // Single-day trips list their moments flat (matches the mock's day-trip shape).
            byDay.size == 1 -> byDay.single().second.map { TripEntry.MomentItem(moment(it, subtitle, zone)) }
            else -> byDay.mapIndexed { i, (date, dayMoments) ->
                TripEntry.DayEntry(
                    dayNum = i + 1,
                    dayLabel = "Day ${i + 1}",
                    dateLong = dayShort(date),
                    moments = dayMoments.map { moment(it, subtitle, zone) },
                )
            }
        }

        val placesCount = members
            .filter { it.subjectKind == Subject.PLACE }
            .map { it.subjectId }
            .distinct()
            .count()

        return Trip(
            id = t.id,
            title = t.title,
            dateRange = t.dateRange,
            daysSpan = byDay.size,
            placesCount = placesCount,
            // Per member moment, not over the trip's date range: a trip is the moments it contains,
            // and the hours between them are somebody else's (or nobody's).
            photosCount = members.sumOf { m -> spanOf(m).let { photoCountWithin(photos, it.first, it.second) } },
            cover = Photo(dominantColor = DEFAULT_COLOR),
            year = t.year.ifEmpty { null },
            momentsCount = members.size,
            tags = emptyList(),
            contents = contents,
            route = route(members, places),
        )
    }

    /**
     * The trip's ordered route — its member `Visit` moments' place coordinates, chronological, with
     * consecutive duplicates collapsed and coordinate-less places dropped (`maps-region-delivery.md`
     * R6). Fewer than two points ⇒ empty (the UI renders nothing).
     */
    private fun route(members: List<MomentInput>, places: List<PlaceInput>): List<GeoPoint> {
        val coordById = places.associate { it.id to (it.latE7 to it.lngE7) }
        val points = members.asSequence()
            .filter { it.subjectKind == Subject.PLACE }
            .mapNotNull { coordById[it.subjectId] }
            .filter { (lat, lng) -> lat != 0L || lng != 0L }
            .map { (lat, lng) -> GeoPoint(latitude = lat / 1e7, longitude = lng / 1e7) }
            .fold(mutableListOf<GeoPoint>()) { acc, p ->
                if (acc.lastOrNull() != p) acc.add(p)
                acc
            }
        return if (points.size >= 2) points else emptyList()
    }

    /** Aggregates workouts into per-activity-type roll-ups, most sessions first. */
    /**
     * Presentation rollups from the **maintained** per-activity counts. The counts arrive
     * precomputed (one row per activity type) instead of being derived by grouping the workout
     * table: this is a lifetime aggregate, and grouping every workout is the read that stopped
     * completing after a full history import (`fit-read-scaling.md` Ph3/4).
     *
     * Empty buckets are dropped — a type whose workouts were all deleted decrements to zero and
     * should leave the surface, not linger as a "0 sessions" card.
     */
    fun activityRollups(counts: List<ActivityCountInput>): List<ActivityRollup> =
        counts
            .filter { it.count > 0 }
            .map { (type, count) ->
                ActivityRollup(
                    kind = type,
                    label = type,
                    icon = type.lowercase(),
                    count = count,
                    suffix = "sessions",
                    secondary = "",
                    lastDate = "",
                    dominantColor = DEFAULT_COLOR,
                )
            }
            .sortedByDescending { it.count }

    // ── subject hydration ─────────────────────────────────────────────────────

    /**
     * The uniform `resolveSubject` dispatch (§5.1) — turns a moment's `{subjectKind, subjectId}`
     * into its presentation subtitle: a place name for a `Visit`, the workout's activity for an
     * `Activity`, `""` for a subject-less moment or a dangling reference.
     */
    private fun subtitleResolver(
        places: List<PlaceInput>,
        workouts: List<WorkoutInput>,
    ): (MomentInput) -> String {
        val placeLabels = places.associate { it.id to it.label }
        val workoutLabels = workouts.associate { it.id to it.activityType }
        return { m ->
            when (m.subjectKind) {
                Subject.PLACE -> placeLabels[m.subjectId].orEmpty()
                Subject.WORKOUT -> workoutLabels[m.subjectId].orEmpty()
                else -> ""
            }
        }
    }

    /**
     * The trace half of the same `resolveSubject` dispatch — a `MOVEMENT` moment's `{TRACE, id}`
     * into the mode + distance its card renders. A dangling reference resolves to `null`, matching
     * the subtitle resolver's `""` contract, so an unresolvable subject degrades instead of failing.
     */
    private fun traceResolver(traces: List<TraceInput>): (MomentInput) -> TraceInput? {
        val byId = traces.associateBy { it.id }
        return { m -> if (m.subjectKind == Subject.TRACE) byId[m.subjectId] else null }
    }

    private fun placeVisit(
        m: MomentInput,
        zone: TimeZone,
        photos: List<PhotoInput> = emptyList(),
    ): PlaceVisit {
        val local = Instant.fromEpochMilliseconds(m.timestampMs).toLocalDateTime(zone)
        return PlaceVisit(
            id = m.id,
            date = monthDay(m.timestampMs, zone),
            time = twoDigit(local.hour) + ":" + twoDigit(local.minute),
            label = m.title,
            duration = formatDuration(m.durationSec),
            photosCount = spanOf(m).let { photoCountWithin(photos, it.first, it.second) },
            photos = spanOf(m).let { photosWithin(photos, it.first, it.second) },
        )
    }

    /** Mean of the visit durations, formatted; `null` when no visit carries a duration. */
    private fun averageDuration(visits: List<MomentInput>): String? {
        val durations = visits.map { it.durationSec }.filter { it > 0 }
        if (durations.isEmpty()) return null
        return formatDuration(durations.sum() / durations.size)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    fun momentKind(raw: String): MomentKind = when (raw) {
        Kind.ACTIVITY -> MomentKind.Activity
        Kind.VISIT -> MomentKind.Visit
        Kind.QUIET -> MomentKind.Quiet
        Kind.MOVEMENT -> MomentKind.Movement
        // An unknown kind from a newer peer still renders, as the generic Event anatomy.
        else -> MomentKind.Event
    }

    private fun localDate(ms: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date

    private fun dateLong(date: LocalDate): String =
        titleCase(date.dayOfWeek.name) + ", " + titleCase(date.month.name) + " " + date.day

    /** "Sat · Apr 12" — the trip day-chapter header (weekday abbrev · month abbrev + day). */
    private fun dayShort(date: LocalDate): String =
        titleCase(date.dayOfWeek.name.take(3)) + " · " + monthAbbrev(date) + " " + date.day

    /** "Apr 7" — the month abbreviation + day-of-month. */
    private fun monthDay(ms: Long, zone: TimeZone): String {
        val date = localDate(ms, zone)
        return monthAbbrev(date) + " " + date.day
    }

    /** "Apr 2026" — the month abbreviation + year. */
    private fun monthYear(ms: Long, zone: TimeZone): String {
        val date = localDate(ms, zone)
        return monthAbbrev(date) + " " + date.year
    }

    private fun monthAbbrev(date: LocalDate): String =
        titleCase(date.month.name.take(3))

    /** "1 h 22 min" / "2 h" / "45 min" — matches the mock's duration style. */
    private fun formatDuration(sec: Long): String {
        val totalMin = (sec / 60).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            else -> "$m min"
        }
    }

    private fun twoDigit(n: Int): String = if (n < 10) "0$n" else n.toString()

    private fun titleCase(enumName: String): String =
        enumName.lowercase().replaceFirstChar { it.uppercase() }
}
