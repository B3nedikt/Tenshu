@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.promise
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.PlaceRoles
import org.tenshu.core.place.RoleSource
import org.tenshu.core.place.recordPlace
import org.tenshu.core.place.recordPlaceMerge
import org.tenshu.core.place.recordPlaceRole
import org.tenshu.core.timeline.SnappedRoute
import org.tenshu.core.timeline.recordTrip
import org.tenshu.place.client.placeService
import org.tenshu.core.place.Place as SvcPlace
import org.tenshu.core.place.PlaceMerge as SvcPlaceMerge
import org.tenshu.core.place.PlaceRole as SvcPlaceRole
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.oneOf
import org.tenshu.core.number.TInteger
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MomentSource
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.Trip
import org.tenshu.core.timeline.TripMember
import org.tenshu.timeline.client.timelineService
import kotlin.js.Promise

/**
 * Web **read** surface for `:timeline-service` — the moment spine, the bound trips
 * and the movement-trace summaries (`docs/proposals/timeline-services.md` §5).
 *
 * This is the bridge, not an app: there is no `web/timeline/` package yet, and per
 * ADR 0020 the first new app surface is what extracts `@tenshu/app-kit`. What is
 * here is the boundary a Timeline app (or an apex panel) would consume, shaped to
 * mirror what the native [app.tenshu.timeline.data.TimelineRepository] projector
 * (`TimelineProjectionRepository`) already reads rather than inventing new shapes:
 * each accessor projects exactly that class's `MOMENT_FIELDS` / `TRIP_FIELDS` /
 * `TRACE_FIELDS`.
 *
 * ## Two layers
 *
 * **Raw** — [moments] / [moment] / [trips] / [traces]: the stored rows, un-hydrated (subjects as
 * `{subjectKind, subjectId}` pointers, superseded rows included). Kept for existing callers.
 *
 * **Projected** (`web-app-suite.md` Wave 2, Timeline run A) — [feed] / [momentDetail] / [trip] /
 * [places] / [place] / [movementRoute] and the place / trip-suggestion writes: the native
 * `TimelineRepository`'s surface over `TimelineBook.kt`, with visits resolved through
 * `:place-service` and activities through `:health-service` (`PlacePlugin`, `HealthPlugin`).
 *
 * **The polyline never crosses the feed.** [traces] projects the three summary scalars the
 * card reads; the decoded path is the separate, one-shot [movementRoute].
 *
 * Every accessor is `Promise`-shaped (one emission off the reactive read), the same
 * snapshot-then-refetch convention [Shares] / [IngestSources] use — React owns the
 * state, the SDK exposes operations (House Rule #5 / `drive-web-app.md` §2). A read
 * failure folds to an empty array — or, on the single-row reads, to
 * `{ ok: false, error }` — rather than rejecting, matching the sibling surfaces.
 */
@JsExport
object Timeline {

