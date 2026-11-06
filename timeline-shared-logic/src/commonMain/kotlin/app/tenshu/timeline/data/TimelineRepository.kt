package app.tenshu.timeline.data

import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripSuggestion
import kotlinx.coroutines.flow.Flow

/**
 * The Timeline data seam (Ph2, docs/proposals/timeline-app.md §2). View models depend on
 * this interface only; v1 binds [MockTimelineRepository], and the real on-device Timeline
 * store swaps in behind the same interface via one Koin binding — no VM/UI change.
 *
 * Observable collections are `Flow` (the mock backs them with `StateFlow`); single-entity
 * lookups are `suspend` getters returning null when absent.
 */
interface TimelineRepository {

    /**
     * Whether the last IPC read actually reached Control — `null` until a first outcome. The
     * real repository derives it from read outcomes plus a slow probe (`:app-reachability`,
     * fit-app.md §9.14); the app shell renders the "Can't reach Tenshu Control" banner off
     * `false` — without this, an unreachable Control folds every read to an empty list and the
     * app is silently blank. Defaults to always-reachable for
     * implementations that are not IPC-backed (the mock, previews).
     */
    fun controlReachable(): kotlinx.coroutines.flow.StateFlow<Boolean?> =
        kotlinx.coroutines.flow.MutableStateFlow(true)

    /**
     * *Why* Control is unreachable (`core-schema-skew-surface.md` SK-1) — `null` while it is fine
     * or not yet known. [controlReachable] decides **whether** the banner shows; this decides what
     * it says, because a proxy that is answering and refusing on a schema version needs the user
     * to update something, and a dead channel does not.
     *
     * Defaults to a permanent `null`, which renders the pre-SK-1 words.
     */
    fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<app.tenshu.reachability.UnreachableReason?> =
        app.tenshu.reachability.NO_UNREACHABLE_REASON

    /**
     * The user asked to check again ("Try again" on the checking gate's retry wall): re-probe Control
     * and re-read the realm now rather than at the next poll. A no-op without an engine.
     */
    fun recheckControl() = Unit

    /**
     * The write gate for Timeline's unattended writers — the capture outbox and its app-start drain
     * (`ADR 0025` §Decision 8.6): while Control's device is known to have no realm, captured visits
     * and moves stay in the durable outbox instead of landing in a store that belongs to no realm.
     * `null` (the default, and the mock) means no gate: writes behave exactly as before.
     */
    val realmWriteGate: app.tenshu.reachability.ControlReachability? get() = null

    /**
     * The realm-wide metric/imperial answer (`OwnerPreferences.Units`, `fit-app.md` §18.2) — the
     * app root provides it to `LocalMeasurementSystem` so every distance formats in it.
     *
     * ⚠ Not Timeline's setting: the user gives one answer, in Fit's Profile today, and every app
     * reads it. Defaults to metric for implementations with no proxy behind them (the mock, the
     * not-connected surface, previews) — the same posture as [controlReachable].
     */
    fun units(): kotlinx.coroutines.flow.Flow<org.tenshu.preference.MeasurementSystem> =
        kotlinx.coroutines.flow.flowOf(org.tenshu.preference.MeasurementSystem.METRIC)

    /** The Home feed — day chapters, newest first. */
    fun homeDays(): Flow<List<Day>>

    /** The auto-detected trip suggestion driving the Interlude card, or null once resolved. */
    fun tripSuggestion(): Flow<TripSuggestion?>

    /** Bookmarked past trips rendered as bound-chapter cover cards on Home. */
    fun boundTrips(): Flow<List<Trip>>

    /** All places (Places tab). */
    fun places(): Flow<List<Place>>

    /** Activity roll-ups (Activities tab). */
    fun activities(): Flow<List<ActivityRollup>>

    /** A trip by id — recursive; nested trips resolve through the same call. */
    suspend fun trip(id: String): Trip?

    /** A moment by id, wherever it lives (a home day, or any (nested) trip). */
    /**
     * One moment, by id.
     *
     * ⚠ [fresh] must be `true` after a local write. The implementation may answer from the feed's
     * last projection, which by definition predates the call — so a mutate-then-reload would
     * otherwise re-read the pre-write snapshot and show the write as having silently failed.
     */
    suspend fun moment(id: String, fresh: Boolean = false): Moment?

    /** A place by id, with its visit history. */
    suspend fun place(id: String): Place?

    /**
     * The decoded path of a movement trace (M5, `timeline-movement-capture.md` §7) — the ordered
     * points for the movement detail's route-over-basemap hero. This is the **only** read that
     * touches the trace's polyline blob; the feed projection stays deliberately blob-free (§2.1).
     * Empty when the trace is unknown or its polyline was dropped. Default empty so read-only
     * implementations (the mock, the iOS EPC client) stay source-compatible.
     */
    suspend fun movementRoute(traceId: String): List<app.tenshu.timeline.model.GeoPoint> = emptyList()

