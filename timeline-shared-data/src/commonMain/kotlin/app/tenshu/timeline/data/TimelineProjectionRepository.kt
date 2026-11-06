package app.tenshu.timeline.data

import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.retryingOnThrow
import app.tenshu.reachability.ProxyHost
import app.tenshu.reachability.UnreachableReason
import app.tenshu.timeline.data.capture.MovementDetectionConfig
import app.tenshu.timeline.data.capture.MovementTracePolyline
import app.tenshu.timeline.data.capture.OngoingMove
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import app.tenshu.timeline.data.geocode.PlaceGeocodingEgress
import app.tenshu.timeline.data.capture.OngoingMoveState
import app.tenshu.timeline.data.capture.OngoingVisit
import app.tenshu.timeline.data.capture.OngoingVisitState
import app.tenshu.timeline.model.MovementModes
import app.tenshu.timeline.data.projection.TimelineProjection
import app.tenshu.timeline.data.projection.TimelineProjection.ActivityCountInput
import app.tenshu.timeline.data.projection.TimelineProjection.MomentInput
import app.tenshu.timeline.data.projection.TimelineProjection.PlaceInput
import app.tenshu.timeline.data.projection.TimelineProjection.TraceInput
import app.tenshu.timeline.data.projection.TimelineProjection.TripInput
import app.tenshu.timeline.data.projection.TimelineProjection.WorkoutInput
import app.tenshu.timeline.data.wifi.WifiPlaceFingerprint
import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.PlaceRoleNames
import app.tenshu.timeline.model.PlaceRoleState
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.time.Clock
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.preference.MeasurementSystem
import org.tenshu.preference.client.NoopOwnerProfile
import org.tenshu.preference.client.OwnerProfile
import app.tenshu.timeline.data.projection.PhotoSelection
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.Preview
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.ThumbnailXLarge
import org.tenshu.core.file.ThumbnailXXLarge
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.eager
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.entity.get
import org.tenshu.core.getOrNull
import org.tenshu.core.ifEmptyError
import org.tenshu.core.file.system.hasLocalData
import org.tenshu.core.file.system.photosCapturedBetween
import org.tenshu.file.client.asksForHeroByName
import org.tenshu.file.client.fileService
import app.tenshu.vectortile.TransitLineIndex
import org.tenshu.core.mapregion.linesServing
import org.tenshu.core.timeline.recordTrip
import org.tenshu.timeline.common.MomentReconciler
import org.tenshu.timeline.common.ReconcilableMoment
import org.tenshu.timeline.common.TripSuggester
import org.tenshu.timeline.common.TripSuggestionInput
import org.tenshu.timeline.common.TripSuggestion as ServiceTripSuggestion
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.number.TInteger
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.search
import org.tenshu.core.equalTo
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.fold
import org.tenshu.core.time.UnixInstant
import org.tenshu.health.client.healthService
import org.tenshu.core.core.TContext
import org.tenshu.core.place.LabelSource
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.recordPlace
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.place.recordPlaceMerge
import org.tenshu.core.place.recordPlaceRole
import org.tenshu.place.client.placeService
import org.tenshu.mapregion.client.mapRegionService
import org.tenshu.timeline.client.timelineService
import app.tenshu.timeline.model.Moment as UiMoment
import org.tenshu.core.health.Workout as SvcWorkout
import org.tenshu.core.health.WorkoutActivityRollup as SvcWorkoutActivityRollup
import org.tenshu.core.place.Place as SvcPlace
import org.tenshu.core.place.PlaceMerge as SvcPlaceMerge
import org.tenshu.core.place.PlaceRole as SvcPlaceRole
import org.tenshu.core.place.RoleSource as SvcRoleSource
import org.tenshu.core.timeline.Moment as SvcMoment
import org.tenshu.core.timeline.MomentSource as SvcMomentSource
import org.tenshu.core.timeline.MovementTrace as SvcTrace
import org.tenshu.core.timeline.SnappedRoute as SvcSnappedRoute
import org.tenshu.core.timeline.SubjectRef as SvcSubjectRef
import org.tenshu.core.timeline.Trip as SvcTrip
import org.tenshu.core.timeline.TripMember as SvcTripMember

/**
 * The real [TimelineRepository] — a **read-model projector** over the three backend services
 * (`docs/proposals/timeline-services.md` §6). Reads `Moment`/`Trip` from `:timeline-service`,
 * `Place` from `:place-service`, `Workout` from `:health-service` via one [TenshuClient],
 * extracts lightweight inputs, and maps them to the presentation model with [TimelineProjection].
 * Binds behind the same Koin `single<TimelineRepository>` the mock uses — **zero VM/UI change**.
 *
 * **Subject hydration (§1.1 / §5.1).** A `Moment` references its domain subject by a polymorphic
 * `{kind, id}` [SubjectRef][SvcSubjectRef]. The feed/place reads therefore join across services:
 * the moment stream is combined with the place + workout streams so the projector can resolve a
 * `Visit`'s place name / an `Activity`'s workout activity, and a place's visit history is derived
 * from the `Visit`-moments that point at it.
 */