    /**
     * Every moment, **newest first** — the feed spine.
     *
     * ⚠ Returned raw: superseded moments (`supersededBy` non-empty) are *included*,
     * and no cross-source reconciliation is applied. Both are projector concerns the
     * native side does in `TimelineProjectionRepository.reconcile`, and doing half of
     * it here would give a JS consumer a subtly different feed than the native one.
     * `supersededBy` is exposed so a consumer can at minimum filter the stored half.
     */
    fun moments(): Promise<Array<TimelineMomentJs>> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.timelineService.moments
            .select { fields(*MOMENT_FIELDS) }
            .flow().first()
            .fold(
                onSuccess = { rows ->
                    rows.toList()
                        .map(::momentOf)
                        .sortedByDescending { it.timestampMs }
                        .toTypedArray()
                },
                onFailure = { emptyArray() },
            )
    }

    /**
     * One moment by [momentId] — the detail read. Resolves `{ ok: true, moment }` — `moment` null
     * when no such row — or `{ ok: false, error }` when the read failed.
     *
     * The id filter runs client-side over the same projection [moments] uses: there
     * is no registered by-key search op on the moments relation, which is exactly the
     * shape `TimelineProjectionRepository.moment` settles for too.
     *
     * ⚠ Both were `null`, so "this moment was superseded or never existed" and "the timeline read
     * failed" were the same answer to a caller that can only render one of them.
     */
    fun moment(momentId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.timelineService.moments
            .select { fields(*MOMENT_FIELDS) }
            .flow().first()
            .fold(
                onSuccess = { rows ->
                    val found = rows.toList().map(::momentOf).firstOrNull { it.momentId == momentId }
                    okResult { it.moment = found }
                },
                onFailure = { error -> failureResult("read", error.toString()) },
            )
    }

    /**
     * Every bound trip (a user curation over moments), newest-title-agnostic — the
     * service stores no sort key, so the order is the relation's. `momentIds` is the
     * membership, which a consumer resolves against [moments].
     */
    fun trips(): Promise<Array<TimelineTripJs>> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.timelineService.trips
            .select { fields(*TRIP_FIELDS) }
            .flow().first()
            .fold(
                onSuccess = { rows -> rows.toList().map(::tripOf).toTypedArray() },
                onFailure = { emptyArray() },
            )
    }

    /**
     * Movement-trace **summaries**, keyed by `traceId` — what a `MOVEMENT`-kind
     * moment's `subjectId` points at, so its card can name a mode and a distance.
     *
     * ⚠ Blob-free by construction (see the class KDoc): `polyline` is not projected.
     */
    fun traces(): Promise<Array<TimelineTraceJs>> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.timelineService.traces
            .select { fields(*TRACE_FIELDS) }
            .flow().first()
            .fold(
                onSuccess = { rows -> rows.toList().map(::traceOf).toTypedArray() },
                onFailure = { emptyArray() },
            )
    }

    // ── web-app-suite Wave 2, run A: the read model + the place / trip writes ────────
    //
    // Mirrors `TimelineRepository` (`:timeline-shared-logic`) op for op over `TimelineBook.kt`'s
    // projection — see that file's header for what is shared, what is copied and what is not done.
    // Every call answers `{ ok: true, … }` or `{ ok: false, error: { kind, message } }`, `kind` a
    // catalogue key; ⚠ a failed read is a failure, never an empty feed. [zone] is an IANA zone (`""`
    // = the browser's own) and only shapes the day keys and date strings.

    /**
     * Suggestions the user resolved in this tab — per-device natively (§5.2, never synced), so per
     * tab here; a reload offers a DISMISSED one again, as an app restart does natively. An accepted
     * one stays gone: its bound trip filters it out (`suggestions`). Internal so a test can reload.
     */
    internal val resolvedSuggestions = MutableStateFlow<Set<String>>(emptySet())

    /**
     * One snapshot. [workoutWindow] is what the caller renders — only its `Activity` subjects are
     * hydrated (see [timelineFlow]); the place reads and the writes hydrate nothing.
     */
    private suspend fun snapshot(workoutWindow: ((TimelineSnapshot) -> List<MomentRow>)? = null): TimelineRead =
        JsRuntime.tenshuClient.timelineFlow(workoutWindow).first()

    /** The feed's window: the rows it shows. */
    private val FEED_WINDOW: (TimelineSnapshot) -> List<MomentRow> = { it.liveMoments }

    /**
     * Home: `{ ok: true, days, trips, suggestion }` — the native `homeDays` + `boundTrips` +
     * `tripSuggestion`, from ONE snapshot so the three can never disagree. `suggestion` is the best
     * unresolved one, or null.
     */
    fun feed(zone: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val dates = LocalDates.of(zone) ?: return@promise timelineFailure(TimelineWebErrorKeys.INVALID_ZONE, zone)
        val tz = projectionZone(zone) ?: return@promise timelineFailure(TimelineWebErrorKeys.INVALID_ZONE, zone)
        feedResult(snapshot(FEED_WINDOW), resolvedSuggestions.value, dates, tz)
    }

    /**
     * Pushes the [feed] envelope on every change — a relation write, a sync apply, or a suggestion
     * resolved in this tab — until the returned lambda is called. ⚠ [onError] is terminal.
     */
    fun observeFeed(zone: String, callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        Contacts.observeJob(onError) {
            val dates = LocalDates.of(zone) ?: throw IllegalArgumentException("invalid time zone '$zone'")
            val tz = projectionZone(zone) ?: throw IllegalArgumentException("invalid time zone '$zone'")
            JsRuntime.tenshuClient.timelineFlow(FEED_WINDOW)
                .combine(resolvedSuggestions) { read, resolved -> feedResult(read, resolved, dates, tz) }
                .collect { callback(it) }
        }

    /**
     * One feed row by id: `{ ok: true, moment }`, `moment` **null** when no such row exists. Like the
     * native fresh path it looks past reconciliation, so a moment opened from a trip still resolves.
     */
    fun momentDetail(momentId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        when (val read = snapshot { s -> s.moments.filter { m -> m.id == momentId } }) {
            is TimelineRead.Ok -> okResult {
                it.moment = read.snapshot.moments.firstOrNull { m -> m.id == momentId }?.let { m -> resolveMoment(m, read.snapshot) }
            }
            else -> read.failure()
        }
    }

    /** One bound trip with its members by day: `{ ok: true, trip }`, `trip` null when unknown. */
    fun trip(tripId: String, zone: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val dates = LocalDates.of(zone) ?: return@promise timelineFailure(TimelineWebErrorKeys.INVALID_ZONE, zone)
        val members = { s: TimelineSnapshot ->
            val wanted = s.trips.firstOrNull { t -> t.id == tripId }?.membership.orEmpty().toSet()
            s.moments.filter { m -> m.id in wanted }
        }
        when (val read = snapshot(members)) {
            is TimelineRead.Ok -> okResult {
                val s = read.snapshot
                it.trip = s.trips.firstOrNull { t -> t.id == tripId }?.let { t ->
                    val wanted = t.membership.toSet()
                    val present = members(s)
                    TimelineTripDetailJs(
                        tripId = t.id, title = t.title, dateRange = t.dateRange, year = t.year,
                        days = groupDays(present, s, dates),
                        missingMomentCount = wanted.size - present.map { m -> m.id }.toSet().size,
                    )
                }
            }
            else -> read.failure()
        }
    }

    /** The Places tab: every surviving (un-folded) place, `{ ok: true, places }`. */
    fun places(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        placesResult(snapshot())
    }

    fun observePlaces(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        Contacts.observeJob(onError) {
            JsRuntime.tenshuClient.timelineFlow().collect { callback(placesResult(it)) }
        }

    /**
     * One place with its visit history: `{ ok: true, place }`, `place` null when unknown. ⚠ A
     * folded id is **followed** to its survivor (`redirectedFrom` says so) rather than 404-ing —
     * a stale link still means something.
     *
     * `foldedPlaces` lists the places merged directly into this one, by id and name: ⚠ they are the
     * ids *Unmerge* takes. The survivor carries no merge row, so `unmergePlace(survivorId)` is
     * `error_timeline_not_merged` — the UI unmerges **a specific folded place**.
     */
    fun place(placeId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        when (val read = snapshot()) {
            is TimelineRead.Ok -> okResult {
                val s = read.snapshot
                val root = PlaceMerge.resolveRoot(placeId, s.merges)
                it.place = s.placeById[root]?.let { p ->
                    // ⚠ Natively the detail's visits are the repointed moments, NOT reconciled ones.
                    val visits = visitsOf(root, s.moments).sortedByDescending { m -> m.timestampMs }
                    val folded = foldedInto(root, s)
                    TimelinePlaceDetailJs(
                        place = placeRowJs(p, visits, s.roles[root]),
                        visits = visits.map { m -> resolveMoment(m, s) }.toTypedArray(),
                        mergedPlaceCount = folded.size,
                        redirectedFrom = placeId.takeIf { root != placeId },
                        foldedPlaces = folded.toTypedArray(),
                    )
                }
            }
            else -> read.failure()
        }
    }

    /**
     * Just a place's visit stats — `{ ok: true, summary }`, `summary` null when unknown: the two
     * numbers [place] carries on its `place` row (`visitCount`, `lastVisitMs`, counted the same way),
     * plus `placeId` (the survivor's) and `redirectedFrom`. ⚠ For a caller that shows the stats and
     * nothing else (web Maps' detail): [place] also resolves EVERY visit moment, which for a home
     * place is thousands of rows over postMessage per open.
     */
    fun placeVisitSummary(placeId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        when (val read = snapshot()) {
            is TimelineRead.Ok -> okResult {
                val s = read.snapshot
                val root = PlaceMerge.resolveRoot(placeId, s.merges)
                it.summary = s.placeById[root]?.let { _ ->
                    val visits = visitsOf(root, s.moments)
                    TimelinePlaceVisitSummaryJs(
                        placeId = root,
                        visitCount = visits.size,
                        lastVisitMs = visits.maxOfOrNull { m -> m.timestampMs }?.toDouble(),
                        redirectedFrom = placeId.takeIf { root != placeId },
                    )
                }
            }
            else -> read.failure()
        }
    }

    /**
     * The decoded path of a journey (M5) — [traceIds]' legs concatenated **in the given order**, each
     * from its `SnappedRoute` when one exists, else from the captured polyline (B4). Data only: the
     * map is W-MAP's. `{ ok: true, points }`; an unknown id contributes nothing. The one read that
     * touches the polyline blobs, so it is never part of the feed. A map coloured per leg wants
     * [movementRoutes], which keeps the leg boundaries this one drops.
     */
    fun movementRoute(traceIds: Array<String>): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (traceIds.isEmpty()) return@promise okResult { it.points = emptyArray<TimelineRoutePointJs>() }
        val blobs = when (val read = readRouteBlobs(traceIds.toSet())) {
            is RouteBlobs.Ok -> read.byTrace
            is RouteBlobs.Failed -> return@promise read.result
        }
        val points = try {
            routePoints(traceIds.asList(), blobs)
        } catch (e: IllegalArgumentException) {
            return@promise timelineFailure(TimelineWebErrorKeys.CORRUPT_ROUTE, e.message)
        }
        okResult { it.points = points }
    }

    /**
     * [movementRoute] for several legs at once, **keeping the boundaries**: `{ ok: true, legs }`, where
     * `legs[i]` is the points of `legs[i]`'s traces, concatenated in the given order, with the same
     * snapped-over-captured substitution per trace. An unknown trace contributes nothing, so a leg
     * of unknown traces is an empty array, never a missing one.
     *
     * ⚠⚠ ONE read per relation for the whole trip, never one per leg: each relation is narrowed to the
     * requested ids (`traceId oneOf …`) before the blob is projected, so only the asked-for polylines
     * cross into this runtime. One call per leg (the first shape) loaded every polyline in the store
     * once per leg, concurrently — a 30-leg trip was 30 full-store blob loads.
     */
    fun movementRoutes(legs: Array<Array<String>>): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val wanted = legs.flatMap { it.asList() }.toSet()
        if (wanted.isEmpty()) {
            return@promise okResult { it.legs = legs.map { emptyArray<TimelineRoutePointJs>() }.toTypedArray() }
        }
        val blobs = when (val read = readRouteBlobs(wanted)) {
            is RouteBlobs.Ok -> read.byTrace
            is RouteBlobs.Failed -> return@promise read.result
        }
        val perLeg = try {
            legs.map { leg -> routePoints(leg.asList(), blobs) }
        } catch (e: IllegalArgumentException) {
            return@promise timelineFailure(TimelineWebErrorKeys.CORRUPT_ROUTE, e.message)
        }
        okResult { it.legs = perLeg.toTypedArray() }
    }

    /**
     * Rename a place (G4): the user's label, every other field kept, `labelSource = user` so the
     * enricher never overwrites it. ⚠ Unlike native, a blank name and an unknown place are typed
     * failures, not silent no-ops. (No Wi-Fi binding on web — §Web scope 4d.)
     */
    fun renamePlace(placeId: String, name: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val label = name.trim()
        if (label.isEmpty()) return@promise timelineFailure(TimelineWebErrorKeys.BLANK_NAME)
        writeRename(placeId, label)
    }

    /**
     * The user's answer to "is this your Home?" — `home` | `work` | `none` (`none` is a dismissal).
     * ⚠⚠ Always written **user-authored**: there is no way to write an inferred role from here, and
     * `PlaceRoleMerger` keeps the answer above any peer's inference. (A tab infers nothing itself —
     * `inferPlaceRoles = false`.)
     */
    fun setPlaceRole(placeId: String, role: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (role !in VALID_ROLES) return@promise timelineFailure(TimelineWebErrorKeys.INVALID_ROLE, role)
        when (val places = rawPlaceEntities()) {
            is Read.Failed -> return@promise timelineFailure(places.error)
            is Read.Ok -> if (places.value.none { it.getFieldValue<TString>(SvcPlace.Fields.PLACE_ID).get() == placeId }) {
                return@promise timelineFailure(TimelineWebErrorKeys.PLACE_NOT_FOUND, placeId)
            }
        }
        written(
            JsRuntime.tenshuClient.placeService.recordPlaceRole(
                SvcPlaceRole.create(
                    context = JsRuntime.tenshuClient.environment.tContext,
                    placeId = placeId,
                    role = role,
                    roleSource = RoleSource.USER,
                    decidedAt = UnixInstantDto(Clock.System.now().toEpochMilliseconds()),
                ),
            ),
        )
    }

    /**
     * Fold two places into one (§9.4): `{ ok: true, survivorId }`. ⚠ The survivor is the
     * deterministic root `min(a, b)`, not the caller's choice, so a manual merge and the phones'
     * automatic pass can never disagree; a user name on the folded row travels to the survivor first.
     *
     * ⚠ Both ids are resolved to their **roots** first, and root merges into root: a
     * stale, already-folded id would otherwise have its own pointer overwritten — `F→R` silently
     * replaced by `F→b`, splitting `F` out of `R`. Two ids that already share a root are
     * `error_timeline_already_merged`.
     */
    fun mergePlaces(a: String, b: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        if (a == b || a.isBlank() || b.isBlank()) return@promise timelineFailure(TimelineWebErrorKeys.INVALID_MERGE)
        val s = when (val read = snapshot()) {
            is TimelineRead.Ok -> read.snapshot
            else -> return@promise read.failure()
        }
        val rootA = PlaceMerge.resolveRoot(a, s.merges)
        val rootB = PlaceMerge.resolveRoot(b, s.merges)
        if (rootA == rootB) return@promise timelineFailure(TimelineWebErrorKeys.ALREADY_MERGED, rootA)
        val rows = s.rawPlaces.associateBy { it.id }
        val root = minOf(rootA, rootB)
        val folded = maxOf(rootA, rootB)
        val rootPlace = rows[root] ?: return@promise timelineFailure(TimelineWebErrorKeys.PLACE_NOT_FOUND, root)
        val foldedPlace = rows[folded] ?: return@promise timelineFailure(TimelineWebErrorKeys.PLACE_NOT_FOUND, folded)
        // Carry the name BEFORE recording the merge, so no read sees the survivor unnamed while the
        // other row is already hidden.
        if (rootPlace.label.isBlank() && foldedPlace.label.isNotBlank()) {
            val renamed = writeRename(root, foldedPlace.label)
            if (renamed.ok != true) return@promise renamed
        }
        val merged = written(recordMerge(folded, mergedInto = root))
        if (merged.ok == true) merged.survivorId = root
        merged
    }

    /**
     * Undo a merge (§9.4) by writing a **blank** target — never a delete, so the unmerge survives a
     * peer that still holds the merge (`PlaceMergeMerger`). ⚠ [placeId] is the **folded** place — one
     * of [place]'s `foldedPlaces` — never the survivor, which has no merge row: a place that is not
     * merged is a typed failure (`error_timeline_not_merged`), not a silent write.
     */
    fun unmergePlace(placeId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        when (val read = snapshot()) {
            is TimelineRead.Ok -> if (read.snapshot.merges[placeId].isNullOrBlank()) {
                return@promise timelineFailure(TimelineWebErrorKeys.NOT_MERGED, placeId)
            }
            else -> return@promise read.failure()
        }
        written(recordMerge(placeId, mergedInto = ""))
    }

    /**
     * "Read as a trip": materialise exactly the suggestion the UI showed as a synced `Trip`, then hide
     * it: `{ ok: true, tripId }`. ⚠ By id, unlike native's "the current one": if the suggestions
     * changed under the user, this refuses (`error_timeline_suggestion_gone`) instead of accepting a
     * different trip.
     */
    fun acceptTripSuggestion(suggestionId: String, zone: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val dates = LocalDates.of(zone) ?: return@promise timelineFailure(TimelineWebErrorKeys.INVALID_ZONE, zone)
        val s = when (val read = snapshot()) {
            is TimelineRead.Ok -> read.snapshot
            else -> return@promise read.failure()
        }
        val current = suggestions(s, resolvedSuggestions.value).firstOrNull { it.suggestedTripId == suggestionId }
            ?: return@promise timelineFailure(TimelineWebErrorKeys.SUGGESTION_GONE, suggestionId)
        val result = written(
            JsRuntime.tenshuClient.timelineService.recordTrip(
                Trip.create(
                    context = JsRuntime.tenshuClient.environment.tContext,
                    tripId = current.suggestedTripId,
                    title = current.title,
                    dateRange = dates.dateRange(current.startMs, current.endMs),
                    year = dates.year(current.startMs),
                    membership = current.memberMomentIds,
                ),
            ),
        )
        if (result.ok == true) {
            resolvedSuggestions.update { it + current.suggestedTripId }
            result.tripId = current.suggestedTripId
        }
        result
    }

    /** "Not a trip": hide the suggestion in this tab (never synced). `{ ok: true }`. */
    fun dismissTripSuggestion(suggestionId: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val s = when (val read = snapshot()) {
            is TimelineRead.Ok -> read.snapshot
            else -> return@promise read.failure()
        }
        if (suggestions(s, resolvedSuggestions.value).none { it.suggestedTripId == suggestionId }) {
            return@promise timelineFailure(TimelineWebErrorKeys.SUGGESTION_GONE, suggestionId)
        }
        resolvedSuggestions.update { it + suggestionId }
        okResult()
    }

    private val VALID_ROLES = setOf(PlaceRoles.HOME, PlaceRoles.WORK, PlaceRoles.NONE)

    private fun feedResult(read: TimelineRead, resolved: Set<String>, dates: LocalDates, zone: TimeZone): dynamic =
        when (read) {
            is TimelineRead.Ok -> okResult {
                val s = read.snapshot
                // ⚠ Folded by the SHARED projection (`:timeline-projection`), as native Home is.
                it.days = feedDays(s, zone)
                it.trips = s.trips.map { t ->
                    TimelineTripSummaryJs(t.id, t.title, t.dateRange, t.year, t.membership.toTypedArray())
                }.toTypedArray()
                it.suggestion = suggestions(s, resolved).firstOrNull()?.let { t -> suggestionJs(t, s, dates) }
            }
            else -> read.failure()
        }

    private fun placesResult(read: TimelineRead): dynamic = when (read) {
        is TimelineRead.Ok -> okResult {
            val s = read.snapshot
            // ⚠ Natively `places()` counts the RECONCILED visits; the detail counts them all.
            it.places = s.places.map { p -> placeRowJs(p, visitsOf(p.id, s.liveMoments), s.roles[p.id]) }.toTypedArray()
        }
        else -> read.failure()
    }

    private suspend fun rawPlaceEntities(): Read<List<Entity<out SvcPlace>>> =
        JsRuntime.tenshuClient.placeService.places.select { fields(*PLACE_FIELDS) }.getAll()
            .fold(onSuccess = { Read.Ok(it.toList()) }, onFailure = { Read.Failed(it) })

    /** The `recordPlace` upsert behind a rename — every field but the label kept. */
    private suspend fun writeRename(placeId: String, label: String): dynamic {
        val row = when (val places = rawPlaceEntities()) {
            is Read.Failed -> return timelineFailure(places.error)
            is Read.Ok -> places.value.firstOrNull { it.getFieldValue<TString>(SvcPlace.Fields.PLACE_ID).get() == placeId }
                ?: return timelineFailure(TimelineWebErrorKeys.PLACE_NOT_FOUND, placeId)
        }
        return written(
            JsRuntime.tenshuClient.placeService.recordPlace(
                SvcPlace.create(
                    context = JsRuntime.tenshuClient.environment.tContext,
                    placeId = placeId,
                    latE7 = row.getFieldValue<TInteger>(SvcPlace.Fields.LAT_E7).get(),
                    lngE7 = row.getFieldValue<TInteger>(SvcPlace.Fields.LNG_E7).get(),
                    capturedAt = row.getFieldValue<UnixInstant>(SvcPlace.Fields.CAPTURED_AT),
                    label = label,
                    city = row.getFieldValue<TString>(SvcPlace.Fields.CITY).get(),
                    countryCode = row.getFieldValue<TString>(SvcPlace.Fields.COUNTRY_CODE).get(),
                    labelSource = LabelSource.USER,
                ),
            ),
        )
    }

    private suspend fun recordMerge(placeId: String, mergedInto: String): TResult<Data?> =
        JsRuntime.tenshuClient.placeService.recordPlaceMerge(
            SvcPlaceMerge.create(
                context = JsRuntime.tenshuClient.environment.tContext,
                placeId = placeId,
                mergedInto = mergedInto,
                mergedAt = UnixInstantDto(Clock.System.now().toEpochMilliseconds()),
            ),
        )

    private fun written(result: TResult<Data?>): dynamic =
        result.fold(onSuccess = { okResult() }, onFailure = { timelineFailure(it) })

    // ── entity → JS extraction (mirrors TimelineProjectionRepository's *Input mappers) ──

    private fun momentOf(e: Entity<out Moment>): TimelineMomentJs {
        val subject = e.getFieldValue<SubjectRef>(Moment.Fields.SUBJECT)
        return TimelineMomentJs(
            momentId = e.getFieldValue<TString>(Moment.Fields.MOMENT_ID).get(),
            timestampMs = e.getFieldValue<UnixInstant>(Moment.Fields.TIMESTAMP)
                .toEpochMilliseconds().toDouble(),
            kind = e.getFieldValue<TString>(Moment.Fields.KIND).get(),
            title = e.getFieldValue<TString>(Moment.Fields.TITLE).get(),
            durationSec = e.getFieldValue<TInteger>(Moment.Fields.DURATION_SEC).get().toDouble(),
            subjectKind = subject.kind.get(),
            subjectId = subject.id.get(),
            supersededBy = e.getFieldValue<TString>(Moment.Fields.SUPERSEDED_BY).get(),
            sources = sourcesOf(e),
        )
    }

    /**
     * The moment's provenance tags. Elements come back as either `Entity<MomentSource>`
     * or a raw composite depending on the select path — both are handled — and a read
     * failure (field absent on an older peer's row) folds to empty rather than failing
     * the whole projection. Same shape as the native `sourcesOf`.
     */
    private fun sourcesOf(e: Entity<out Moment>): Array<String> =
        runCatching {
            e.getFieldValue<TList<*>>(Moment.Fields.SOURCES).toList().mapNotNull { s ->
                when (s) {
                    is Entity<*> -> s.getFieldValue<TString>(MomentSource.Fields.SOURCE).get()
                    is MomentSource -> s.source.get()
                    else -> null
                }
            }.toTypedArray()
        }.getOrDefault(emptyArray())

    private fun tripOf(e: Entity<out Trip>) = TimelineTripJs(
        tripId = e.getFieldValue<TString>(Trip.Fields.TRIP_ID).get(),
        title = e.getFieldValue<TString>(Trip.Fields.TITLE).get(),
        dateRange = e.getFieldValue<TString>(Trip.Fields.DATE_RANGE).get(),
        year = e.getFieldValue<TString>(Trip.Fields.YEAR).get(),
        momentIds = membershipOf(e),
    )

    /** The trip's membership (moment ids); same dual-shape handling as [sourcesOf]. */
    private fun membershipOf(e: Entity<out Trip>): Array<String> =
        runCatching {
            e.getFieldValue<TList<*>>(Trip.Fields.MEMBERSHIP).toList().mapNotNull { m ->
                when (m) {
                    is Entity<*> -> m.getFieldValue<TString>(TripMember.Fields.MOMENT_ID).get()
                    is TripMember -> m.momentId.get()
                    else -> null
                }
            }.toTypedArray()
        }.getOrDefault(emptyArray())

    private fun traceOf(e: Entity<out MovementTrace>) = TimelineTraceJs(
        traceId = e.getFieldValue<TString>(MovementTrace.Fields.TRACE_ID).get(),
        mode = e.getFieldValue<TString>(MovementTrace.Fields.MODE).get(),
        distanceM = e.getFieldValue<TInteger>(MovementTrace.Fields.DISTANCE_M).get().toDouble(),
    )

    // The projections. Kept byte-identical to `TimelineProjectionRepository`'s
    // constants of the same name — ⚠ a field absent here reads back *empty*, not
    // as an error, so a divergence is silent (the note that comment carries too).
    private val MOMENT_FIELDS = arrayOf(
        Moment.Fields.MOMENT_ID, Moment.Fields.TIMESTAMP, Moment.Fields.KIND,
        Moment.Fields.TITLE, Moment.Fields.SUBJECT, Moment.Fields.DURATION_SEC,
        Moment.Fields.SUPERSEDED_BY, Moment.Fields.SOURCES,
    )
    private val TRIP_FIELDS = arrayOf(
        Trip.Fields.TRIP_ID, Trip.Fields.TITLE, Trip.Fields.DATE_RANGE, Trip.Fields.YEAR,
        Trip.Fields.MEMBERSHIP,
    )
    private val TRACE_FIELDS = arrayOf(
        MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.MODE, MovementTrace.Fields.DISTANCE_M,
    )
}