    /**
     * The decoded path of a whole journey — [traceIds]' routes concatenated **in the given order**,
     * which the projector supplies in time order. A ride whose in-ride station stops were collapsed
     * has several traces behind one row, and drawing only the first would contradict the card.
     *
     * Defaults to the one-trace read per id so existing implementations (the mock, the iOS EPC
     * client, test fakes) stay source-compatible; the real repository overrides it with a single pass.
     *
     * ⚠ **This is the overload callers use.** A fake built as `object : TimelineRepository by other`
     * gets a *generated* forwarding override here, which does NOT route through a hand-written
     * [movementRoute] `(String)` override on the same object — so override this one, not its sibling.
     */
    suspend fun movementRoute(traceIds: List<String>): List<app.tenshu.timeline.model.GeoPoint> =
        traceIds.flatMap { movementRoute(it) }

    /**
     * Rename a place (place-geocoding.md G4) — the user-authored label; the enricher's
     * blank-only rule then never overwrites it. Default no-op so read-only implementations
     * (the iOS EPC client until G5) stay source-compatible; blank input is ignored.
     */
    suspend fun renamePlace(id: String, label: String) {}

    /**
     * Answer the "is this your Home?" prompt for a place (`place-roles.md` PR-3) — or dismiss it,
     * which is the same call with [app.tenshu.timeline.model.PlaceRoleNames.NONE].
     *
     * ⚠ Whatever the user says here is written as **user-authored**, which the inference pass then
     * never overwrites and `PlaceRoleMerger` protects across devices. There is deliberately no way
     * to write an *inferred* role from the client: that is the proxy's job, and a client that could
     * mint one would be able to impersonate a guess as an answer.
     *
     * Default no-op so read-only implementations (the mock, the iOS EPC client) stay
     * source-compatible — the same convention as [renamePlace].
     */
    suspend fun setPlaceRole(placeId: String, role: String) {}

    /**
     * Fold two places into one (`timeline-recognition-and-place-naming.md` §9.4) and return the id
     * that survives — which the caller should navigate to, since the other row disappears from every
     * surface.
     *
     * ⚠ The survivor is **not** the caller's choice: it is the deterministic root (the smaller id),
     * the same rule the proxy's automatic pass uses, so a manual merge and an automatic one can never
     * disagree. The user's *name* is not at the mercy of that — it travels to the survivor. Default
     * no-op returns [a] so read-only/mock implementations stay source-compatible.
     */
    suspend fun mergePlaces(a: String, b: String): String = a

    /**
     * Undo a merge — [placeId] becomes its own place again (§9.4). The rows were never rewritten, so
     * this restores exactly what was there; nothing is recovered because nothing was lost.
     */
    suspend fun unmergePlace(placeId: String) {}

    /**
     * How many places have been folded into [placeId] (§9.4). Drives the *Unmerge* affordance: the
     * folded rows are hidden from every read, so "am I a merge result" cannot be answered by looking
     * at this place alone.
     */
    suspend fun mergedPlaceCount(placeId: String): Int = 0

    /**
     * Nearby place-name suggestions for a coordinate (E7), best first — for the "choose the right
     * place" picker (`timeline-recognition-and-place-naming.md` §4). Proxy-mediated Photon egress,
     * behind the same consent as geocoding. Default empty so read-only/mock implementations stay
     * source-compatible.
     */
    suspend fun placeSuggestions(latE7: Long, lngE7: Long): List<String> = emptyList()

    /**
     * Preview bytes for the photo at [uri], at roughly [width]x[height], or `null` when this device
     * can answer with nothing.
     *
     * @param heroFidelity pull the **`XLARGE` rung** (~1024 px, ~139 KB) for this photo as well as
     * the two cheap ones. Only a hero may pass it: one photo, on a screen the user deliberately
     * opened.
     *
     * ⚠⚠ **A flag and not a size threshold.** `previewRequestPx` buckets a frame to 256/512/768/…,
     * so a feed row on a desktop asks for 512 — above the `LARGE` rung. Deriving "is this a hero"
     * from the requested size would pull ~139 KB per row a scroll passes.
     *
     * ⚠ **One method with a flag, deliberately — not a second `photoBytesAtHeroFidelity`.**
     * Almost every fake here is built as `object : TimelineRepository by mock`, and Kotlin's
     * interface delegation does **not** dispatch back into the overriding class: a second method
     * with a `= photoBytes(...)` default would resolve against the *delegate*, so a fake overriding
     * only `photoBytes` would silently never see the hero's read. That is invisible — the test
     * still gets bytes, just the wrong object's. One method cannot be half-overridden.
     */
    suspend fun photoBytes(
        uri: String,
        width: Int,
        height: Int,
        heroFidelity: Boolean = false,
    ): ByteArray? = null

    /** Dismiss the Interlude ("Not a trip") — clears the suggestion. */
    suspend fun dismissTripSuggestion()

    /** Accept the Interlude ("Read as a trip") — clears the suggestion (v1 mock: no-op beyond that). */
    suspend fun acceptTripSuggestion()
}
