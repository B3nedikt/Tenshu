@file:OptIn(ExperimentalJsExport::class, ExperimentalCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.error.ErrorCopyKey
import app.tenshu.error.errorCopyKey
import app.tenshu.sdk.internal.failureResult
import app.tenshu.timeline.data.projection.TimelineProjection
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.equalTo
import org.tenshu.core.fold
import app.tenshu.sdk.internal.WorkoutChanges
import org.tenshu.core.number.TInteger
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.PlaceRoles
import org.tenshu.core.place.RoleSource
import org.tenshu.core.resolve
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.timeline.MomentSource
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TripMember
import org.tenshu.health.client.healthService
import org.tenshu.place.client.placeService
import org.tenshu.timeline.client.timelineService
import org.tenshu.timeline.common.MomentReconciler
import org.tenshu.timeline.common.ReconcilableMoment
import org.tenshu.timeline.common.TripSuggester
import org.tenshu.timeline.common.TripSuggestion
import org.tenshu.timeline.common.TripSuggestionInput
import org.tenshu.core.health.Workout as SvcWorkout
import org.tenshu.core.place.Place as SvcPlace
import org.tenshu.core.place.PlaceMerge as SvcPlaceMerge
import org.tenshu.core.place.PlaceRole as SvcPlaceRole
import org.tenshu.core.timeline.Moment as SvcMoment
import org.tenshu.core.timeline.MovementTrace as SvcTrace
import org.tenshu.core.timeline.Trip as SvcTrip
import app.tenshu.timeline.model.Moment as ProjectedMoment

/*
 * The Timeline **read model** on web (`web-app-suite.md` Wave 2, Timeline run A): the rows behind
 * `Timeline`'s feed / detail / place reads. It mirrors `TimelineProjectionRepository`
 * (`:timeline-shared-data`) read for read, without compiling that module to JS.
 *
 * ⚠⚠ Where the projection lives, and why (the run-A decision; `timeline-app.md` §Web scope 4a):
 *  - **Shared, not copied** — everything the native projector takes from a JS-compiled module is
 *    called here, not re-implemented: cross-source reconciliation (`MomentReconciler`), trip
 *    suggestion (`TripSuggester`), merge roots (`PlaceMerge.resolveRoot`), role-source parsing
 *    (`RoleSource.isUser`).
 *  - **Copied, small, pinned** — the joins: merge folding (`PlaceMergeFolding`), subject hydration,
 *    the confirmed-role rule (D9), day grouping, the suggestion's date range, the polyline codec's
 *    decode half. Each has a case in `TimelineBookMappingTest` or `TimelineWebBridgeTest`; change a
 *    native rule and its twin here must follow.
 *  - **Shared, the feed folds** — the presentation folds of `TimelineProjection.days` (the
 *    consecutive-visit merge §2b, the in-ride station-stop collapse §15/§32.8, movement endpoints
 *    §11) run from `:timeline-projection`, the module native Home calls too (`timeline-app.md` §Web
 *    scope 4a, decided 2026-10-24). ⚠⚠ Call it, never copy it: [feedDays] hands it the native
 *    `*Input`s and maps its rows back onto [TimelineDayMomentJs]. Trip detail and a place's visits
 *    stay data-level ([groupDays]), as natively — `TimelineProjection.trip` / `place` fold nothing.
 *    The web passes no ongoing state (§Web scope 4b), no photos (item 3) and no stop/line names
 *    (item 2: no tile lookup, no egress).
 *
 * ⚠ Unlike native, a **failed read is a failure**, never an empty feed (the Contacts run-A rule): the
 * web has no reachability banner to explain a blank timeline. And there is no `onStart(empty)` on the
 * merge / role reads — natively that seeds `combine`, but it also means a one-shot `first()` sees *no
 * merges*; here every source must answer, and one that stays silent past [SOURCE_SILENCE_BOUND] is a
 * typed failure ([TimelineRead.Silent]), never a hang.
 */

// ── rows (the native `*Input`s, JS-side) ─────────────────────────────────────

internal data class MomentRow(
    val id: String,
    val timestampMs: Long,
    val kind: String,
    val title: String,
    val durationSec: Long,
    val subjectKind: String,
    val subjectId: String,
    val supersededBy: String,
    val sources: List<String>,
)

internal data class PlaceRow(
    val id: String,
    val label: String,
    val latE7: Long,
    val lngE7: Long,
    val city: String,
    val countryCode: String,
    val labelSource: String,
)

/** A place's role and whether the user answered it — the native `PlaceRoleState`. */
internal data class RoleRow(val role: String, val isSuggestion: Boolean) {
    /** A confirmed, non-`none` role — the only kind that may stand in for a place's name (D9). */
    val isConfirmed: Boolean get() = !isSuggestion && role != PlaceRoles.NONE
}

internal data class TraceRow(
    val id: String,
    val mode: String,
    val distanceM: Long,
    /** §32.12 / §32.8: the station-stop collapse reads the tier stamp — as the native `traceInput`. */
    val classifierVersion: Int = 0,
    val startLatE7: Long = 0,
    val startLngE7: Long = 0,
    val endLatE7: Long = 0,
    val endLngE7: Long = 0,
)

internal data class TripRow(
    val id: String,
    val title: String,
    val dateRange: String,
    val year: String,
    val membership: List<String>,
)

/** Every relation one projection reads, as one consistent snapshot. */
internal class TimelineSnapshot(
    val rawMoments: List<MomentRow>,
    val rawPlaces: List<PlaceRow>,
    val merges: Map<String, String>,
    val roles: Map<String, RoleRow>,
    val traces: List<TraceRow>,
    val trips: List<TripRow>,
    /** `workoutId → activityType`, for the `Activity` moments [rawMoments] actually reference. */
    val workoutActivity: Map<String, String> = emptyMap(),
) {
    /** Moments with every `Visit` subject re-pointed at its surviving place (P4f). */
    val moments: List<MomentRow> by lazy { repointToRoots(rawMoments, merges) }

    /** Places minus every row folded into another. */
    val places: List<PlaceRow> by lazy { rootsOnly(rawPlaces, merges) }

    val placeById: Map<String, PlaceRow> by lazy { places.associateBy { it.id } }

    /** What the feed shows: superseded rows dropped, near-duplicates collapsed to their canonical. */
    val liveMoments: List<MomentRow> by lazy { reconcile(moments, placeById) }

    fun withWorkouts(activity: Map<String, String>) =
        TimelineSnapshot(rawMoments, rawPlaces, merges, roles, traces, trips, activity)
}

// ── the joins (twins of the native rules, each pinned) ───────────────────────

/** `PlaceMergeFolding.rootsOnly`. */
internal fun rootsOnly(places: List<PlaceRow>, merges: Map<String, String>): List<PlaceRow> =
    if (merges.isEmpty()) places else places.filter { PlaceMerge.resolveRoot(it.id, merges) == it.id }

/** `PlaceMergeFolding.repointToRoots`. */
internal fun repointToRoots(moments: List<MomentRow>, merges: Map<String, String>): List<MomentRow> {
    if (merges.isEmpty()) return moments
    return moments.map { m ->
        if (m.subjectKind != SubjectKind.PLACE) return@map m
        val root = PlaceMerge.resolveRoot(m.subjectId, merges)
        if (root == m.subjectId) m else m.copy(subjectId = root)
    }
}

/** `TimelineProjectionRepository.reconcile` — the shared reconciler over the live rows. */
internal fun reconcile(moments: List<MomentRow>, placeById: Map<String, PlaceRow>): List<MomentRow> {
    val live = moments.filter { it.supersededBy.isEmpty() }
    val reconcilable = live.map { m ->
        val p = if (m.subjectKind == SubjectKind.PLACE) placeById[m.subjectId] else null
        ReconcilableMoment(
            momentId = m.id,
            kind = m.kind,
            startMs = m.timestampMs,
            latE7 = p?.latE7 ?: 0,
            lngE7 = p?.lngE7 ?: 0,
            subjectKind = m.subjectKind,
            subjectId = m.subjectId,
            source = m.sources.firstOrNull() ?: "",
        )
    }
    val canonical = MomentReconciler.reconcile(reconcilable).map { it.canonicalMomentId }.toSet()
    return live.filter { it.id in canonical }
}

/** One moment with its subject resolved against [s] — the web feed row. */
internal fun resolveMoment(m: MomentRow, s: TimelineSnapshot): TimelineDayMomentJs {
    val place = if (m.subjectKind == SubjectKind.PLACE) s.placeById[m.subjectId] else null
    val trace = if (m.subjectKind == SubjectKind.TRACE) s.traces.firstOrNull { it.id == m.subjectId } else null
    val activity = if (m.subjectKind == SubjectKind.WORKOUT) s.workoutActivity[m.subjectId] else null
    val resolved = when (m.subjectKind) {
        SubjectKind.PLACE -> place != null
        SubjectKind.TRACE -> trace != null
        SubjectKind.WORKOUT -> activity != null
        else -> true
    }
    return TimelineDayMomentJs(
        momentId = m.id,
        timestampMs = m.timestampMs.toDouble(),
        kind = m.kind,
        title = m.title,
        durationSec = m.durationSec.toDouble(),
        subjectKind = m.subjectKind,
        subjectId = m.subjectId,
        subjectResolved = resolved,
        sources = m.sources.toTypedArray(),
        placeId = place?.id,
        placeName = place?.label,
        placeCity = place?.city,
        placeCountryCode = place?.countryCode,
        placeLatE7 = place?.latE7?.toDouble(),
        placeLngE7 = place?.lngE7?.toDouble(),
        // ⚠⚠ Confirmed only (D9): a suggestion must never rename the user's rows before they answer.
        placeRole = place?.let { s.roles[it.id] }?.takeIf { it.isConfirmed }?.role,
        activityType = activity,
        traceId = trace?.id,
        movementMode = trace?.mode,
        distanceM = trace?.distanceM?.toDouble(),
    )
}

/**
 * `TimelineProjection.days`' grouping, without its row folds: by the **start's** local date in
 * [zone], newest day first, oldest first within a day. ⚠ A visit that crosses midnight belongs to
 * the day it began on, as natively.
 */
internal fun groupDays(rows: List<MomentRow>, s: TimelineSnapshot, zone: LocalDates): Array<TimelineDayJs> =
    rows.sortedBy { it.timestampMs }
        .groupBy { zone.dateKey(it.timestampMs) }
        .toList()
        .sortedByDescending { it.first }
        .map { (date, dayRows) -> TimelineDayJs(date, dayRows.map { resolveMoment(it, s) }.toTypedArray()) }
        .toTypedArray()

/**
 * The feed's days, **folded by the shared projection** — `TimelineProjection.days`, the call native
 * Home makes (`TimelineProjectionRepository.homeDays`), over the same inputs: the reconciled live
 * rows, the surviving places, the confirmed roles plus every role-bearing place as the collapse veto.
 * Each folded row is its representative stored moment ([resolveMoment]) plus what the fold says
 * (`mergedStayCount`, the whole span, the ride's legs, its endpoints).
 *
 * ⚠ Days are keyed by the projection's own `LocalDate` in [zone] (kotlinx-datetime over the bundled
 * IANA set, [zoneOrNull]) — the same rule native groups by.
 */
internal fun feedDays(s: TimelineSnapshot, zone: TimeZone): Array<TimelineDayJs> {
    val rowById = s.liveMoments.associateBy { it.id }
    val days = TimelineProjection.days(
        moments = s.liveMoments.map { it.toInput() },
        places = s.places.map { it.toInput() },
        workouts = s.workoutActivity.map { (id, type) -> TimelineProjection.WorkoutInput(id, type) },
        zone = zone,
        traces = s.traces.map { it.toInput() },
        roles = s.roles.filterValues { it.isConfirmed }.mapValues { it.value.role },
        rolePlaceIds = s.roles.filterValues { it.role != PlaceRoles.NONE }.keys,
    )
    return days.map { day ->
        TimelineDayJs(
            date = day.date.toString(),
            moments = day.moments.map { m ->
                // Every projected row carries its representative's stored id; with no ongoing input
                // there is no synthetic row, so the lookup cannot miss.
                foldedRow(resolveMoment(rowById.getValue(m.id), s), m)
            }.toTypedArray(),
        )
    }.toTypedArray()
}

/** [row] (the representative stored moment) with the projection's fold of it, [m], added. */
internal fun foldedRow(row: TimelineDayMomentJs, m: ProjectedMoment) = TimelineDayMomentJs(
    momentId = row.momentId, timestampMs = row.timestampMs, kind = row.kind, title = row.title,
    durationSec = row.durationSec, subjectKind = row.subjectKind, subjectId = row.subjectId,
    subjectResolved = row.subjectResolved, sources = row.sources, placeId = row.placeId,
    placeName = row.placeName, placeCity = row.placeCity, placeCountryCode = row.placeCountryCode,
    placeLatE7 = row.placeLatE7, placeLngE7 = row.placeLngE7, placeRole = row.placeRole,
    activityType = row.activityType, traceId = row.traceId, movementMode = row.movementMode,
    distanceM = row.distanceM,
    displayTitle = m.title,
    spanDurationMin = m.durationMin,
    spanDistanceM = m.distanceM?.toDouble(),
    mergedStayCount = m.mergedStayCount,
    traceIds = m.traceIds.toTypedArray(),
    containedMoves = m.containedMoves.map { TimelineContainedMoveJs(it.mode, it.distanceM.toDouble(), it.durationMin) }
        .toTypedArray(),
    originPlace = m.originPlace,
    destinationPlace = m.destinationPlace,
    originRole = m.originRole,
    destinationRole = m.destinationRole,
)

private fun MomentRow.toInput() = TimelineProjection.MomentInput(
    id = id, timestampMs = timestampMs, kind = kind, title = title, durationSec = durationSec,
    subjectKind = subjectKind, subjectId = subjectId, supersededBy = supersededBy, sources = sources,
)

private fun PlaceRow.toInput() = TimelineProjection.PlaceInput(
    id = id, label = label, latE7 = latE7, lngE7 = lngE7, city = city, countryCode = countryCode,
    labelSource = labelSource,
)

private fun TraceRow.toInput() = TimelineProjection.TraceInput(
    id = id, mode = mode, distanceM = distanceM, classifierVersion = classifierVersion,
    startLatE7 = startLatE7, startLngE7 = startLngE7, endLatE7 = endLatE7, endLngE7 = endLngE7,
)

/**
 * The zone the projection groups by: `""` is the browser's own (natively
 * `TimeZone.currentSystemDefault()`), anything else must be a zone the bundled IANA set knows.
 */
internal fun projectionZone(zone: String): TimeZone? {
    if (zone.isNotEmpty()) return zoneOrNull(zone)
    zoneOrNull("UTC") // ⚠ touches the bundled IANA set first, so the system zone resolves by name
    return runCatching { TimeZone.currentSystemDefault() }.getOrNull()
}

/** The `Visit` moments referencing [placeId] — a place's derived history. */
internal fun visitsOf(placeId: String, moments: List<MomentRow>): List<MomentRow> =
    moments.filter { it.subjectKind == SubjectKind.PLACE && it.subjectId == placeId }

/** A place's list row: [visits] are what the native `places()` hands `TimelineProjection.place`. */
internal fun placeRowJs(p: PlaceRow, visits: List<MomentRow>, role: RoleRow?) = TimelinePlaceJs(
    placeId = p.id,
    name = p.label,
    city = p.city,
    countryCode = p.countryCode,
    latE7 = p.latE7.toDouble(),
    lngE7 = p.lngE7.toDouble(),
    nameIsUsers = LabelSource.isUser(p.labelSource) && p.label.isNotBlank(),
    // ⚠ The FULL state, suggestion included — the place surfaces are where the question is asked.
    role = role?.role,
    roleIsSuggestion = role?.isSuggestion ?: false,
    visitCount = visits.size,
    lastVisitMs = visits.maxOfOrNull { it.timestampMs }?.toDouble(),
)

/**
 * The places merged **directly** into [root] — the ids `unmergePlace` takes, each with its stored
 * name (`""` when its row is not on this device). ⚠ Direct only, matching `mergedPlaceCount`: in a
 * chain `F→G→R`, `G` is listed under `R`, and unmerging it brings `G` back with `F` still in it.
 */
internal fun foldedInto(root: String, s: TimelineSnapshot): List<TimelineFoldedPlaceJs> {
    val names = s.rawPlaces.associate { it.id to it.label }
    return s.merges.filter { (id, target) -> target == root && id != root }.keys.sorted()
        .map { id -> TimelineFoldedPlaceJs(placeId = id, name = names[id].orEmpty()) }
}

// ── trip suggestion (per-device — here per-tab — never synced, §5.2) ─────────

/**
 * Every unresolved suggestion, best first — `TimelineProjectionRepository.currentSuggestion` before
 * its `firstOrNull`. A **confirmed** `home` role beats the most-frequented-cell heuristic.
 *
 * ⚠ "Unresolved" means neither resolved in this tab nor **already a bound trip**:
 * accepting stores the trip under the suggestion's own id, so after a reload — when [resolved] is
 * empty again — that id in `s.trips` is the only memory of the answer.
 */
internal fun suggestions(s: TimelineSnapshot, resolved: Set<String>): List<TripSuggestion> {
    val storedHome = s.roles.entries
        .firstOrNull { (_, r) -> r.isConfirmed && r.role == PlaceRoles.HOME }
        ?.let { (placeId, _) -> s.placeById[placeId] }
        ?.let { it.latE7 to it.lngE7 }
    // ⚠ Natively over the repointed (not reconciled) moments — so here too.
    val inputs = s.moments
        .filter { it.subjectKind == SubjectKind.PLACE }
        .mapNotNull { m ->
            val p = s.placeById[m.subjectId] ?: return@mapNotNull null
            TripSuggestionInput(m.id, m.timestampMs, p.latE7, p.lngE7, p.label)
        }
    val bound = s.trips.mapTo(HashSet()) { it.id }
    return TripSuggester.suggest(inputs, storedHome = storedHome)
        .filter { it.suggestedTripId !in resolved && it.suggestedTripId !in bound }
}

internal fun suggestionJs(t: TripSuggestion, s: TimelineSnapshot, zone: LocalDates): TimelineTripSuggestionJs {
    val members = t.memberMomentIds.toSet()
    return TimelineTripSuggestionJs(
        suggestionId = t.suggestedTripId,
        title = t.title,
        dateRange = zone.dateRange(t.startMs, t.endMs),
        daysSpan = ((t.endMs - t.startMs) / DAY_MS).toInt() + 1,
        placesCount = s.moments
            .filter { it.id in members && it.subjectKind == SubjectKind.PLACE }
            .map { it.subjectId }.distinct().size,
        startMs = t.startMs.toDouble(),
        endMs = t.endMs.toDouble(),
        momentIds = t.memberMomentIds.toTypedArray(),
    )
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

// ── local dates (Intl, not kotlinx-datetime) ─────────────────────────────────

/**
 * Wall-clock dates in one IANA zone, through the browser's own `Intl.DateTimeFormat`.
 *
 * ⚠ Not `kotlinx-datetime`: on Kotlin/JS it ships no zone database (`app-notify`'s build notes —
 * `TimeZone.of("Europe/Berlin")` throws without ~180 KB of `@js-joda/timezone` in the bundle), and
 * every browser already carries the full ICU set. `""` is the browser's own zone — what natively is
 * `TimeZone.currentSystemDefault()`.
 */
internal class LocalDates private constructor(private val format: dynamic) {

    /** `YYYY-MM-DD` — sorts as a date, and is the day's key on the JS side. */
    fun dateKey(ms: Long): String {
        val (y, m, d) = parts(ms)
        return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** The native `formatDateRange`: `Oct 3 – 7`, or `Sep 28 – Oct 2` across months/years. */
    fun dateRange(startMs: Long, endMs: Long): String {
        val (sy, sm, sd) = parts(startMs)
        val (ey, em, ed) = parts(endMs)
        return if (sm == em && sy == ey) "${MONTHS[sm - 1]} $sd – $ed" else "${MONTHS[sm - 1]} $sd – ${MONTHS[em - 1]} $ed"
    }

    /** The native `formatYear`. */
    fun year(ms: Long): String = parts(ms).first.toString()

    private fun parts(ms: Long): Triple<Int, Int, Int> {
        val list = format.formatToParts(kotlin.js.Date(ms.toDouble()))
            .unsafeCast<Array<dynamic>>()
        fun part(type: String): Int = list.first { it.type == type }.value.unsafeCast<String>().toInt()
        return Triple(part("year"), part("month"), part("day"))
    }

    companion object {
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        private val newFormat: (dynamic) -> dynamic = js("(function (o) { return new Intl.DateTimeFormat('en-US', o) })")

        /** `null` for a zone the browser does not know — the caller answers `error_timeline_invalid_zone`. */
        fun of(zone: String): LocalDates? = try {
            val options: dynamic = js("({})")
            options.year = "numeric"
            options.month = "numeric"
            options.day = "numeric"
            if (zone.isNotEmpty()) options.timeZone = zone
            LocalDates(newFormat(options))
        } catch (e: Throwable) {
            null // RangeError: Invalid time zone specified
        }
    }
}

// ── the polyline codec's decode half ─────────────────────────────────────────

/**
 * `MovementTracePolyline.decode` (`:timeline-shared-data`) — delta + zig-zag varint of
 * (latE7, lngE7, timestampMs, accuracyM); returns (latE7, lngE7, timestampMs) per point.
 *
 * ⚠⚠ A second copy of a stored format. Pinned from both sides by one golden vector:
 * `MovementTracePolylineGoldenTest` (JVM, the real encoder) and `TimelineBookMappingTest` (here)
 * assert the same bytes. A truncated blob throws, which the caller reports as a failed read.
 */
internal fun decodePolyline(bytes: ByteArray): List<Triple<Long, Long, Long>> {
    if (bytes.isEmpty()) return emptyList()
    var pos = 0
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= bytes.size) throw IllegalArgumentException("truncated polyline")
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
    fun zigZag(): Long {
        val z = varint()
        return (z ushr 1) xor -(z and 1)
    }
    val count = varint().toInt()
    var lat = 0L
    var lng = 0L
    var ts = 0L
    return List(count) {
        lat += zigZag()
        lng += zigZag()
        ts += zigZag()
        zigZag() // accuracy — route data natively, not needed to draw
        Triple(lat, lng, ts)
    }
}

// ── reads ────────────────────────────────────────────────────────────────────

private fun <E : Data, R> TResult<TList<E>>.rows(row: (E) -> R): Read<List<R>> =
    fold(onSuccess = { Read.Ok(it.toList().map(row)) }, onFailure = { Read.Failed(it) })

/**
 * One projection read's outcome. ⚠ [Silent] is the third answer, beside a failed read: a source
 * that has not answered at all within [SOURCE_SILENCE_BOUND]. Without it `combine`
 * waits for that source forever, and `feed()` and every snapshot-first write hang with it.
 */
internal sealed interface TimelineRead {
    class Ok(val snapshot: TimelineSnapshot) : TimelineRead
    class Failed(val error: TError) : TimelineRead
    /** [source] has not emitted once — natively the `onStart(empty)` merges / roles seed covers it. */
    class Silent(val source: String) : TimelineRead
}

internal fun TimelineRead.failure(): dynamic = when (this) {
    is TimelineRead.Failed -> timelineFailure(error)
    is TimelineRead.Silent -> timelineFailure(TimelineWebErrorKeys.SOURCE_SILENT, source)
    is TimelineRead.Ok -> error("not a failure")
}

/**
 * How long a source may stay silent before the read answers [TimelineRead.Silent]. ⚠ A bound, not a
 * seed: native seeds merges / roles with `onStart(emptyMap())` (`TimelineProjectionRepository`
 * `mergesFlowFresh` / `placeRolesFlowFresh`), which a one-shot `first()` here would read as "nothing
 * is merged" — and `unmergePlace` / `mergePlaces` decide on exactly that. A late source still
 * counts: the stream pushes the real snapshot when it arrives.
 */
internal val SOURCE_SILENCE_BOUND: Duration = 15.seconds

/**
 * The whole read model, re-emitting on any relation's change — and, when [workoutWindow] is given,
 * on any `Workout` save or delete.
 *
 * [workoutWindow] is the **requested window**: the moments the caller will actually
 * render, whose `Activity` subjects are hydrated — the feed's live rows, a trip's members, one
 * detail. Null hydrates nothing (places, the writes). The workouts are read **by key**, never by
 * scanning the table (the read that blanked native Home at ~10k workouts), and only the ids the
 * window did not already resolve or that [WorkoutChanges] names — see [WorkoutHydrator].
 */
internal fun TenshuClient.timelineFlow(
    workoutWindow: ((TimelineSnapshot) -> List<MomentRow>)? = null,
): Flow<TimelineRead> =
    timelineFlowOf(
        TimelineSources(momentsRead(), placesRead(), mergesRead(), rolesRead(), tracesRead(), tripsRead()),
        hydrator = workoutWindow?.let { window ->
            WorkoutHydrator(window, WorkoutChanges.version, WorkoutChanges::changedSince) { id -> workoutActivity(id) }
        },
    )

/** The six relation reads one snapshot joins — a seam so a test can hand in a source that never answers. */
internal class TimelineSources(
    val moments: Flow<Read<List<MomentRow>>>,
    val places: Flow<Read<List<PlaceRow>>>,
    val merges: Flow<Read<List<Pair<String, String>>>>,
    val roles: Flow<Read<List<Pair<String, RoleRow>>>>,
    val traces: Flow<Read<List<TraceRow>>>,
    val trips: Flow<Read<List<TripRow>>>,
)

internal fun timelineFlowOf(
    sources: TimelineSources,
    silentAfter: Duration = SOURCE_SILENCE_BOUND,
    hydrator: WorkoutHydrator? = null,
): Flow<TimelineRead> {
    val joined = combine(
        sources.moments.answeringWithin(silentAfter),
        sources.places.answeringWithin(silentAfter),
        sources.merges.answeringWithin(silentAfter),
        sources.roles.answeringWithin(silentAfter),
        combine(
            sources.traces.answeringWithin(silentAfter),
            sources.trips.answeringWithin(silentAfter),
        ) { a, b -> a to b },
    ) { moments, places, merges, roles, (traces, trips) ->
        val all = listOf("moments" to moments, "places" to places, "merges" to merges, "roles" to roles, "traces" to traces, "trips" to trips)
        all.firstNotNullOfOrNull { (_, r) -> (r as? Read.Failed)?.let { TimelineRead.Failed(it.error) } }
            ?: all.firstOrNull { (_, r) -> r == null }?.let { (name, _) -> TimelineRead.Silent(name) }
            ?: TimelineRead.Ok(
                TimelineSnapshot(
                    rawMoments = (moments as Read.Ok).value,
                    rawPlaces = (places as Read.Ok).value,
                    merges = (merges as Read.Ok).value.toMap(),
                    roles = (roles as Read.Ok).value.toMap(),
                    traces = (traces as Read.Ok).value,
                    trips = (trips as Read.Ok).value,
                ),
            )
    }
    return hydrator?.let { joined.hydrated(it) } ?: joined
}

/**
 * This source's answers, preceded by **null** if it has not answered within [bound] — so `combine`
 * always emits, and a silent source becomes [TimelineRead.Silent] instead of a hang.
 */
private fun <T> Flow<Read<T>>.answeringWithin(bound: Duration): Flow<Read<T>?> = channelFlow {
    val silence = launch {
        delay(bound)
        send(null)
    }
    collect {
        silence.cancel()
        send(it)
    }
}

/**
 * Resolves the `Activity` moments of a requested window to their workouts' `activityType`, **one
 * keyed read per id it does not already hold**. Per collection it keeps what it read
 * (an absent row included — a keyed lookup cannot tell "absent" from "failed"); a [changes] bump
 * drops only the ids [changedSince] names, or everything when it cannot name them.
 *
 * ⚠ So a moment-only change reads nothing new, a workout save reads that one workout, and a read
 * never reaches past the window — natively the twin is `workoutsFor`'s `distinctUntilChanged` over
 * the referenced ids. ⚠ A read that **throws** (a dead dispatch) is not remembered: it is retried
 * on the next emission rather than pinned unresolved.
 */
internal class WorkoutHydrator(
    val window: (TimelineSnapshot) -> List<MomentRow>,
    val changes: Flow<Long>,
    val changedSince: (Long) -> Set<String>?,
    val read: suspend (workoutId: String) -> String?,
)

private fun Flow<TimelineRead>.hydrated(h: WorkoutHydrator): Flow<TimelineRead> = flow {
    val known = HashMap<String, String?>()
    var seen: Long? = null
    combine(this@hydrated, h.changes) { r, v -> r to v }.collect { (r, v) ->
        val since = seen
        if (since != null && v != since) {
            val changed = h.changedSince(since)
            if (changed == null) known.clear() else changed.forEach { known.remove(it) }
        }
        seen = v
        if (r !is TimelineRead.Ok) return@collect emit(r)
        val ids = h.window(r.snapshot).asSequence()
            .filter { it.subjectKind == SubjectKind.WORKOUT && it.subjectId.isNotBlank() }
            .map { it.subjectId }
            .toSet()
        for (id in ids) {
            if (id in known) continue
            try {
                known[id] = h.read(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not remembered: the next emission retries it.
            }
        }
        emit(TimelineRead.Ok(r.snapshot.withWorkouts(ids.mapNotNull { id -> known[id]?.let { id to it } }.toMap())))
    }
}

private fun TenshuClient.momentsRead(): Flow<Read<List<MomentRow>>> =
    timelineService.moments.select { fields(*MOMENT_FIELDS) }.flow().map { r -> r.rows(::momentRow) }

internal fun TenshuClient.placesRead(): Flow<Read<List<PlaceRow>>> =
    placeService.places.select { fields(*PLACE_FIELDS) }.flow().map { r -> r.rows(::placeRow) }

internal fun TenshuClient.mergesRead(): Flow<Read<List<Pair<String, String>>>> =
    placeService.merges.select { fields(SvcPlaceMerge.Fields.PLACE_ID, SvcPlaceMerge.Fields.MERGED_INTO) }
        .flow()
        .map { r ->
            r.rows {
                it.getFieldValue<TString>(SvcPlaceMerge.Fields.PLACE_ID).get() to
                    it.getFieldValue<TString>(SvcPlaceMerge.Fields.MERGED_INTO).get()
            }
        }

internal fun TenshuClient.rolesRead(): Flow<Read<List<Pair<String, RoleRow>>>> =
    placeService.roles
        .select { fields(SvcPlaceRole.Fields.PLACE_ID, SvcPlaceRole.Fields.ROLE, SvcPlaceRole.Fields.ROLE_SOURCE) }
        .flow()
        .map { r ->
            r.rows {
                it.getFieldValue<TString>(SvcPlaceRole.Fields.PLACE_ID).get() to RoleRow(
                    role = it.getFieldValue<TString>(SvcPlaceRole.Fields.ROLE).get(),
                    // ⚠ Anything not exactly `user` is a suggestion — the weaker reading, as natively.
                    isSuggestion = !RoleSource.isUser(it.getFieldValue<TString>(SvcPlaceRole.Fields.ROLE_SOURCE).get()),
                )
            }
        }

private fun TenshuClient.tracesRead(): Flow<Read<List<TraceRow>>> =
    timelineService.traces
        .select { fields(*TRACE_ROW_FIELDS) }
        .flow()
        .map { r ->
            r.rows {
                TraceRow(
                    id = it.getFieldValue<TString>(SvcTrace.Fields.TRACE_ID).get(),
                    mode = it.getFieldValue<TString>(SvcTrace.Fields.MODE).get(),
                    distanceM = it.getFieldValue<TInteger>(SvcTrace.Fields.DISTANCE_M).get(),
                    classifierVersion = it.getFieldValue<TInteger>(SvcTrace.Fields.CLASSIFIER_VERSION).get().toInt(),
                    startLatE7 = it.getFieldValue<TInteger>(SvcTrace.Fields.START_LAT_E7).get(),
                    startLngE7 = it.getFieldValue<TInteger>(SvcTrace.Fields.START_LNG_E7).get(),
                    endLatE7 = it.getFieldValue<TInteger>(SvcTrace.Fields.END_LAT_E7).get(),
                    endLngE7 = it.getFieldValue<TInteger>(SvcTrace.Fields.END_LNG_E7).get(),
                )
            }
        }

private fun TenshuClient.tripsRead(): Flow<Read<List<TripRow>>> =
    timelineService.trips.select { fields(*TRIP_FIELDS) }.flow().map { r -> r.rows(::tripRow) }

/** One keyed `Workout` read: its `activityType`, or null when the lookup found nothing. */
private suspend fun TenshuClient.workoutActivity(id: String): String? =
    healthService.workouts
        .search<SvcWorkout> { workoutId equalTo id }
        .select { fields(SvcWorkout.Fields.WORKOUT_ID, SvcWorkout.Fields.ACTIVITY_TYPE) }
        .resolve()
        .fold(onSuccess = { it }, onFailure = { null })
        ?.getFieldValue<TString>(SvcWorkout.Fields.ACTIVITY_TYPE)?.get()

private fun momentRow(e: Entity<out SvcMoment>): MomentRow {
    val subject = e.getFieldValue<SubjectRef>(SvcMoment.Fields.SUBJECT)
    return MomentRow(
        id = e.getFieldValue<TString>(SvcMoment.Fields.MOMENT_ID).get(),
        timestampMs = e.getFieldValue<UnixInstant>(SvcMoment.Fields.TIMESTAMP).toEpochMilliseconds(),
        kind = e.getFieldValue<TString>(SvcMoment.Fields.KIND).get(),
        title = e.getFieldValue<TString>(SvcMoment.Fields.TITLE).get(),
        durationSec = e.getFieldValue<TInteger>(SvcMoment.Fields.DURATION_SEC).get(),
        subjectKind = subject.kind.get(),
        subjectId = subject.id.get(),
        supersededBy = e.getFieldValue<TString>(SvcMoment.Fields.SUPERSEDED_BY).get(),
        sources = runCatching {
            e.getFieldValue<TList<*>>(SvcMoment.Fields.SOURCES).toList().mapNotNull { s ->
                when (s) {
                    is Entity<*> -> s.getFieldValue<TString>(MomentSource.Fields.SOURCE).get()
                    is MomentSource -> s.source.get()
                    else -> null
                }
            }
        }.getOrDefault(emptyList()),
    )
}

internal fun placeRow(e: Entity<out SvcPlace>) = PlaceRow(
    id = e.getFieldValue<TString>(SvcPlace.Fields.PLACE_ID).get(),
    label = e.getFieldValue<TString>(SvcPlace.Fields.LABEL).get(),
    latE7 = e.getFieldValue<TInteger>(SvcPlace.Fields.LAT_E7).get(),
    lngE7 = e.getFieldValue<TInteger>(SvcPlace.Fields.LNG_E7).get(),
    city = e.getFieldValue<TString>(SvcPlace.Fields.CITY).get(),
    countryCode = e.getFieldValue<TString>(SvcPlace.Fields.COUNTRY_CODE).get(),
    labelSource = e.getFieldValue<TString>(SvcPlace.Fields.LABEL_SOURCE).get(),
)

private fun tripRow(e: Entity<out SvcTrip>) = TripRow(
    id = e.getFieldValue<TString>(SvcTrip.Fields.TRIP_ID).get(),
    title = e.getFieldValue<TString>(SvcTrip.Fields.TITLE).get(),
    dateRange = e.getFieldValue<TString>(SvcTrip.Fields.DATE_RANGE).get(),
    year = e.getFieldValue<TString>(SvcTrip.Fields.YEAR).get(),
    membership = runCatching {
        e.getFieldValue<TList<*>>(SvcTrip.Fields.MEMBERSHIP).toList().mapNotNull { m ->
            when (m) {
                is Entity<*> -> m.getFieldValue<TString>(TripMember.Fields.MOMENT_ID).get()
                is TripMember -> m.momentId.get()
                else -> null
            }
        }
    }.getOrDefault(emptyList()),
)

// Byte-identical to `TimelineProjectionRepository`'s constants. ⚠ A field absent here reads back
// *empty*, not as an error, so a divergence is silent.
private val MOMENT_FIELDS = arrayOf(
    SvcMoment.Fields.MOMENT_ID, SvcMoment.Fields.TIMESTAMP, SvcMoment.Fields.KIND,
    SvcMoment.Fields.TITLE, SvcMoment.Fields.SUBJECT, SvcMoment.Fields.DURATION_SEC,
    SvcMoment.Fields.SUPERSEDED_BY, SvcMoment.Fields.SOURCES,
)
private val TRIP_FIELDS = arrayOf(
    SvcTrip.Fields.TRIP_ID, SvcTrip.Fields.TITLE, SvcTrip.Fields.DATE_RANGE, SvcTrip.Fields.YEAR,
    SvcTrip.Fields.MEMBERSHIP,
)
/**
 * What the native `traceInput` reads — the summary plus the §32.12 tier stamp and the two endpoints.
 * ⚠ A field absent here reads back `0`, which the collapse takes as "no tier evidence": silently
 * different rows from native, not an error.
 */
private val TRACE_ROW_FIELDS = arrayOf(
    SvcTrace.Fields.TRACE_ID, SvcTrace.Fields.MODE, SvcTrace.Fields.DISTANCE_M,
    SvcTrace.Fields.CLASSIFIER_VERSION,
    SvcTrace.Fields.START_LAT_E7, SvcTrace.Fields.START_LNG_E7,
    SvcTrace.Fields.END_LAT_E7, SvcTrace.Fields.END_LNG_E7,
)
internal val PLACE_FIELDS = arrayOf(
    SvcPlace.Fields.PLACE_ID, SvcPlace.Fields.LAT_E7, SvcPlace.Fields.LNG_E7,
    SvcPlace.Fields.CAPTURED_AT, SvcPlace.Fields.LABEL, SvcPlace.Fields.CITY,
    SvcPlace.Fields.COUNTRY_CODE, SvcPlace.Fields.LABEL_SOURCE,
)

// ── errors ───────────────────────────────────────────────────────────────────

/** The catalogue keys this surface mints itself; every other failure carries its derived key. */
internal object TimelineWebErrorKeys {
    val INVALID_ZONE = ErrorCopyKey("error_timeline_invalid_zone")
    val PLACE_NOT_FOUND = ErrorCopyKey("error_timeline_place_not_found")
    val BLANK_NAME = ErrorCopyKey("error_timeline_blank_name")
    val INVALID_ROLE = ErrorCopyKey("error_timeline_invalid_role")
    val INVALID_MERGE = ErrorCopyKey("error_timeline_invalid_merge")
    val NOT_MERGED = ErrorCopyKey("error_timeline_not_merged")
    /** The suggestion the UI showed is no longer offered (resolved, or the moments changed). */
    val SUGGESTION_GONE = ErrorCopyKey("error_timeline_suggestion_gone")
    val CORRUPT_ROUTE = ErrorCopyKey("error_timeline_corrupt_route")
    /** Both ids already resolve to one place — merging them would only rewrite a pointer. */
    val ALREADY_MERGED = ErrorCopyKey("error_timeline_already_merged")
    /** A relation read never answered within [SOURCE_SILENCE_BOUND] — see [TimelineRead.Silent]. */
    val SOURCE_SILENT = ErrorCopyKey("error_timeline_source_silent")
}

internal fun timelineFailure(error: TError): dynamic = failureResult(error.errorCopyKey().value, error.toString())

internal fun timelineFailure(key: ErrorCopyKey, message: String? = null): dynamic = failureResult(key.value, message)

// ── JS shapes ────────────────────────────────────────────────────────────────

/** One local day: `date` is `YYYY-MM-DD` in the zone the read was asked for. */
@JsExport
class TimelineDayJs internal constructor(
    val date: String,
    val moments: Array<TimelineDayMomentJs>,
)

/**
 * One feed row — a stored moment with its subject resolved. Fields of the other subject kinds are
 * `null`, never `""`: `placeName == ""` is an *unnamed* place, `placeName == null` is not a place.
 * `subjectResolved` is false when the subject row is not on this device (yet).
 */
@JsExport
class TimelineDayMomentJs internal constructor(
    val momentId: String,
    val timestampMs: Double,
    /** `Activity` | `Visit` | `Event` | `Quiet` | `Movement`. */
    val kind: String,
    /** The stored title — ⚠ English, composed at ingest; natively the headline is derived at render. */
    val title: String,
    val durationSec: Double,
    /** `none` | `place` | `workout` | `trace`; for a place, `subjectId` is the merge root. */
    val subjectKind: String,
    val subjectId: String,
    val subjectResolved: Boolean,
    val sources: Array<String>,
    val placeId: String?,
    val placeName: String?,
    val placeCity: String?,
    val placeCountryCode: String?,
    val placeLatE7: Double?,
    val placeLngE7: Double?,
    /** A **confirmed** role (`home` | `work`) only; a suggestion never reaches a row. */
    val placeRole: String?,
    val activityType: String?,
    val traceId: String?,
    val movementMode: String?,
    val distanceM: Double?,
    // ── the fold (feed rows only; elsewhere these describe the single stored moment) ──
    // ⚠ Added, never reshaped: every field above still describes the REPRESENTATIVE stored moment.
    /** The row's headline: the stored title, or — for a collapsed ride — the whole ride's, regenerated. */
    val displayTitle: String = title,
    /** The row's whole span in minutes (merged stay, collapsed ride incl. station stops); null = none. */
    val spanDurationMin: Int? = (durationSec / 60).toInt().takeIf { it > 0 },
    /** A movement row's distance — for a collapsed ride, every leg's. */
    val spanDistanceM: Double? = distanceM,
    /** How many stored `Visit` fragments this row stands for (§2b); 1 = not merged. */
    val mergedStayCount: Int = 1,
    /** Every leg's trace of a collapsed ride (§15), in order; else the one [traceId], if any. */
    val traceIds: Array<String> = listOfNotNull(traceId).toTypedArray(),
    /** Short moves folded inside a merged stay. */
    val containedMoves: Array<TimelineContainedMoveJs> = emptyArray(),
    /** A movement's bracketing visit names (§11, "Home → Work"); null when unknown. */
    val originPlace: String? = null,
    val destinationPlace: String? = null,
    /** A **confirmed** role of either end (D9), for "Home → Work"; null otherwise. */
    val originRole: String? = null,
    val destinationRole: String? = null,
)

/** A short move folded inside a merged stay (`ContainedMove`). */
@JsExport
class TimelineContainedMoveJs internal constructor(
    val mode: String,
    val distanceM: Double,
    val durationMin: Int?,
)

/** A bound trip as the feed lists it; `momentIds` resolve through `trip()`. */
@JsExport
class TimelineTripSummaryJs internal constructor(
    val tripId: String,
    val title: String,
    val dateRange: String,
    val year: String,
    val momentIds: Array<String>,
)

/** A bound trip with its member moments, grouped by local day like the feed. */
@JsExport
class TimelineTripDetailJs internal constructor(
    val tripId: String,
    val title: String,
    val dateRange: String,
    val year: String,
    val days: Array<TimelineDayJs>,
    /** Members the store does not (yet) hold — synced trips can arrive before their moments. */
    val missingMomentCount: Int,
)

/** The "read as a trip?" card. Pass `suggestionId` back to accept / dismiss exactly this one. */
@JsExport
class TimelineTripSuggestionJs internal constructor(
    val suggestionId: String,
    val title: String,
    val dateRange: String,
    val daysSpan: Int,
    val placesCount: Int,
    val startMs: Double,
    val endMs: Double,
    val momentIds: Array<String>,
)

@JsExport
class TimelinePlaceJs internal constructor(
    val placeId: String,
    /** `""` is an unnamed place — on web nothing names it but the user (no geocoder, §Web scope 2). */
    val name: String,
    val city: String,
    val countryCode: String,
    val latE7: Double,
    val lngE7: Double,
    /** The user named it themselves (`labelSource == user`). */
    val nameIsUsers: Boolean,
    /** `home` | `work` | `none` | null. With [roleIsSuggestion] it is a question to ask, not a fact. */
    val role: String?,
    val roleIsSuggestion: Boolean,
    val visitCount: Int,
    val lastVisitMs: Double?,
)

@JsExport
class TimelinePlaceDetailJs internal constructor(
    val place: TimelinePlaceJs,
    /** Newest first. */
    val visits: Array<TimelineDayMomentJs>,
    /** How many places are folded into this one — drives *Unmerge*. */
    val mergedPlaceCount: Int,
    /** The id that was asked for, when it was folded into [place] (a stale link), else null. */
    val redirectedFrom: String?,
    /** The places folded directly into this one — ⚠ *Unmerge* takes one of THESE ids, never [place]'s. */
    val foldedPlaces: Array<TimelineFoldedPlaceJs>,
)

/** [Timeline.placeVisitSummary]: the detail's visit stats without its visits. [placeId] is the survivor's. */
@JsExport
class TimelinePlaceVisitSummaryJs internal constructor(
    val placeId: String,
    val visitCount: Int,
    val lastVisitMs: Double?,
    /** The id that was asked for, when it was folded into [placeId] (a stale link), else null. */
    val redirectedFrom: String?,
)

/** A place folded into another: the id `unmergePlace` takes, and its own stored name (`""` = unnamed). */
@JsExport
class TimelineFoldedPlaceJs internal constructor(
    val placeId: String,
    val name: String,
)

@JsExport
class TimelineRoutePointJs internal constructor(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Double,
)