/**
 * One timeline moment, surfaced to JS. `timestampMs` / `durationSec` are `Double`
 * (JS numbers). `subjectKind` is a `SubjectKind` value (`none` / `place` / `workout`
 * / `trace`) and `subjectId` the owning service's id — un-hydrated, see [Timeline].
 * `supersededBy` is the canonical moment's key when reconciliation folded this one
 * away, `""` otherwise.
 */
@JsExport
class TimelineMomentJs internal constructor(
    val momentId: String,
    val timestampMs: Double,
    val kind: String,
    val title: String,
    val durationSec: Double,
    val subjectKind: String,
    val subjectId: String,
    val supersededBy: String,
    val sources: Array<String>,
)

/** One bound trip. `momentIds` is its membership, resolvable against `Timeline.moments()`. */
@JsExport
class TimelineTripJs internal constructor(
    val tripId: String,
    val title: String,
    val dateRange: String,
    val year: String,
    val momentIds: Array<String>,
)

/**
 * One movement-trace summary — the subject of a `MOVEMENT` moment. `mode` is a
 * `MovementMode` string (`walk` / `run` / `cycle` / `drive` / `unknown`);
 * `distanceM` is metres. The path blob is deliberately not here.
 */
@JsExport
class TimelineTraceJs internal constructor(
    val traceId: String,
    val mode: String,
    val distanceM: Double,
)