class TimelineProjectionRepository(
    private val client: TenshuClient,
    private val context: TContext? = null,
    /**
     * Scope for the reachability probe (fit-app.md §9.14 recipe, shared via `:app-reachability`);
     * absent (tests) no probe runs and the signal tracks only organic read outcomes.
     */
    private val probeScope: CoroutineScope? = null,
    probeIntervalMs: Long = 5_000,
    /** Test seam for the reachability probe; production uses the real one-row select. */
    probe: (suspend () -> Boolean)? = null,
    /**
     * The schema-skew verdict (`core-schema-skew-surface.md` SK-1b), so the shell banner can say
     * *update Control* instead of *can't reach Control* when the proxy is answering and refusing.
     *
     * ⚠ Absent (tests, previews) every outage reads as a transport problem — the pre-SK-1
     * behaviour, not a crash.
     */
    schemaSkew: StateFlow<UnreachableReason.Skew?>? = null,
    /**
     * The realm schema pull fence (`core-schema-skew-surface.md` SK-3) - the services this device
     * is behind on, empty when in step.
     *
     * WARN: absent, a fenced device renders NO banner at all - the pre-SK-3 behaviour and the
     * worst of the three, because the fence gates `pullAllowed` only: the app answers every read
     * and looks completely healthy while silently receiving nothing.
     */
    schemaFence: StateFlow<List<String>>? = null,
    /**
     * Which binary owns this app's proxy (SK-2), read from the proxy's own `RuntimeIdentity` row
     * rather than assumed. `null` means no host declared itself; the banner then reports the fence
     * without naming an app instead of guessing wrong on one platform.
     */
    proxyHost: StateFlow<ProxyHost?>? = null,
    /**
     * Opt-in Wi-Fi place recognition (§4.5), so a rename can bind the network the user is standing
     * on to the place they just named (§8.4 F2). `null` on platforms without a binding.
     */
    private val wifi: WifiPlaceFingerprint? = null,
    /**
     * The owner's person, for its [MeasurementSystem] only (`fit-app.md` §18.2). Defaults to the
     * no-op so a harness with no preference proxy still constructs — it then reports metric, which
     * is the same answer an un-set realm gives.
     */
    private val ownerProfile: OwnerProfile = NoopOwnerProfile,
    /**
     * The user's place-name-lookup opt-in (`place-geocoding.md` D5), which gates the transit-stop
     * lookup and the picker's suggestions — both can send a coordinate to a third-party geocoder.
     *
     * ⚠⚠ `null` means **no**, not "ungated": absence of a choice is never consent. A platform that
     * binds no control gets unnamed stops and a free-text picker. See [PlaceGeocodingEgress].
     */
    geocodingConsent: PlaceGeocodingControl? = null,
    /**
     * Whether Control's device has a realm (ADR 0025 §Decision 8) — wired on Android/desktop to
     * `syncService.getSyncStatus()`. ⚠ Last and defaulted so iOS route-1 constructs untouched;
     * absent, `NotSetUp` never fires.
     */
    private val controlHasRealm: app.tenshu.reachability.RealmProbe,
) : TimelineRepository {

    /**
     * Control-reachability (shared engine; see [ControlReachability]): Timeline has no store of
     * its own — every read goes over IPC to Control — so an unreachable Control folds every
     * screen to empty; this signal drives the shell banner, and its recovery probe re-subscribes
     * the read streams so live screens repopulate without re-entry. Internal for tests.
     */
    /** The only door to a third-party geocoder — every lookup checks the opt-in there. */
    private val geocodingEgress = PlaceGeocodingEgress(client, geocodingConsent)

    internal val reach = ControlReachability(
        probe = probe ?: { probeControl() },
        scope = probeScope,
        probeIntervalMs = probeIntervalMs,
        // R4's transport signal: reports the outage instantly instead of after a probe interval,
        // and suppresses probing across a channel that cannot carry one.
        transportConnected = client.proxyConnected,
        schemaSkew = schemaSkew,
        schemaFence = schemaFence,
        proxyHost = proxyHost,
        // ADR 0025: lets the shell say "Control isn't installed" instead of "open Control and unlock
        // it" — an instruction naming an app that is not on the device. `null` (desktop today, every
        // in-process runtime) leaves the outage worded exactly as before.
        controlInstalled = { client.controlInstalled() },
        // One event per call the proxy served, used to WAKE the probe rather than to answer for
        // it — see `ControlReachability.answered`. This is what replaced the repositories'
        // hand-written `markReachable` calls.
        answered = client.proxyAnswered,
        controlHasRealm = controlHasRealm,
    )

    override fun controlReachable(): StateFlow<Boolean?> = reach.state

    override fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<UnreachableReason?> = reach.reason

    override fun recheckControl() = reach.recheck()

    override val realmWriteGate: ControlReachability get() = reach

    /**
     * The owner's units, straight off the platform preference row — no Timeline-side copy, so a
     * change made in Fit's Profile reaches this app without either knowing about the other
     * (`fit-app.md` §18.2).
     */
    override fun units(): Flow<MeasurementSystem> = ownerProfile.observe().map { it.units }

    /** The cheapest "is Control there?" round-trip: one row, one field (recovery loop only). */
    private suspend fun probeControl(): Boolean =
        try {
            client.placeService.places.take(1)
                .select { fields(SvcPlace.Fields.PLACE_ID) }.getAll()
                .fold(onSuccess = { true }, onFailure = { false })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dead dispatch may throw rather than fold (see [unreachable]).
            false
        }

    /**
     * Fold a read result while tracking Control reachability: a success proves the proxy
     * answered; a failure — the executor folds transport errors into a Failure — flips
     * the signal. Reads still degrade to empty so screens render (blank, but *explained*).
     */
    private fun <T : Data?, R> TResult<T>.foldTracked(
        empty: R,
        onSuccess: (T) -> R,
    ): R =
        fold(
            onSuccess = { v ->
                onSuccess(v)
            },
            onFailure = {
                reach.markUnreachable()
                empty
            },
        )

    /**
     * A dead dispatch does not always fold into a Failure — a `.flow()` read can *throw* into
     * its collector (or hang; the probe owns that case). Same signal: mark unreachable and emit
     * the empty list the fold path would have produced.
     */
    /**
     * Retry a read that threw, naming it — [app.tenshu.reachability.retryingOnThrow] carries why
     * this is not a `catch`, and why it does not report an outage.
     */
    private fun <T> Flow<T>.retrying(read: String): Flow<T> =
        retryingOnThrow(read) { message, _ -> println("[timeline] $message") }

    /**
     * The projected feed. Shared like the reads beneath it, so the several consumers of a Home
     * render cost one projection — and so [moment] can answer from the projection the user actually
     * tapped instead of deriving its own (see [homeDaysReplay]).
     */
    override fun homeDays(): Flow<List<Day>> = homeDaysShared ?: homeDaysSource

    private val homeDaysSource: Flow<List<Day>> by lazy {
        combine(
            momentsFlow(), placesWithRoles(), workoutsFlow(), tracesFlow(), liveCaptureFlow(),
        ) { moments, placesAndRoles, workouts, traces, live ->
            val (places, roles) = placesAndRoles
            val reconciled = reconcile(moments, places)
            TimelineProjection.days(
                reconciled, places, workouts,
                ongoingArrivalMs = live.visit?.arrivalMs, nowMs = live.nowMs, traces = traces,
                roles = confirmedRoles(roles),
                rolePlaceIds = roleBearingPlaceIds(roles),
                ongoingMove = live.move?.toInput(),
                photos = readPhotos(reconciled),
            )
        }.flowOn(Dispatchers.Default)
    }

    /** Typed handle for [homeDaysSource]'s share, so [moment] can read `replayCache` without subscribing. */
    private val homeDaysShared: SharedFlow<List<Day>>? by lazy {
        probeScope?.let {
            homeDaysSource.shareIn(it, SharingStarted.WhileSubscribed(SHARE_STOP_TIMEOUT_MS), replay = 1)
        }
    }

    /**
     * The feed as last projected, or null if nothing has rendered it yet.
     *
     * ⚠ `replayCache`, deliberately, NOT `first()`: this must never *start* the upstream. If the
     * feed has not been projected, the caller falls through to its own read rather than waiting on
     * five sources it did not ask for.
     */
    private fun homeDaysReplay(): List<Day>? = homeDaysShared?.replayCache?.firstOrNull()

    /**
     * Places paired with the roles among them, as one source.
     *
     * ⚠ Paired rather than passed as a sixth `combine` argument: `combine` has typed overloads only
     * up to five, and the untyped vararg form would cost every lambda parameter its type.
     *
     * The **full** state rides along, unfiltered, because the two consumers want opposite widths:
     * [confirmedRoles] (what may be *displayed*) and [roleBearingPlaceIds] (what may not be
     * *swallowed*). Filtering here would have forced the veto to inherit the display rule.
     */
    private fun placesWithRoles(): Flow<Pair<List<PlaceInput>, Map<String, PlaceRoleState>>> =
        combine(placesFlow(), placeRolesFlow()) { places, roles -> places to roles }

    /**
     * `placeId → role token` for **confirmed** roles only (D9) — what the feed may render.
     *
     * ⚠⚠ A *suggested* role must never stand in for a place's name: the user has not answered yet,
     * and silently renaming their rows to "Home" on a guess is exactly the overreach the confirm
     * prompt exists to prevent.
     */
    private fun confirmedRoles(roles: Map<String, PlaceRoleState>): Map<String, String> =
        roles.filterValues { it.isConfirmed }.mapValues { it.value.role }

    /**
     * Every place carrying a role the user has *not dismissed* — confirmed or still a suggestion.
     *
     * Deliberately wider than [confirmedRoles], because it is only ever read as a **veto**: the
     * in-ride station-stop collapse must not swallow a place the inference pass already believes is
     * Home just because the confirm prompt is still unanswered. [PlaceRoleNames.NONE] is excluded —
     * paired with a user source it is a dismissal, which is a positive statement that the place has
     * no role and so no reason to protect it.
     */
    private fun roleBearingPlaceIds(roles: Map<String, PlaceRoleState>): Set<String> =
        roles.filterValues { it.role != PlaceRoleNames.NONE }.keys

    /** What live capture says is happening *right now*, plus the "now" that ticks it. */
    private data class LiveCapture(
        val visit: OngoingVisit?,
        val move: OngoingMove?,
        val nowMs: Long,
    )

    /**
     * The device-local live-capture state + a ticking "now" (§11.2 for the place you are at, §12.6
     * for the journey you are on): while either holds, re-emits every [LIVE_TICK_MS] so the current
     * row's duration counts up in the feed; when neither does, emits once and stops.
     *
     * ⚠ **The ticker is what makes a live duration free.** Nothing is written per minute — a visit
     * moment is written exactly twice (confirm + departure) and a movement exactly once (at close,
     * §9 Option A); the counting-up is recomputed from `now` at projection time. Rejecting
     * per-minute re-syncs was the whole point of §11.2, and the ongoing ride inherits it unchanged.
     *
     * ⚠ A move is dropped once its last fix is [ONGOING_MOVE_STALE_MS] old. The state cannot expire
     * itself — capture dying while fixes stop (indoors, permission revoked, an OEM kill) leaves a
     * perfectly well-formed `OngoingMove` that would otherwise count up for ever. The bound *is*
     * `maxResumeGapMs`, by construction rather than by coincidence: it is the same window the
     * segmenter uses to decide whether a journalled path is still the journey you are on, and a leg
     * we would not resume is not a leg we should still be showing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun liveCaptureFlow(): Flow<LiveCapture> =
        combine(OngoingVisitState.current, OngoingMoveState.current) { visit, move -> visit to move }
            .flatMapLatest { (visit, move) ->
                if (visit == null && move == null) {
                    flowOf(LiveCapture(null, null, 0L))
                } else {
                    flow {
                        while (true) {
                            val nowMs = Clock.System.now().toEpochMilliseconds()
                            val live = move?.takeIf { nowMs - it.lastFixMs <= ONGOING_MOVE_STALE_MS }
                            emit(LiveCapture(visit, live, nowMs))
                            // Nothing left to tick — stop the timer rather than spin for ever.
                            if (visit == null && live == null) return@flow
                            delay(LIVE_TICK_MS)
                        }
                    }
                }
            }

    /** The live ride as the projector's presentation input — confidence-gated mode included. */
    private fun OngoingMove.toInput() = TimelineProjection.OngoingMoveInput(
        startMs = startMs,
        distanceM = distanceMeters,
        mode = provisionalMode,
    )

    override fun boundTrips(): Flow<List<Trip>> =
        combine(tripsFlow(), momentsFlow(), placesFlow(), workoutsFlow()) { trips, moments, places, workouts ->
            val photos = readPhotos(moments)
            trips.map { TimelineProjection.trip(it, moments, places, workouts, photos = photos) }
        }.flowOn(Dispatchers.Default)

    override fun places(): Flow<List<Place>> =
        combine(placesFlow(), momentsFlow(), placeRolesFlow()) { places, moments, roles ->
            val live = reconcile(moments, places)
            val photos = readPhotos(live)
            places.map { p ->
                TimelineProjection.place(p, visitsOf(p.id, live), photos = photos).copy(role = roles[p.id])
            }
        }.flowOn(Dispatchers.Default)

    /**
     * Activities is a **cross-row aggregate over all history** — "how many of each kind, ever" —
     * so it reads the maintained [org.tenshu.core.health.WorkoutActivityRollup] (one row per
     * activity type, ~20 rows) rather than grouping the workout table.
     *
     * ⚠ It cannot use [workoutsFlow]: that read is windowed to the recent [WORKOUT_WINDOW], which
     * would silently under-count a lifetime total. Nor can it read the table unbounded — that is
     * precisely the read that stopped completing at ~10.5k workouts and blanked this tab. A count
     * is the canonical case for a precomputed row (`fit-read-scaling.md` Ph3/4).
     */
    override fun activities(): Flow<List<ActivityRollup>> =
        activityRollupsFlow()
            .map { TimelineProjection.activityRollups(it) }
            .flowOn(Dispatchers.Default)

    // Suggestions the user has resolved (accepted / dismissed) this session — a per-device concern
    // (§5.2), so it stays in-memory and never syncs. Filters resolved suggestions out of the feed.
    private val resolvedSuggestions = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The auto-detected trip suggestion (§5.2) — a **per-device** detector over the local moments,
     * **not** a synced read. `TripSuggester` derives home + clusters away-from-home journeys; the top
     * unresolved candidate drives the Interlude card. Accepting materialises a `Trip` (which *does*
     * sync, then converges via `TripMerger`); dismissing just hides it.
     */
    override fun tripSuggestion(): Flow<TripSuggestion?> =
        combine(
            momentsFlow(), placesFlow(), resolvedSuggestions, placeRolesFlow(),
        ) { moments, places, resolved, roles ->
            currentSuggestion(moments, places, resolved, roles)?.let { toPresentation(it, moments, places) }
        }.flowOn(Dispatchers.Default)

    /**
     * ⚠⚠ The one-shot reads below hop to [Dispatchers.Default], because their callers are
     * `viewModelScope.launch { … }` — i.e. **`Dispatchers.Main.immediate`**. The flows already got
     * this right via `flowOn(Dispatchers.Default)`; the suspend one-shots never did, and this
     * repository contained **zero** `withContext` calls.
     *
     * Not a theoretical tidy-up. These are not thin reads: [moment] deliberately projects the
     * *whole* feed to answer for one row (see its KDoc for why), so opening a single moment ran the
     * entire reconcile + merge-fold + day projection on the UI thread. Measured on device
     * 2026-08-04, tapping one row:
     *
     *     Choreographer: Skipped 155 frames!  The application may be doing too much work on its main thread.
     *     Choreographer: Skipped 99 frames!
     *
     * 155 frames is ~2.6 s of frozen UI — the reported "jittery loading indicator" was the spinner
     * being unable to animate, not anything about the spinner.
     *
     * ⚠ The IPC hop already moves to `Dispatchers.IO` inside the transport; what sat on Main is the
     * *deserialisation and projection* around it, which is CPU work and belongs on Default.
     */
    override suspend fun trip(id: String): Trip? = withContext(Dispatchers.Default) {
        val target = readTrips().firstOrNull { it.id == id } ?: return@withContext null
        val tripMoments = momentsFlowFresh().first()
        return@withContext TimelineProjection.trip(
            target, tripMoments, placesFlowFresh().first(), workoutsFlowFresh().first(),
            photos = readPhotos(tripMoments),
        )
    }


    /**
     * ⚠⚠ This deliberately projects the **whole feed** to answer for one row, because feed and
     * detail must agree: a merged visit has to surface the same span the feed showed, and roles
     * have to veto the same in-ride collapse, or the detail 404s on the very row the user tapped.
     *
     * So rather than re-deriving that agreement, [fresh] `false` reads the projection the feed
     * *already produced* — agreement becomes structural instead of recomputed, and the read costs
     * a list scan instead of five reads plus a full reconcile + merge-fold + day projection.
     *
     * ⚠ [fresh] `true` is required after a local write. The projection is replayed from a
     * `SharedFlow`, so by definition it predates the call; a mutate-then-reload (which is the very
     * next statement after every write in the detail view models) would otherwise re-read the
     * pre-write snapshot and show the write as having silently failed. The client's copy updates
     * only when the stream's IPC callback lands, which is a separate message from the write's
     * response with no ordering guarantee between them.
     *
     * ⚠ The fast path also falls through when the id is absent — a moment opened from a *trip*
     * detail need not appear in any day of the feed, and a deep link need not have a feed at all.
     * Falling back is always correct, never worse than before, and only ever costs the old path.
     */
    override suspend fun moment(id: String, fresh: Boolean): UiMoment? = withContext(Dispatchers.Default) {
        if (!fresh) {
            homeDaysReplay()?.flatMap { it.moments }?.firstOrNull { it.id == id }
                ?.let { return@withContext it }
        }
        val all = momentsFlowFresh().first()
        // The in-progress ride has no stored moment to find — it exists only as live capture state
        // (§12.6). Read it here too, or tapping the very row the feed just rendered would 404.
        val live = OngoingMoveState.current.value
            ?.takeIf { nowMs() - it.lastFixMs <= ONGOING_MOVE_STALE_MS }
        if (all.none { it.id == id } && id != TimelineProjection.ONGOING_MOVE_ID) return@withContext null
        // Project the full feed (not just this moment) so a merged visit surfaces its span +
        // contained movement — the tapped row carries the representative fragment's id (§2b).
        // Traces included, or opening a movement moment's detail would lose its mode + distance.
        // ⚠ Roles ride along too: they veto the in-ride station-stop collapse, and a detail that
        // judged it differently from the feed would 404 on the very row the user just tapped.
        val roles = placeRolesFlowFresh().first()
        val projected = TimelineProjection.days(
            all, placesFlowFresh().first(), workoutsFlowFresh().first(),
            nowMs = nowMs(),
            traces = tracesFlowFresh().first(),
            roles = confirmedRoles(roles),
            rolePlaceIds = roleBearingPlaceIds(roles),
            ongoingMove = live?.toInput(),
            // ⚠ The detail's photos come from the SAME projection as the feed's, so the row and the
            // screen it opens can never disagree about which photos belong to the moment.
            photos = readPhotos(all),
        )
        return@withContext projected.flatMap { it.moments }.firstOrNull { it.id == id }
    }


    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    override suspend fun place(id: String): Place? = withContext(Dispatchers.Default) {
        // A deep link (or a stale UI row) may name a place that has since been folded away; follow
        // the pointer rather than 404-ing on an id that is still perfectly meaningful.
        val resolved = PlaceMerge.resolveRoot(id, mergesFlowFresh().first())
        val target = readPlaces().firstOrNull { it.id == resolved } ?: return@withContext null
        // ⚠ Unlike [places], the detail carries the role even when it is only a SUGGESTION — this is
        // the one screen that asks the question, so it needs the un-filtered state.
        val role = placeRolesFlowFresh().first()[resolved]
        val visits = visitsOf(resolved, momentsFlowFresh().first())
        return@withContext TimelineProjection
            .place(target, visits, photos = readPhotos(visits))
            .copy(role = role)
    }


    override suspend fun movementRoute(traceId: String): List<GeoPoint> = movementRoute(listOf(traceId))

    /**
     * The decoded route of a whole journey (M5) — the single read that projects the polyline blob,
     * one-shot on detail open, so the feed's light [TRACE_FIELDS] stays blob-free (§2.1). The id
     * filter runs client-side (no registered search op on traces — the same row-lookup shape as
     * [renamePlace]); decimation bounds each blob, so the one-shot stays small.
     *
     * ⚠ [traceIds] may name several legs — a ride whose in-ride station stops the projector collapsed
     * away. They are read in ONE pass and concatenated **in the caller's order** (the projector emits
     * them in time order), not in store order; an unknown id contributes nothing.
     *
     * ⚠ **B4: a leg with a `SnappedRoute` is drawn from that instead** (§14) — the same journey,
     * corrected onto the rail geometry it ran on. The raw polyline is still read and is still the
     * fallback, per leg: a snapped route exists only for a leg that provably followed exclusive
     * track, so a mixed journey (rail leg + walk to the platform) draws each leg from the best path
     * it has. Nothing else changes — the substitution is invisible above this function, so the route
     * hero, the iOS sketch and the map fit all inherit it without a call-site change.
     */
    override suspend fun movementRoute(traceIds: List<String>): List<GeoPoint> = withContext(Dispatchers.Default) {
        if (traceIds.isEmpty()) return@withContext emptyList()
        val wanted = traceIds.toSet()
        return@withContext try {
            val raw = client.timelineService.traces
                .select { fields(SvcTrace.Fields.TRACE_ID, SvcTrace.Fields.POLYLINE) }
                .getAll()
                .foldTracked(emptyMap<String, List<GeoPoint>>()) { rows ->
                    decodeRoutes(rows.toList(), SvcTrace.Fields.TRACE_ID, SvcTrace.Fields.POLYLINE, wanted)
                }
            val snapped = client.timelineService.snappedRoutes
                .select { fields(SvcSnappedRoute.Fields.TRACE_ID, SvcSnappedRoute.Fields.POLYLINE) }
                .getAll()
                .foldTracked(emptyMap<String, List<GeoPoint>>()) { rows ->
                    decodeRoutes(
                        rows.toList(),
                        SvcSnappedRoute.Fields.TRACE_ID,
                        SvcSnappedRoute.Fields.POLYLINE,
                        wanted,
                    )
                }
            // The substitution, in one line: a leg that has a corrected route is drawn from it, and
            // every other leg falls back to the raw path it was captured with.
            traceIds.flatMap { snapped[it] ?: raw[it].orEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dead dispatch may throw rather than fold (see [unreachable]); the hero just stays stats-only.
            reach.markUnreachable()
            emptyList()
        }
    }

    // ── Photos: Drive files joined to moments by time (timeline-photos.md T-P1…T-P3) ──────────

    /**
     * Every photo taken across [moments]' combined span, for the projection to hand out.
     *
     * ⚠⚠ **One read per projection, not one per moment.** This is the whole point of T-P2's op
     * shape: the caller names the span it is about to render and the proxy scans its own store
     * once, so a feed of forty rows costs one walk rather than forty. The projection then assigns
     * each photo to the moment whose window contains it — which is also what keeps a row and the
     * detail it opens in agreement, since both read the same assignment.
     *
     * ⚠ Capped at [PHOTO_SCAN_CAP]. A realm's whole history can span years, and the op orders by
     * provenance then recency, so a truncated answer drops the oldest and least-likely-wanted
     * first. A moment old enough to fall past the cap shows no photos rather than wrong ones.
     */
    private suspend fun readPhotos(moments: List<MomentInput>): List<TimelineProjection.PhotoInput> {
        if (moments.isEmpty()) return emptyList()
        val startMs = moments.minOf { it.timestampMs }
        val endMs = moments.maxOf { it.timestampMs + it.durationSec * 1_000L } + MIN_PHOTO_WINDOW_MS
        return try {
            client.fileService.defaultFileSystem
                .photosCapturedBetween(startMs = startMs, endMs = endMs, limit = PHOTO_SCAN_CAP)
                .foldTracked(emptyList()) { photos ->
                    photos.toList().map {
                        TimelineProjection.PhotoInput(
                            path = it.path.get(),
                            capturedAtMs = it.capturedAt.get(),
                            backedUp = it.backedUp.get(),
                            latE7 = it.latE7.get(),
                            lngE7 = it.lngE7.get(),
                        )
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A feed that cannot read photos is a feed without photos, never a feed that fails to
            // render — the same posture every other optional join here takes.
            reach.markUnreachable()
            emptyList()
        }
    }

    /**
     * Preview bytes for one photo, by path — Drive's own two-step, because there is only one
     * correct way to read this property and Timeline does not get to invent a second.
     *
     * `eager(...)` is load-bearing: [File.Preview] is deferred-by-default, so a one-shot `.get()`
     * without the override resolves to `null` every time. And when the answer really is `null`,
     * the stored thumbnail may simply not be on this device yet — [File.Thumbnail] is `OnDemand`
     * since C7 — so we project it once to pull the blob into the local cache and ask again.
     */
    override suspend fun photoBytes(
        uri: String,
        width: Int,
        height: Int,
        heroFidelity: Boolean,
    ): ByteArray? =
        withContext(Dispatchers.Default) {
            if (uri.isBlank()) return@withContext null
            return@withContext try {
                // ⚠⚠ **X-6: the hero rung is named BEFORE `File.Preview`, because the old order
                // could never reach it.** `cachePhotoThumbnails` — the only thing that pulled
                // `XLARGE` — fired solely when `readPhotoPreview` came back `null`, and on a
                // byte-less device it is not null by the time a moment opens: the feed has already
                // pulled `LIST` + `LARGE`, so `File.Preview` answers a 1600 px request from the
                // 256 px class. The rung stayed on the content plane and every hero was a ~6x
                // upscale. Identical to the defect `drive-synced-thumbnails.md` §16.2 fixed in
                // Photos; this is the copy that was left behind (`drive-xxlarge-thumbnail-rung.md`
                // §4.4, X-6).
                if (heroFidelity &&
                    asksForHeroByName(maxOf(width, height), uri) { holdsOriginal(it) }
                ) {
                    heroRung(uri, maxOf(width, height))?.let { hero ->
                        // ⚠⚠⚠ **The small classes must still be pulled, and this return is the
                        // only path that would skip them.** `cachePhotoThumbnails` below is the
                        // only thing in Timeline that fetches `LIST` + `LARGE`, and it is gated on
                        // `readPhotoPreview` being null — which, once `XXLARGE` is local, it never
                        // is again. So returning here without this left the photo holding *only*
                        // the hero class, permanently.
                        //
                        // That is not a missing-thumbnail bug, it is an amplification one:
                        // `StoredThumbnailLadder.covering` serves the smallest locally-present
                        // covering class **verbatim, with no downscale**, so every later 256 px
                        // feed row for this photo was answered with the 2048 px blob — ~33x the
                        // bytes over IPC and a ~12.6 MB ARGB decode instead of a 256 px one, for
                        // the life of the cache. Measured at 20.7x on the test fixture.
                        //
                        // ⚠ After the hero read, not before: when the hero is null we fall through
                        // to the path below, which pulls them anyway. Local-first, so a warm photo
                        // pays nothing; a cold one pays ~19 KB against the 0.5 MB it just fetched.
                        cachePhotoThumbnails(uri, heroFidelity = false)
                        return@withContext hero
                    }
                }
                readPhotoPreview(uri, width, height) ?: run {
                    cachePhotoThumbnails(uri, heroFidelity = heroFidelity)
                    readPhotoPreview(uri, width, height)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reach.markUnreachable()
                null
            }
        }

    /** O(1), no payload read. ⚠ A failed probe reads as *not held*, which only costs a pull. */
    private suspend fun holdsOriginal(path: String): Boolean =
        client.fileService.defaultFileSystem.hasLocalData(path).getOrNull()?.get() == true

    /**
     * The hero class itself — `XXLARGE` first, `XLARGE` behind it (§4.4). Selecting the relation
     * answers from the local copy when there is one and pulls it otherwise.
     *
     * ⚠⚠ `XXLARGE` only above [GetStoredThumbnail.XLARGE_PX]: it is ~0.5 MB on the wire, so naming
     * it for anything tile-sized is §14.2's trap at three times the cost — *never a grid tile,
     * never a feed row*. Timeline's hero asks for 1600 px, which clears it; the band's own rows and
     * the feed never call this.
     *
     * ⚠ The `XLARGE` arm is not a failure path: a photograph uploaded before X-1, in a realm no
     * device has backfilled, has an `XLARGE` and no `XXLARGE`.
     *
     * `null` when no device ever minted either, or the fetch failed — the caller then falls back to
     * whatever `File.Preview` has locally, which is what master always drew.
     */
    private suspend fun heroRung(path: String, requestPx: Int): ByteArray? {
        if (requestPx > GetStoredThumbnail.XLARGE_PX) {
            namedRung(path, File.ThumbnailXXLarge)?.let { return it }
        }
        return namedRung(path, File.ThumbnailXLarge)
    }

    /** Reads one stored class by name — local-first, pulling it when this device has none. */
    private suspend fun namedRung(
        path: String,
        property: Property.Dynamic<TByteArray>,
    ): ByteArray? =
        client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select { relations(eager(property)) }
            .resolve()
            .getOrNull()
            ?.get(property)
            ?.getOrNull()

    private suspend fun readPhotoPreview(path: String, width: Int, height: Int): ByteArray? =
        client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select { relations(eager(File.Preview(width = width, height = height))) }
            .resolve()
            .getOrNull()
            ?.get(File.Preview)
            ?.getOrNull()

    /**
     * Pull this file's thumbnail blobs into the local cache — tier 3, so losing them is a re-fetch.
     *
     * ⚠ [heroFidelity] adds the `XLARGE` rung; `TimelineRepository.photoBytes` carries why it is a
     * flag and not a size threshold.
     */
    private suspend fun cachePhotoThumbnails(path: String, heroFidelity: Boolean) {
        client.fileService.defaultFileSystem.root
            .files
            .navigateTo(path.substringBeforeLast('/'))
            .search<File> { this.path equalTo path }
            .select {
                if (heroFidelity) {
                    relations(File.Thumbnail, File.ThumbnailLarge, File.ThumbnailXLarge)
                } else {
                    relations(File.Thumbnail, File.ThumbnailLarge)
                }
            }
            .resolve()
    }


    /**
     * Decode a `traceId`-keyed relation's polyline blobs for the [wanted] ids.
     *
     * ⚠⚠ **`MovementTracePolyline`, not `LonLatPolyline`.** Both rows here store the *captured-path*
     * codec (lat/lng/time/accuracy); `LonLatPolyline` is the lon/lat-only wire codec for rail linework
     * coming back from the proxy. Decoding one with the other yields plausible-looking garbage rather
     * than an error, and a previous slice did exactly that.
     */
    private fun decodeRoutes(
        rows: List<Entity<*>>,
        idField: String,
        polylineField: String,
        wanted: Set<String>,
    ): Map<String, List<GeoPoint>> = rows
        .filter { it.getFieldValue<TString>(idField).get() in wanted }
        .associate { row ->
            row.getFieldValue<TString>(idField).get() to
                MovementTracePolyline
                    .decode(row.getFieldValue<TByteArray>(polylineField).get())
                    .map { f -> GeoPoint(latitude = f.latE7 / 1e7, longitude = f.lngE7 / 1e7) }
        }

    /**
     * User-authored rename (place-geocoding.md G4): a `recordPlace` upsert carrying the new
     * label and preserving everything else; the enricher's blank-only rule (D3) then protects
     * it forever. No-op without a [context] (the iOS EPC client constructs this repository
     * read-only until G5) and for blank input.
     */
    override suspend fun renamePlace(id: String, label: String) {
        val ctx = context ?: return
        val newLabel = label.trim()
        if (newLabel.isEmpty()) return
        val rows = client.placeService.places.select { fields(*PLACE_FIELDS) }.flow().first()
            .fold(onSuccess = { it.toList() }, onFailure = { emptyList() })
        val row = rows.firstOrNull { it.getFieldValue<TString>(SvcPlace.Fields.PLACE_ID).get() == id } ?: return
        client.placeService.recordPlace(
            SvcPlace.create(
                context = ctx,
                placeId = id,
                latE7 = row.getFieldValue<TInteger>(SvcPlace.Fields.LAT_E7).get(),
                lngE7 = row.getFieldValue<TInteger>(SvcPlace.Fields.LNG_E7).get(),
                capturedAt = row.getFieldValue<UnixInstant>(SvcPlace.Fields.CAPTURED_AT),
                label = newLabel,
                city = row.getFieldValue<TString>(SvcPlace.Fields.CITY).get(),
                countryCode = row.getFieldValue<TString>(SvcPlace.Fields.COUNTRY_CODE).get(),
                // 4-C: a rename (and the place picker, which routes here) is user-authored — the
                // enricher must never overwrite it, and it survives repeat/Wi-Fi-recognised visits.
                labelSource = LabelSource.USER,
            ),
        )
        // F2 (§8.4): naming a place while standing in it is the strongest association the user can
        // give — bind the network to it, so the next visit on *any* of this venue's access points
        // recognises the name instead of minting a neighbouring place. No-op when consent is off.
        wifi?.bindCurrentNetwork(id)
    }

    /**
     * The user's answer to a role prompt — or their dismissal of it, which is the same write with
     * [PlaceRoleNames.NONE] (`place-roles.md` D1). Always recorded as **user-authored**, so the
     * inference pass leaves it alone from here on and `PlaceRoleMerger` protects it across devices.
     *
     * No-op without a [context], matching [renamePlace] (the iOS EPC client is read-only).
     */
    override suspend fun setPlaceRole(placeId: String, role: String) {
        val ctx = context ?: return
        if (placeId.isBlank()) return
        client.placeService.recordPlaceRole(
            SvcPlaceRole.create(
                context = ctx,
                placeId = placeId,
                role = role,
                roleSource = SvcRoleSource.USER,
                decidedAt = UnixInstantDto(Clock.System.now().toEpochMilliseconds()),
            ),
        )
    }

    /**
     * Manual merge (§9.4). The survivor is the **deterministic root** — `min(a, b)` — not whichever
     * the user tapped, so a hand-made merge lands on exactly the row the proxy's automatic pass would
     * have chosen and the two can never contradict each other. The loser's user-authored name travels
     * to the survivor, or merging would hide the very thing the user typed.
     */
    override suspend fun mergePlaces(a: String, b: String): String {
        val ctx = context ?: return a
        if (a == b || a.isBlank() || b.isBlank()) return a
        val root = minOf(a, b)
        val folded = maxOf(a, b)

        val places = readPlaces().associateBy { it.id }
        val rootPlace = places[root]
        val foldedPlace = places[folded]
        // Carry the name across BEFORE recording the merge, so no read can observe the survivor
        // unnamed while the other row is already hidden.
        if (rootPlace != null && foldedPlace != null &&
            rootPlace.label.isBlank() && foldedPlace.label.isNotBlank()
        ) {
            renamePlace(root, foldedPlace.label)
        }
        client.placeService.recordPlaceMerge(
            SvcPlaceMerge.create(
                context = ctx,
                placeId = folded,
                mergedInto = root,
                mergedAt = UnixInstantDto(Clock.System.now().toEpochMilliseconds()),
            ),
        )
        return root
    }

    /**
     * Undo a merge (§9.4) by writing a **blank** target rather than deleting the row: the row is the
     * carrier of the decision, and `PlaceMergeMerger` reads a newer blank as "the user says these are
     * different places" — which is what makes an unmerge survive a peer that still holds the merge.
     */
    override suspend fun unmergePlace(placeId: String) {
        val ctx = context ?: return
        client.placeService.recordPlaceMerge(
            SvcPlaceMerge.create(
                context = ctx,
                placeId = placeId,
                mergedInto = "",
                mergedAt = UnixInstantDto(Clock.System.now().toEpochMilliseconds()),
            ),
        )
    }

    override suspend fun mergedPlaceCount(placeId: String): Int =
        mergesFlowFresh().first().count { (_, target) -> target == placeId }

    /**
     * Nearby place-name candidates for the picker (§4) — proxy-mediated Photon egress, best first.
     * A failed op or blank result yields an empty list (the picker just shows free-text then).
     */
    override suspend fun placeSuggestions(latE7: Long, lngE7: Long): List<String> =
        // ⚠ Consent-gated: with place-name lookups off the picker is free text only.
        geocodingEgress.placeCandidates(latE7, lngE7)

    /** "Not a trip" — hide the current suggestion (per-device, never synced). */
    override suspend fun dismissTripSuggestion() {
        val current = currentSuggestion(momentsFlowFresh().first(), placesFlowFresh().first(), resolvedSuggestions.value)
        if (current != null) resolvedSuggestions.update { it + current.suggestedTripId }
    }

    /** "Read as a trip" — materialise the current suggestion as a `Trip` with its membership, then hide it. */
    override suspend fun acceptTripSuggestion() {
        val moments = momentsFlowFresh().first()
        val current = currentSuggestion(moments, placesFlowFresh().first(), resolvedSuggestions.value) ?: return
        val context = client.environment.tContext
        client.timelineService.recordTrip(
            SvcTrip.create(
                context = context,
                tripId = current.suggestedTripId,
                title = current.title,
                dateRange = formatDateRange(current.startMs, current.endMs),
                year = formatYear(current.startMs),
                membership = current.memberMomentIds,
            ),
        )
        resolvedSuggestions.update { it + current.suggestedTripId }
    }

    // ── trip suggestion (per-device, §5.2) ──────────────────────────────────

    /** The top unresolved trip suggestion over the device's Visit moments (place-subjected). */
    private fun currentSuggestion(
        moments: List<MomentInput>,
        places: List<PlaceInput>,
        resolved: Set<String>,
        roles: Map<String, PlaceRoleState> = emptyMap(),
    ): ServiceTripSuggestion? {
        val placeById = places.associateBy { it.id }
        // PR-4: a place the user confirmed as `home` beats the most-frequented-cell heuristic. Only a
        // CONFIRMED role — a suggestion is the machine's guess, and feeding one guess into another is
        // how a wrong home quietly becomes a wrong trip.
        val storedHome = roles.entries
            .firstOrNull { (_, r) -> r.isConfirmed && r.role == PlaceRoleNames.HOME }
            ?.let { (placeId, _) -> placeById[placeId] }
            ?.let { it.latE7 to it.lngE7 }
        val inputs = moments
            .filter { it.subjectKind == TimelineProjection.Subject.PLACE }
            .mapNotNull { m ->
                val p = placeById[m.subjectId] ?: return@mapNotNull null
                TripSuggestionInput(m.id, m.timestampMs, p.latE7, p.lngE7, p.label)
            }
        return TripSuggester.suggest(inputs, storedHome = storedHome)
            .firstOrNull { it.suggestedTripId !in resolved }
    }

    private fun toPresentation(
        s: ServiceTripSuggestion,
        moments: List<MomentInput>,
        places: List<PlaceInput>,
    ): TripSuggestion {
        val memberIds = s.memberMomentIds.toSet()
        val placesCount = moments
            .filter { it.id in memberIds && it.subjectKind == TimelineProjection.Subject.PLACE }
            .map { it.subjectId }
            .distinct()
            .count()
        return TripSuggestion(
            id = s.suggestedTripId,
            title = s.title,
            dateRange = formatDateRange(s.startMs, s.endMs),
            daysSpan = ((s.endMs - s.startMs) / DAY_MS).toInt() + 1,
            placesCount = placesCount,
            photosCount = 0, // no captured photos yet — a later slice
            cover = Photo(dominantColor = SUGGESTION_COVER_COLOR),
            confidence = 1.0,
        )
    }

    private fun formatDateRange(startMs: Long, endMs: Long): String {
        val zone = TimeZone.currentSystemDefault()
        val start = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(zone).date
        val end = Instant.fromEpochMilliseconds(endMs).toLocalDateTime(zone).date
        return if (start.month == end.month && start.year == end.year) {
            "${monthAbbrev(start.month)} ${start.day} – ${end.day}"
        } else {
            "${monthAbbrev(start.month)} ${start.day} – ${monthAbbrev(end.month)} ${end.day}"
        }
    }

    private fun formatYear(startMs: Long): String =
        Instant.fromEpochMilliseconds(startMs).toLocalDateTime(TimeZone.currentSystemDefault()).year.toString()

    private fun monthAbbrev(m: Month): String =
        m.name.take(3).lowercase().replaceFirstChar { it.uppercaseChar() }

    // ── per-service read flows (input extraction, empty on failure) ──────────

    /**
     * Moments, with every `Visit` subject **resolved to its surviving place** (P4f, §10.11) — so a
     * folded row's visits count towards, and are labelled by, the place that survives.
     */
    /** A fresh, unshared read — see [shared] for why one-shot readers must not share. */
    private fun momentsFlowFresh(): Flow<List<MomentInput>> =
        combine(rawMomentsFlowFresh(), mergesFlowFresh()) { moments, merges ->
            PlaceMergeFolding.repointToRoots(moments, merges)
        }

    private val momentsFlowShared: Flow<List<MomentInput>> by lazy { momentsFlowFresh().shared() }

    private fun momentsFlow(): Flow<List<MomentInput>> = momentsFlowShared
    /**
     * Share ONE upstream across every consumer of a read.
     *
     * ⚠⚠ Without this each caller of `momentsFlow()` / `placesFlow()` / … built its **own** flow, so
     * the same rows were fetched from the proxy once per consumer. Measured on device 2026-08-04
     * during a single Timeline Home load: `moments` subscribed **4×**, `places` **4×**, `workouts`
     * **2×**, and the whole render cost **100,000** `getObject` calls to show 508 rows — each
     * workout row read 4 times, each moment ~40, each place ~210. `combine` multiplies it further,
     * because it re-runs on every source emission and several combines share these sources.
     *
     * `replay = 1` is what makes a late consumer cheap: it gets the last value immediately instead
     * of triggering a fresh read, which is also why `combine` now completes sooner (it emits only
     * once every source has emitted at least once).
     *
     * `WhileSubscribed` with a stop timeout, not `Eagerly`: a repository that reads forever whether
     * or not anything is on screen is the opposite of the fix. The timeout keeps the upstream alive
     * across a screen change so navigation does not re-read.
     *
     * ⚠ [probeScope] absent (tests, previews) ⇒ **no sharing**, and every flow behaves exactly as it
     * did before. Sharing needs a scope to outlive the individual collectors, and a test that owns
     * no scope should not silently acquire process-lifetime state.
     */
    private fun <T> Flow<T>.shared(): Flow<T> {
        val scope = probeScope ?: return this
        return shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_TIMEOUT_MS), replay = 1)
    }

    /** A fresh, unshared read — see [shared] for why one-shot readers must not share. */
    private fun rawMomentsFlowFresh(): Flow<List<MomentInput>> =
        reach.resubscribing {
            client.timelineService.moments.select { fields(*MOMENT_FIELDS) }.flow()
                .map { r -> r.foldTracked(emptyList()) { it.toList().map(::momentInput) } }
                .retrying("moments")
        }

    private val rawMomentsFlowShared: Flow<List<MomentInput>> by lazy { rawMomentsFlowFresh().shared() }

    private fun rawMomentsFlow(): Flow<List<MomentInput>> = rawMomentsFlowShared
    /**
     * Places, **folded** (P4f, §10.11): a row merged into another (`PlaceMerge`) is dropped here, so
     * one venue reads as one place everywhere — no consumer needs to know merging exists.
     */
    /** A fresh, unshared read — see [shared] for why one-shot readers must not share. */
    private fun placesFlowFresh(): Flow<List<PlaceInput>> =
        combine(rawPlacesFlowFresh(), mergesFlowFresh()) { places, merges ->
            PlaceMergeFolding.rootsOnly(places, merges)
        }

    private val placesFlowShared: Flow<List<PlaceInput>> by lazy { placesFlowFresh().shared() }

    private fun placesFlow(): Flow<List<PlaceInput>> = placesFlowShared
    /** A fresh, unshared read — see [shared] for why one-shot readers must not share. */
    private fun rawPlacesFlowFresh(): Flow<List<PlaceInput>> =
        reach.resubscribing {
            client.placeService.places.select { fields(*PLACE_FIELDS) }.flow()
                .map { r -> r.foldTracked(emptyList()) { it.toList().map(::placeInput) } }
                .retrying("places")
        }

    private val rawPlacesFlowShared: Flow<List<PlaceInput>> by lazy { rawPlacesFlowFresh().shared() }

    private fun rawPlacesFlow(): Flow<List<PlaceInput>> = rawPlacesFlowShared
    /**
     * `placeId → mergedInto` (P4f). Tiny — one row per folded place, and most users have none.
     *
     * ⚠⚠ **Starts by emitting empty and never propagates a failure.** This flow is `combine`d into
     * both the place and moment reads, and `combine` emits nothing until *every* source has emitted:
     * a merges read that stalled or threw would blank Home and Places entirely. That is not a
     * hypothetical — one silent `combine` source is exactly what blanked Timeline Home at ~10.5k
     * workouts. An absent merge map degrades to "nothing is merged", which is the pre-P4f world.
     */
    /** A fresh, unshared read — see [shared] for why one-shot readers must not share. */
    private fun mergesFlowFresh(): Flow<Map<String, String>> =
        reach.resubscribing {
            client.placeService.merges.select { fields(*PLACE_MERGE_FIELDS) }.flow()
                .map { r ->
                    r.foldTracked(emptyMap<String, String>()) { rows ->
                        rows.toList().associate {
                            it.getFieldValue<TString>(SvcPlaceMerge.Fields.PLACE_ID).get() to
                                it.getFieldValue<TString>(SvcPlaceMerge.Fields.MERGED_INTO).get()
                        }
                    }
                }
                .retrying("placeMerges")
        }.onStart { emit(emptyMap()) }

    private val mergesFlowShared: Flow<Map<String, String>> by lazy { mergesFlowFresh().shared() }

    private fun mergesFlow(): Flow<Map<String, String>> = mergesFlowShared
    /**
     * `placeId → (role, isSuggestion)` — one row per place that has ever been given or offered a
     * role, so tiny by construction rather than by windowing.
     *
     * ⚠⚠ **Starts by emitting empty and never propagates a failure**, for the same reason
     * [mergesFlow] does: this is `combine`d into the place and moment reads, `combine` emits nothing
     * until every source has, and one silent source is exactly what blanked Timeline Home at ~10.5k
     * rows. An absent role map degrades to "no place has a role" — the pre-PR-1 world, which every
     * surface still renders correctly.
     */
    /** A fresh, unshared read — see [shared] for why one-shot readers must not share. */
    private fun placeRolesFlowFresh(): Flow<Map<String, PlaceRoleState>> =
        reach.resubscribing {
            client.placeService.roles.select { fields(*PLACE_ROLE_FIELDS) }.flow()
                .map { r ->
                    r.foldTracked(emptyMap<String, PlaceRoleState>()) { rows ->
                        rows.toList().associate { row ->
                            row.getFieldValue<TString>(SvcPlaceRole.Fields.PLACE_ID).get() to
                                PlaceRoleState(
                                    role = row.getFieldValue<TString>(SvcPlaceRole.Fields.ROLE).get(),
                                    // ⚠ Anything not exactly `user` is a suggestion — degrade to the
                                    // weaker reading, so a value this build does not recognise can
                                    // never be rendered as something the user actually said.
                                    isSuggestion = !SvcRoleSource.isUser(
                                        row.getFieldValue<TString>(SvcPlaceRole.Fields.ROLE_SOURCE).get()
                                    ),
                                )
                        }
                    }
                }
                .retrying("placeMerges")
        }.onStart { emit(emptyMap()) }

    private val placeRolesFlowShared: Flow<Map<String, PlaceRoleState>> by lazy { placeRolesFlowFresh().shared() }

    private fun placeRolesFlow(): Flow<Map<String, PlaceRoleState>> = placeRolesFlowShared
    /**
     * The most recent [WORKOUT_WINDOW] workouts, newest-first — the **join input** for the feed
     * (an `Activity` moment's subtitle, a day card's workout), never a listing in its own right.
     *
     * ⚠ This read must not scale with total history. Unbounded, it is what blanked Home: `combine`
     * emits nothing until *every* source has emitted, so the one read that never completed took
     * the whole screen with it — at ~10.5k workouts (a full Health-Connect history import) the
     * scan degraded quadratically (200 rows ≈ 2 s, 2 000 ≈ 338 s, 10.5k never finished, pegging
     * the proxy's CPU). The four healthy sources made it look like every read was broken.
     *
     * `sortByDescending { start }` + `take` + `select` is the ordered-window-fusion shape
     * (`core-ordered-window-fusion.md`): ONE projected pass with a bounded top-N, not a full-store
     * sort. The lambda selector is deliberate — the `sortByDescending(Workout.Start)` property
     * overload cannot round-trip on the wire (`fit-app.md` §9.17), exactly as
     * `FitProjectionRepository.buildRecentWorkoutInputs` documents.
     *
     * ⚠ Known bound: an `Activity` moment older than the window resolves to an empty subtitle
     * rather than its activity name. The feed is recency-ordered, so this only bites when
     * scrolling far back with a very dense workout history; widening the window is a constant,
     * and the real answer is paging the feed itself.
     */
    /**
     * The workouts the feed's moments actually reference — **read by id, not scanned**.
     *
     * ⚠⚠ This was `sortByDescending { start }.take(300)` over the whole table, and the projector
     * uses the result for exactly one thing: `workouts.associate { it.id to it.activityType }`, an
     * id→label lookup for the handful of `Activity` moments on screen (`subtitleResolver`). So the
     * window was fetching ~300 rows, and *scanning* all of them, to answer a dozen lookups.
     * Measured on device 2026-08-04 at 10,232 workouts: that one source took **11,487 ms** of a
     * ~11.5 s Home render and set the whole screen's floor, because `combine`'s sources run
     * concurrently and the slowest one wins. `search { workoutId equalTo … }` takes the
     * `trySearchByKey` fast path — a direct `DataIdProvider` hash lookup, no scan — so the cost
     * becomes O(referenced moments) instead of O(all workouts), and **zero reads** when the feed
     * has no `Activity` moment at all.
     *
     * ⚠ It also fixes a latent bug the window caused: a moment referencing the 301st-newest workout
     * fell outside `take(300)`, so `workoutLabels[…]` missed and the card rendered a silently blank
     * subtitle. Reading by id has no window, so older Activity moments keep their label.
     *
     * ⚠ Derived from [momentsFlow], which is shared — so this adds no extra moments read; it
     * re-resolves only when the referenced id *set* changes, not on every moment emission.
     */
    private fun workoutsFlowFresh(): Flow<List<WorkoutInput>> = workoutsFor(momentsFlowFresh())

    /**
     * ⚠ The shared variant derives from the **shared** moments upstream, not the fresh one. Built
     * from `workoutsFlowFresh()` it would drive a second, unshared full moments read for every Home
     * render — silently undoing the sharing this sits next to.
     */
    private fun workoutsFor(moments: Flow<List<MomentInput>>): Flow<List<WorkoutInput>> =
        moments.map { referencedWorkoutIds(it) }.distinctUntilChanged()
            .map { ids -> workoutsByIds(ids) }

    /** The workout ids the feed will actually look up — `Activity` moments' subjects. */
    private fun referencedWorkoutIds(moments: List<MomentInput>): Set<String> =
        moments.asSequence()
            .filter { it.subjectKind == TimelineProjection.Subject.WORKOUT }
            .map { it.subjectId }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * One keyed read per id. Serial and unbatched by design: the set is the number of `Activity`
     * moments on screen (single digits in practice), and each read is an O(1) key lookup, so a
     * batching op would be new permanent wire surface for no measurable gain. A row that cannot be
     * read contributes nothing — the subtitle falls back to `""`, exactly as a dangling reference
     * already did.
     */
    private suspend fun workoutsByIds(ids: Set<String>): List<WorkoutInput> {
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull { id ->
            try {
                client.healthService.workouts
                    .search<SvcWorkout> { workoutId equalTo id }
                    .select { fields(*WORKOUT_FIELDS) }
                    .resolve()
                    .foldTracked(null as Entity<SvcWorkout>?) { it }
                    ?.let(::workoutInput)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A dead dispatch may throw rather than fold (see [unreachable]).
                reach.markUnreachable()
                null
            }
        }
    }

    private val workoutsFlowShared: Flow<List<WorkoutInput>> by lazy { workoutsFor(momentsFlow()).shared() }

    private fun workoutsFlow(): Flow<List<WorkoutInput>> = workoutsFlowShared
    /**
     * Movement traces — the subject a `MOVEMENT` moment points at. Read for the feed card's mode +
     * distance; the `polyline` blob is deliberately **not** projected (the route surface is M5).
     */
    /** A fresh, unshared read — see [shared] for why one-shot readers must not share. */
    private fun tracesFlowFresh(): Flow<List<TraceInput>> =
        reach.resubscribing {
            client.timelineService.traces.select { fields(*TRACE_FIELDS) }.flow()
                .map { r -> r.foldTracked(emptyList()) { it.toList().map(::traceInput) } }
                .map { traces -> traces.map { withTransitStops(it) } }
                .retrying("traces")
        }

    private val tracesFlowShared: Flow<List<TraceInput>> by lazy { tracesFlowFresh().shared() }

    private fun tracesFlow(): Flow<List<TraceInput>> = tracesFlowShared
    /**
     * The maintained per-activity workout counts — ~one row per activity type, so this read does
     * not scale with workout history at all. The rollup is locally derived on every device by
     * `WorkoutActivityRollupMaintainer`; nothing here has to know that.
     */
    /** A fresh, unshared read — see [shared] for why one-shot readers must not share. */
    private fun activityRollupsFlowFresh(): Flow<List<ActivityCountInput>> =
        reach.resubscribing {
            client.healthService.workoutActivityRollups
                .select { fields(*ACTIVITY_ROLLUP_FIELDS) }.flow()
                .map { r -> r.foldTracked(emptyList()) { it.toList().map(::activityCountInput) } }
                .retrying("workoutActivityRollups")
        }

    private val activityRollupsFlowShared: Flow<List<ActivityCountInput>> by lazy { activityRollupsFlowFresh().shared() }

    private fun activityRollupsFlow(): Flow<List<ActivityCountInput>> = activityRollupsFlowShared
    /** A fresh, unshared read — see [shared] for why one-shot readers must not share. */
    private fun tripsFlowFresh(): Flow<List<TripInput>> =
        reach.resubscribing {
            client.timelineService.trips.select { fields(*TRIP_FIELDS) }.flow()
                .map { r -> r.foldTracked(emptyList()) { it.toList().map(::tripInput) } }
                .retrying("trips")
        }

    private val tripsFlowShared: Flow<List<TripInput>> by lazy { tripsFlowFresh().shared() }

    private fun tripsFlow(): Flow<List<TripInput>> = tripsFlowShared
    private suspend fun readTrips(): List<TripInput> = tripsFlowFresh().first()

    private suspend fun readPlaces(): List<PlaceInput> = placesFlowFresh().first()

    /** The `Visit`-moments referencing [placeId] — the source of a place's derived history. */
    private fun visitsOf(placeId: String, moments: List<MomentInput>): List<MomentInput> =
        moments.filter { it.subjectKind == TimelineProjection.Subject.PLACE && it.subjectId == placeId }

    /**
     * Cross-source reconciliation for the read model (I6, `timeline-ingestion.md` §7.1): (a) drop
     * moments a reconciliation pass folded into a canonical (`supersededBy` set — superseded, not
     * tombstoned), and (b) collapse *near*-duplicates the sync key layer can't — the same visit/
     * activity seen by multiple sources (same place cell + time bucket / same workout) — to their
     * canonical via [MomentReconciler], so a surface shows **one moment per real-world moment**.
     * `Visit` cells need coordinates, resolved here from the place join.
     */
    private fun reconcile(moments: List<MomentInput>, places: List<PlaceInput>): List<MomentInput> {
        val live = moments.filter { it.supersededBy.isEmpty() }
        val placeById = places.associateBy { it.id }
        val reconcilable = live.map { m ->
            val p = if (m.subjectKind == TimelineProjection.Subject.PLACE) placeById[m.subjectId] else null
            ReconcilableMoment(
                momentId = m.id,
                kind = m.kind,
                startMs = m.timestampMs,
                latE7 = p?.latE7 ?: 0,
                lngE7 = p?.lngE7 ?: 0,
                subjectKind = m.subjectKind,
                subjectId = m.subjectId,
                // Without this the reconciler's source-priority tie-break had no signal at all and
                // silently fell through to earliest-start (§12.3). A moment carries one tag in
                // practice (its writer's); take the first deterministically if a merge unioned more.
                source = m.sources.firstOrNull() ?: "",
            )
        }
        val canonicalIds = MomentReconciler.reconcile(reconcilable).map { it.canonicalMomentId }.toSet()
        return live.filter { it.id in canonicalIds }
    }

    // ── entity → input extraction (Entity accessors read the materialised fields) ──

    private fun momentInput(e: Entity<out SvcMoment>): MomentInput {
        val subject = e.getFieldValue<SvcSubjectRef>(SvcMoment.Fields.SUBJECT)
        return MomentInput(
            id = e.getFieldValue<TString>(SvcMoment.Fields.MOMENT_ID).get(),
            timestampMs = e.getFieldValue<UnixInstant>(SvcMoment.Fields.TIMESTAMP).toEpochMilliseconds(),
            kind = e.getFieldValue<TString>(SvcMoment.Fields.KIND).get(),
            title = e.getFieldValue<TString>(SvcMoment.Fields.TITLE).get(),
            durationSec = e.getFieldValue<TInteger>(SvcMoment.Fields.DURATION_SEC).get(),
            subjectKind = subject.kind.get(),
            subjectId = subject.id.get(),
            supersededBy = e.getFieldValue<TString>(SvcMoment.Fields.SUPERSEDED_BY).get(),
            sources = sourcesOf(e),
        )
    }

    /**
     * Reads the moment's provenance tags from the materialised nested-composite list field. Mirrors
     * [membershipOf]: elements come back as either `Entity<MomentSource>` or a raw composite depending
     * on the select path, and a read failure (field absent on an old peer) folds to empty rather than
     * failing the whole projection.
     */
    private fun sourcesOf(e: Entity<out SvcMoment>): List<String> =
        runCatching {
            e.getFieldValue<TList<*>>(SvcMoment.Fields.SOURCES).toList().mapNotNull { s ->
                when (s) {
                    is Entity<*> -> s.getFieldValue<TString>(SvcMomentSource.Fields.SOURCE).get()
                    is SvcMomentSource -> s.source.get()
                    else -> null
                }
            }
        }.getOrDefault(emptyList())

    /**
     * The named stops a **transit** leg ran between (§32.14), or the leg unchanged.
     *
     * ⚠ Transit only, and that is the whole safety argument: a walk that happens to finish near a
     * station must not be renamed after it. Measured — an office sits 252 m from a station, so a
     * radius wide enough for a real platform would rename it, and only the mode gate stops that.
     *
     * ⚠ Cached by `traceId` because a trace's endpoints never move, and the feed re-renders far more
     * often than traces are written. Without it this would repeat two lookups per transit leg on
     * every emission of a shared flow.
     *
     * ⚠⚠ **But "the endpoints never move" is a claim about the coordinates, not about the lookup**
     * (§36.6a). [stopNear] collapses every failure to `""`, so caching unconditionally meant one
     * failed tile read left a leg unnameable for the life of the process — observed on device, where
     * a leg finishing at München Ost showed a bare `Transit` for hours and the identical stored trace
     * named itself after a reinstall. [StopLookup] carries the retry budget and the reasoning; the
     * short version is that neither "cache forever" nor "never cache a blank" is available, because
     * the second re-sends a coordinate to the geocoder on every emission of every nameless leg.
     */
    /** `traceId|consent -> `[StopLookup]. ⚠ Bounded retries, and a retry never takes a name away. */
    private val transitStopCache = mutableMapOf<String, StopLookup>()

    private suspend fun withTransitStops(trace: TraceInput): TraceInput {
        if (trace.mode != MovementModes.TRANSIT) return trace
        // ⚠ Keyed on the opt-in too. With it off the stops are never asked (the egress refuses), so
        // those attempts are spent on the line alone; if they were charged to the same entry, a user
        // who opts in later would find the budget gone and the leg nameless for the whole process.
        val cacheKey = "${trace.id}|${geocodingEgress.isAllowed}"
        val cached = transitStopCache[cacheKey]
        if (!StopLookup.shouldAsk(cached)) {
            return trace.copy(
                originStop = cached!!.originStop,
                destinationStop = cached.destinationStop,
                line = cached.line,
            )
        }
        val resolved = StopLookup(
            originStop = stopNear(trace.startLatE7, trace.startLngE7),
            destinationStop = stopNear(trace.endLatE7, trace.endLngE7),
            // §33 L5. ⚠ Independent of the stops: the overlay is joined by COORDINATE, so a leg whose
            // endpoints the basemap cannot name can still be named by its line, and vice versa (§17.6).
            line = lineOn(trace),
            attempts = (cached?.attempts ?: 0) + 1,
        ).mergedOver(cached)
        transitStopCache[cacheKey] = resolved
        return trace.copy(
            originStop = resolved.originStop,
            destinationStop = resolved.destinationStop,
            line = resolved.line,
        )
    }

    /**
     * The line(s) serving both ends of [trace], already rendered by the settled display rule.
     *
     * ⚠ Uses the **same** radius as the stop lookup: it is the same uncertainty about the same two
     * endpoints, so two different numbers here would be two different claims about one fact.
     *
     * ⚠⚠ Blank on every failure — no overlay, no coverage, a fetch that failed. The row then reads
     * exactly as it did before overlays existed, which is the whole posture of this tier.
     */
    private suspend fun lineOn(trace: TraceInput): String {
        if (trace.startLatE7 == 0L && trace.startLngE7 == 0L) return ""
        if (trace.endLatE7 == 0L && trace.endLngE7 == 0L) return ""
        val refs = runCatchingCancellable {
            client.mapRegionService
                .linesServing(
                    fromLatE7 = trace.startLatE7,
                    fromLngE7 = trace.startLngE7,
                    toLatE7 = trace.endLatE7,
                    toLngE7 = trace.endLngE7,
                    withinM = TRANSIT_STOP_RADIUS_M,
                )
                .fold(onSuccess = { it.get() }, onFailure = { "" })
        }.getOrDefault("")
        return TransitLineIndex.label(refs.split(',').filter { it.isNotBlank() }.toSet()).orEmpty()
    }

    /**
     * ⚠⚠ Through [geocodingEgress], never the op directly: the proxy falls back from its tiles to
     * komoot's public Photon, so this sends the leg's endpoint to a third party. It used to call
     * `nearestTransitStop` itself and skipped the opt-in — every transit leg's endpoints left the
     * device with place-name lookups off.
     */
    private suspend fun stopNear(latE7: Long, lngE7: Long): String =
        geocodingEgress.transitStopNear(latE7, lngE7, TRANSIT_STOP_RADIUS_M).orEmpty()

    private fun traceInput(e: Entity<out SvcTrace>) = TraceInput(
        id = e.getFieldValue<TString>(SvcTrace.Fields.TRACE_ID).get(),
        mode = e.getFieldValue<TString>(SvcTrace.Fields.MODE).get(),
        distanceM = e.getFieldValue<TInteger>(SvcTrace.Fields.DISTANCE_M).get(),
        // §32.12: free — the same row the other three come from, no extra read and no join.
        classifierVersion = e.getFieldValue<TInteger>(SvcTrace.Fields.CLASSIFIER_VERSION).get().toInt(),
        startLatE7 = e.getFieldValue<TInteger>(SvcTrace.Fields.START_LAT_E7).get(),
        startLngE7 = e.getFieldValue<TInteger>(SvcTrace.Fields.START_LNG_E7).get(),
        endLatE7 = e.getFieldValue<TInteger>(SvcTrace.Fields.END_LAT_E7).get(),
        endLngE7 = e.getFieldValue<TInteger>(SvcTrace.Fields.END_LNG_E7).get(),
    )

    private fun tripInput(e: Entity<out SvcTrip>) = TripInput(
        id = e.getFieldValue<TString>(SvcTrip.Fields.TRIP_ID).get(),
        title = e.getFieldValue<TString>(SvcTrip.Fields.TITLE).get(),
        dateRange = e.getFieldValue<TString>(SvcTrip.Fields.DATE_RANGE).get(),
        year = e.getFieldValue<TString>(SvcTrip.Fields.YEAR).get(),
        membership = membershipOf(e),
    )

    /**
     * Reads the trip's membership (moment ids) from the materialised nested-composite list field.
     * Elements come back as either `Entity<TripMember>` or raw `TripMember` composites depending on
     * the select path, so both are handled; a read failure (field absent on an old peer) folds to empty.
     */
    private fun membershipOf(e: Entity<out SvcTrip>): List<String> =
        runCatching {
            e.getFieldValue<TList<*>>(SvcTrip.Fields.MEMBERSHIP).toList().mapNotNull { member ->
                when (member) {
                    is Entity<*> -> member.getFieldValue<TString>(SvcTripMember.Fields.MOMENT_ID).get()
                    is SvcTripMember -> member.momentId.get()
                    else -> null
                }
            }
        }.getOrDefault(emptyList())

    private fun placeInput(e: Entity<out SvcPlace>) = PlaceInput(
        id = e.getFieldValue<TString>(SvcPlace.Fields.PLACE_ID).get(),
        label = e.getFieldValue<TString>(SvcPlace.Fields.LABEL).get(),
        latE7 = e.getFieldValue<TInteger>(SvcPlace.Fields.LAT_E7).get(),
        lngE7 = e.getFieldValue<TInteger>(SvcPlace.Fields.LNG_E7).get(),
        city = e.getFieldValue<TString>(SvcPlace.Fields.CITY).get(),
        countryCode = e.getFieldValue<TString>(SvcPlace.Fields.COUNTRY_CODE).get(),
        // Feeds the "is this place user-meaningful?" veto in the in-ride station-stop collapse.
        // ⚠ Absent from the projection it reads back empty, which degrades to `geocoded` — the
        // *unprotected* reading, so the guard must project it or it silently never fires.
        labelSource = e.getFieldValue<TString>(SvcPlace.Fields.LABEL_SOURCE).get(),
    )

    private fun workoutInput(e: Entity<out SvcWorkout>) = WorkoutInput(
        id = e.getFieldValue<TString>(SvcWorkout.Fields.WORKOUT_ID).get(),
        activityType = e.getFieldValue<TString>(SvcWorkout.Fields.ACTIVITY_TYPE).get(),
    )

    private fun activityCountInput(e: Entity<out SvcWorkoutActivityRollup>) = ActivityCountInput(
        activityType = e.getFieldValue<TString>(SvcWorkoutActivityRollup.Fields.ACTIVITY_TYPE).get(),
        count = e.getFieldValue<TInteger>(SvcWorkoutActivityRollup.Fields.COUNT).get().toInt(),
    )

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        /** How often the current place's live duration re-emits while the user stays (§11.2). */
        const val LIVE_TICK_MS = 30_000L

        /**
         * How long an in-progress ride may go without a fix before the feed stops calling it
         * current. Deliberately *derived* from the segmenter's own continuity window rather than
         * restated, so the two can never drift: past this point the segmenter would flush the
         * journalled path as a finished leg instead of resuming it (`MovementSegmenter`), and a
         * ride we would no longer resume must not still be ticking on screen.
         */
        val ONGOING_MOVE_STALE_MS = MovementDetectionConfig().maxResumeGapMs

        /**
         * The window floor, single-sourced from [PhotoSelection] since T-P3 — the projection
         * applies the same rule when it assigns photos to rows, and two copies of it would be two
         * answers to "does this photo belong to this moment".
         */
        const val MIN_PHOTO_WINDOW_MS = PhotoSelection.MIN_WINDOW_MS

        /**
         * The most photos one projection reads. A feed spans as much history as the store holds,
         * and the op ranks before it truncates, so this bounds the read without deciding which
         * moment loses: the oldest and least-provenanced go first.
         */
        const val PHOTO_SCAN_CAP = 500

        /**
         * How many recent workouts the feed joins against. Mirrors Fit's `WORKOUT_WINDOW`
         * (`fit-read-scaling.md` Phase 1) — Timeline needs strictly less, since it only
         * resolves subtitles for workouts a moment already points at.
         */
        const val WORKOUT_WINDOW = 300

        /** Keeps a shared read alive briefly with no collectors, so navigation does not re-read. */
        const val SHARE_STOP_TIMEOUT_MS = 5_000L
        // Placeholder cover tint for a photo-less suggestion (the Interlude card renders a color when
        // a Photo has no uri); captured-photo covers are a later slice.
        const val SUGGESTION_COVER_COLOR = "#6C7BdC"

        val MOMENT_FIELDS = arrayOf(
            SvcMoment.Fields.MOMENT_ID, SvcMoment.Fields.TIMESTAMP, SvcMoment.Fields.KIND,
            SvcMoment.Fields.TITLE, SvcMoment.Fields.SUBJECT, SvcMoment.Fields.DURATION_SEC,
            SvcMoment.Fields.SUPERSEDED_BY,
            // ⚠ A field absent from the projection reads back empty — populating Moment.sources
            // without projecting it here would have left the reconciler exactly as blind as before.
            SvcMoment.Fields.SOURCES,
        )
        val TRIP_FIELDS = arrayOf(
            SvcTrip.Fields.TRIP_ID, SvcTrip.Fields.TITLE, SvcTrip.Fields.DATE_RANGE, SvcTrip.Fields.YEAR,
            SvcTrip.Fields.MEMBERSHIP,
        )
        val PLACE_MERGE_FIELDS = arrayOf(
            SvcPlaceMerge.Fields.PLACE_ID, SvcPlaceMerge.Fields.MERGED_INTO,
        )
        // ⚠ `decidedAt` is deliberately absent — the UI never shows when a role was decided, and a
        // field absent from the projection reads back empty rather than failing.
        val PLACE_ROLE_FIELDS = arrayOf(
            SvcPlaceRole.Fields.PLACE_ID, SvcPlaceRole.Fields.ROLE, SvcPlaceRole.Fields.ROLE_SOURCE,
        )
        val PLACE_FIELDS = arrayOf(
            SvcPlace.Fields.PLACE_ID, SvcPlace.Fields.LAT_E7, SvcPlace.Fields.LNG_E7,
            SvcPlace.Fields.CAPTURED_AT, SvcPlace.Fields.LABEL, SvcPlace.Fields.CITY,
            SvcPlace.Fields.COUNTRY_CODE, SvcPlace.Fields.LABEL_SOURCE,
        )
        // Only what the card reads. ⚠ A field absent here reads back empty — and the polyline is
        // deliberately absent: decoding a path per feed row is exactly what §2.1's split avoids.
        /**
         * How far a **transit** leg's endpoint may sit from a named stop and still be named by it
         * (§32.14).
         *
         * **Measured, not guessed.** Against the production tiles at the reported journey's own
         * endpoints: one station answers at **79 m** and a metro station at **210 m** — the latter because
         * the rider surfaces some way from the platform, which is the normal shape of a metro exit.
         * 300 m covers both. ⚠ It is also where the first false positive appears (an office sits 252 m
         * from a metro station), which is survivable *only* because this is asked on transit legs alone —
         * a walk that finishes near a station is never offered to it.
         *
         * ⚠ `StopTileReader` ranks **tier before distance**, so a `station` still beats a `tram_stop`
         * inside this radius: at the U5 exit that is Odeonsplatz (210 m) over a nearer
         * stop named after a street (165 m). Widening the radius does not mean taking the nearest thing.
         */
        const val TRANSIT_STOP_RADIUS_M = 300L

        val TRACE_FIELDS = arrayOf(
            SvcTrace.Fields.TRACE_ID, SvcTrace.Fields.MODE, SvcTrace.Fields.DISTANCE_M,
            // §32.14 — the leg's own endpoints, so a transit row can be named by the stops it ran
            // between rather than by whatever dwells happen to bracket it.
            SvcTrace.Fields.START_LAT_E7, SvcTrace.Fields.START_LNG_E7,
            SvcTrace.Fields.END_LAT_E7, SvcTrace.Fields.END_LNG_E7,
            // §32.12 — an int on a row already being read. ⚠ Absent here it reads back 0, which the
            // collapse would take as "kind unknown" and silently fold every transfer again.
            SvcTrace.Fields.CLASSIFIER_VERSION,
        )
        val ACTIVITY_ROLLUP_FIELDS = arrayOf(
            SvcWorkoutActivityRollup.Fields.ACTIVITY_TYPE,
            SvcWorkoutActivityRollup.Fields.COUNT,
        )

        val WORKOUT_FIELDS = arrayOf(
            SvcWorkout.Fields.WORKOUT_ID, SvcWorkout.Fields.ACTIVITY_TYPE, SvcWorkout.Fields.SOURCE,
            SvcWorkout.Fields.START, SvcWorkout.Fields.END, SvcWorkout.Fields.DURATION_SEC,
        )
    }
}

/**
 * Narrows a root file listing to the folder at [parentPath], so a single-file lookup searches one
 * folder's children instead of the whole tree.
 *
 * Mirrors the private `navigateTo` in Drive's `TenshuFilesRepository` (and its `navigateToFolder`
 * twin in the JS SDK) — the third copy of six lines, kept private rather than promoted because
 * publishing it would make `:file-service-client` owe an API it has never been asked for. Fold all
 * three into one when a fourth appears.
 */
private fun TList<File>.navigateTo(parentPath: String): TList<File> {
    val parent = parentPath.ifEmpty { "/" }
    if (parent == "/") return this
    var list = this
    var prefix = ""
    for (segment in parent.trimStart('/').split('/')) {
        prefix = "$prefix/$segment"
        val level = prefix
        list = list.search<Folder> { path equalTo level }.ifEmptyError().files
    }
    return list
}