// ── the route read, shared by `movementRoute` and `movementRoutes` ───────────

internal sealed interface RouteBlobs {
    /** Each requested trace's blob, the snapped route's where one exists. Unknown ids are absent. */
    class Ok(val byTrace: Map<String, ByteArray>) : RouteBlobs
    class Failed(val result: dynamic) : RouteBlobs
}

/**
 * The polyline blob of each trace in [wanted]: one read of `traces` and one of `snappedRoutes`,
 * each filtered to [wanted] BEFORE the blob is projected. ⚠ The result holds only what the store
 * answered — no second, in-memory filter — so a test can see that nothing unasked was read
 * (`TimelineWebBridgeTest`). ⚠ `oneOf` is a linear scan: it bounds what is materialised, not
 * what the store walks.
 */
internal suspend fun readRouteBlobs(wanted: Set<String>): RouteBlobs {
    val ids = wanted.toList()
    val service = JsRuntime.tenshuClient.timelineService
    val raw = service.traces
        .filter<MovementTrace> { traceId oneOf ids }
        .select { fields(MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.POLYLINE) }
        .getAll()
    val snapped = service.snappedRoutes
        .filter<SnappedRoute> { traceId oneOf ids }
        .select { fields(SnappedRoute.Fields.TRACE_ID, SnappedRoute.Fields.POLYLINE) }
        .getAll()
    fun blobs(r: TResult<TList<*>>, idField: String, blobField: String): Map<String, ByteArray>? =
        r.fold(
            onSuccess = { rows ->
                rows.toList().map { it as Entity<*> }
                    .associate { it.getFieldValue<TString>(idField).get() to it.getFieldValue<TByteArray>(blobField).get() }
            },
            onFailure = { null },
        )
    val rawBlobs = blobs(raw, MovementTrace.Fields.TRACE_ID, MovementTrace.Fields.POLYLINE)
        ?: return RouteBlobs.Failed(raw.fold(onSuccess = { timelineFailure(TimelineWebErrorKeys.CORRUPT_ROUTE) }, onFailure = { timelineFailure(it) }))
    val snappedBlobs = blobs(snapped, SnappedRoute.Fields.TRACE_ID, SnappedRoute.Fields.POLYLINE)
        ?: return RouteBlobs.Failed(snapped.fold(onSuccess = { timelineFailure(TimelineWebErrorKeys.CORRUPT_ROUTE) }, onFailure = { timelineFailure(it) }))
    // The substitution (B4): a leg with a corrected route is drawn from it, else from its capture.
    return RouteBlobs.Ok(rawBlobs + snappedBlobs)
}

/** [traceIds]' points, concatenated in order. ⚠ Throws `IllegalArgumentException` on a corrupt blob. */
private fun routePoints(traceIds: List<String>, blobs: Map<String, ByteArray>): Array<TimelineRoutePointJs> =
    traceIds.flatMap { id -> blobs[id]?.let(::decodePolyline).orEmpty() }
        .map { (lat, lng, ts) -> TimelineRoutePointJs(latitude = lat / 1e7, longitude = lng / 1e7, timestampMs = ts.toDouble()) }
        .toTypedArray()
