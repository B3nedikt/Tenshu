package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.CategorySummary
import app.tenshu.liststate.ListLoadState
import app.tenshu.fit.model.CycleEntry
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.DetectedMovement
import app.tenshu.fit.model.FitProfile
import app.tenshu.fit.model.Goals
import org.tenshu.preference.BiologicalSex
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.ImportCategory
import app.tenshu.fit.model.ImportProgress
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.BatchImportSummary
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.DataSourceRow
import app.tenshu.fit.model.ImportRunEntry
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.SharingReadiness
import app.tenshu.fit.model.MedicalRecord
import app.tenshu.fit.model.Metric
import app.tenshu.fit.model.NutritionDay
import app.tenshu.fit.model.RecordedTrack
import app.tenshu.fit.model.SampleLatest
import app.tenshu.fit.model.SleepSession
import app.tenshu.fit.model.Units
import app.tenshu.fit.model.Workout
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** What actually happened to a recorder save — drives the finish navigation vs. the notice. */
enum class WorkoutSaveOutcome {
    /** The workout landed in the (Control-backed) store; its detail is readable now. */
    Synced,

    /**
     * The proxy was unreachable; the workout is journalled durably on this device and will be
     * written by the next [FitRepository.drainPendingWorkouts] (next launch / next save).
     */
    SavedLocally,
}

/**
 * The outcome of the one-shot workout detail read ([FitRepository.workout]).
 *
 * ⚠⚠ **A failure is not an absence.** With a bare `Workout?`, a parse throw, a dead IPC hop and
 * a genuinely deleted row all render the same "Workout not found" — a decode throw on the
 * `Workout.Streams` carrier presents as *every workout is missing*.
 *
 * Nothing here says *why* it failed — the reason belongs in the log, not on the screen, and the
 * screen's job is to stop claiming the row is gone.
 */
sealed interface WorkoutRead {

    /** The row was read. */
    data class Found(val workout: Workout) : WorkoutRead

    /** The store answered, and has no such row — the only case that may say "not found". */
    data object NotFound : WorkoutRead

    /**
     * The read did not complete: a throw crossing IPC, a decode failure, or no proxy at all.
     * ⚠ Says nothing about whether the row exists, which is the whole point.
     */
    data object Failed : WorkoutRead
}

/**
 * The Fit data seam (docs/proposals/fit-app.md §6). View models depend on this
 * interface only; [MockFitRepository] and the real `:health-service`-backed projector sit
 * behind it, chosen by one Koin binding — no VM/UI change.
 *
 * Observable collections are `Flow` (the mock backs them with `StateFlow`); single-entity
 * lookups are `suspend` getters returning null when absent.
 */
interface FitRepository {

    /**
     * "Today" as the dataset knows it. UI headers and today-math key off this — never off the
     * system clock directly — so the mock week renders coherently against a fixed date.
     *
     * ⚠⚠ **Read it, don't capture it.** For a fixture implementation this is a constant, but the
     * real one ([app.tenshu.fit.data.FitProjectionRepository]) answers from the live clock, and
     * both it and every view model outlive midnight: a repository is a process-scoped singleton
     * and a `ViewModel` survives every recomposition. A `val today = repository.today` taken at
     * construction puts a `Today` header over yesterday's workouts. Read it inside the collect /
     * inside the composition instead.
     */
    val today: LocalDate

    /**
     * Daily aggregates, oldest first, ending at [today].
     *
     * ⚠ Carries [ListLoadState] rather than a bare list, and the two other reads Home and Browse
     * stand on ([categories], [goals]) are reached through it. Folding a failed read to
     * `emptyList()` here would let Home render "0 steps, 0 minutes" and Browse render seven
     * "Not set up" cards plus a *connect a data source* prompt — telling the user their store is
     * empty at the one moment the app could not know. See `ListLoadState`.
     */
    fun aggregates(): Flow<ListLoadState<DailyAggregate>>

    /**
     * Daily nutrition aggregates (energy + hydration), oldest first (§12 B3a) — the Nutrition
     * category teaser + detail. Defaults to empty for implementations without a nutrition store.
     */
    fun nutrition(): Flow<List<NutritionDay>> = flowOf(emptyList())

    /**
     * Logged menstruation days, oldest first (§12 B3b) — the Cycle category. The Cycle card is
     * **data-driven** (shown only when this is non-empty), never gated on a profile sex field.
     * Defaults to empty for implementations without a cycle store.
     */
    fun cycle(): Flow<List<CycleEntry>> = flowOf(emptyList())

    /**
     * The most recent workouts, newest first — a **bounded** read (the real projector windows it),
     * so this is the right source for anything that needs the rows *together*: Home's latest card,
     * Profile's connected-sources list, History's week roll-up.
     *
     * ⚠ Not the History feed — that is [workoutsPaged]. A feed that has to reach past the
     * window cannot ride a list read, and summing a `PagingData` is not possible, so the two
     * genuinely want different shapes.
     */
    fun workouts(): Flow<List<Workout>>

    /**
     * The History feed: every workout, newest first, delivered a page at a time.
     *
     * ⚠⚠ **This does not replace [workouts] and does not save a read.** Home is the default tab and
     * reads `aggregates()`, whose own workout read happens at launch whatever History does — so
     * paging History *adds* a read. Its case is uniformity with Drive's listings, no magic constant
     * on the feed, and a feed that actually reaches past the [workouts] window
     * (`fit-history-paging-handover.md` §1).
     *
     * The default is the honest unpaged answer for every implementation without a store behind it
     * — the mock, previews, the not-connected iOS surface — rather than an empty feed: a static
     * `PagingData` over whatever [workouts] returns renders identically, it simply never appends.
     * `LoadStates` are stated explicitly because the deprecated single-argument
     * `PagingData.from` leaves the indicators pinned in `Loading`, which reads as a feed that is
     * forever about to load more.
     */
    fun workoutsPaged(): Flow<PagingData<Workout>> =
        workouts().map { rows ->
            PagingData.from(
                rows,
                LoadStates(
                    refresh = LoadState.NotLoading(endOfPaginationReached = true),
                    prepend = LoadState.NotLoading(endOfPaginationReached = true),
                    append = LoadState.NotLoading(endOfPaginationReached = true),
                ),
            )
        }

    /**
     * The seven Browse categories with teasers + last-updated stamps.
     *
     * ⚠ [ListLoadState], for the reason on [aggregates]: a category is "Not set up" only when the
     * store really is empty, never because a read failed.
     */
    fun categories(): Flow<ListLoadState<CategorySummary>>

    /** Latest reading per vital/body metric — the Browse teaser + detail current-value surface. */
    fun samplesLatest(): Flow<List<SampleLatest>>

    /**
     * The full time-series of readings for one [metric], newest first (§12 B2) — the Body/Vitals
     * category detail's history. Defaults to empty for implementations without a time-series store
     * (the not-connected iOS surface); the mock and the real projector override it.
     */
    fun sampleHistory(metric: Metric): Flow<List<SampleLatest>> = flowOf(emptyList())

    fun sleepSessions(): Flow<List<SleepSession>>

    /** Import-only, read-only (fit-agent.md §0.7). */
    fun medicalRecords(): Flow<List<MedicalRecord>>

    fun goals(): Flow<Goals>

    fun profile(): Flow<FitProfile>

    /** Gate for the onboarding flow (D5). The mock starts false and resets per launch. */
    fun onboarded(): Flow<Boolean>

    /**
     * Whether the last IPC read/write actually reached Control — `null` until a first outcome
     * arrives. The real repository derives it from read/write results plus a slow probe while
     * unreachable (fit-app.md §9.14); the app shell renders the "Can't reach Tenshu Control"
     * banner off `false` — without this, an unreachable Control folds every read to an empty
     * list and the app is silently blank. Defaults to always-reachable for
     * implementations that are not IPC-backed (the mock, previews, fixed iOS surfaces).
     */
    fun controlReachable(): StateFlow<Boolean?> = MutableStateFlow(true)

    /**
     * *Why* Control is unreachable (`core-schema-skew-surface.md`) — `null` while it is fine
     * or not yet known. [controlReachable] decides **whether** the banner shows; this decides what
     * it says, because a proxy that is answering and refusing on a schema version needs the user
     * to update something, and a dead channel does not.
     *
     * Defaults to a permanent `null`, which renders the generic unreachable copy.
     */
    fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<UnreachableReason?> = app.tenshu.reachability.NO_UNREACHABLE_REASON

    /**
     * The user asked to check again ("Try again" on the checking gate's retry wall): re-probe Control
     * and re-read the realm now rather than at the next poll. A no-op without an engine.
     */
    fun recheckControl() = Unit

    /**
     * Movements Timeline captured on its own (`fit-automatic-activity-detection.md`) — the
     * raw material for auto-detected workouts, newest first.
     *
     * Defaults to empty, and that default is the honest answer in three real situations, not a
     * stub: Timeline is not installed, this repository has no proxy behind it, or nothing has
     * been captured. ⚠ Fit never *senses* for these — Timeline does, and Fit reads back
     * retrospectively (D5); that is what keeps a second detector, and a second location
     * subscription, from existing.
     */
    fun detectedMovements(): Flow<List<DetectedMovement>> = flowOf(emptyList())

    /**
     * Walks and runs found in **step** data rather than a GPS trace (Tier 1, §9.2) — the source
     * that works on a phone whose health store has steps but no exercise sessions.
     *
     * ⚠ One-shot, not a flow: the underlying platform read is expensive (10 000 records on a real
     * device) and belongs to the once-per-launch promotion pass.
     */
    suspend fun detectedStepBouts(): List<DetectedMovement> = emptyList()

    /**
     * The workout detail read.
     *
     * ⚠ Returns [WorkoutRead], not `Workout?`: an implementation that cannot complete the read
     * must answer [WorkoutRead.Failed] rather than a null the caller would render as "not found".
     */
    suspend fun workout(id: String): WorkoutRead

    /**
     * Appends a finished recording ([RecordingEngine]) so it shows in Home/History. [track] is the
     * dense captured route + HR (real GPS from the first-party recorder); when present the real
     * store persists it to the workout's stream blob, so its route + HR curve read back on the
     * detail screen. `null` for a summary-only add.
     *
     * With a durable outbox bound (Android/desktop), the workout is journalled locally *before*
     * the store write and this returns [WorkoutSaveOutcome.SavedLocally] instead of throwing when
     * the proxy is unreachable — the journal replays via [drainPendingWorkouts]. Without one, a
     * failed write throws (the recorder engine then keeps the session for a manual retry).
     */
    suspend fun addWorkout(workout: Workout, track: RecordedTrack? = null): WorkoutSaveOutcome

    /**
     * Deletes one workout by [Workout.workoutId] — the row, its stream blob, and a tombstone so
     * the deletion converges on every paired device.
     *
     * Returns `false` when the delete could not be performed (no proxy behind this repository,
     * or the write failed). ⚠ The caller must not treat that as success: unlike the other
     * defaulted no-op mutations here, a delete that silently does nothing is a UI affordance
     * that lies — which is why this reports rather than swallowing. Gate the affordance on the
     * result, not on the call returning.
     */
    suspend fun deleteWorkout(id: String): Boolean = false

    /**
     * Replays workouts journalled by [addWorkout] while the proxy was unreachable — oldest first,
     * removing each on confirmed write; stops at the first failure; never throws. Run at app
     * start ([HealthImportBootstrap]) and ahead of every new save. No-op for implementations
     * without a durable journal (the mock).
     */
    suspend fun drainPendingWorkouts() = Unit

    /**
     * Suspends until unattended writes may run — `ControlReachability.awaitRealmWritable`
     * (`ADR 0025` §Decision 8.6). [HealthImportBootstrap] waits on it before each pass, so a device
     * whose Control has no realm imports and drains nothing into an ownerless store, and the held
     * pass runs when a realm appears. Returns at once by default (the mock, and every
     * implementation with no realm probe wired).
     */
    suspend fun awaitRealmWritable() = Unit

    /**
     * The realm write gate as a refusal (`ADR 0025` §8.6): throws `RealmWritesHeldException` while
     * unattended writes must wait. Handed to every write boundary a Fit pipeline outside this
     * repository drives (`AutoDetectBootstrap`'s supersession). ⚠⚠ Deliberately **no default**: an
     * implementation that forgot it would make every such boundary silently inert — so each one says
     * what it does (the mock and the iOS not-connected repository have no realm to guard).
     */
    fun refuseIfRealmWritesHeld()

    /**
     * `ControlReachability.realmReleaseMarker` — take it **before** any initial work and hand it to
     * [onEachRealmRelease] (`ADR 0025` §8.6). `0` by default.
     */
    fun realmReleaseMarker(): Long = 0L

    /**
     * Runs [action] once per held → writable release since [since] (ABSENT → PRESENT and PENDING →
     * PRESENT/UNKNOWN alike) — how [HealthImportBootstrap] re-runs a pass the gate held. By default
     * there is nothing to subscribe to (no realm probe wired), so it returns at once.
     */
    suspend fun onEachRealmRelease(since: Long, action: suspend () -> Unit) = Unit

    /**
     * How many automatic import passes the realm gate has **held** — rejected at entry or stopped
     * mid-run, while the verdict held writes (`ADR 0025` §8.6). [HealthImportBootstrap] re-runs a
     * pass on the next release only if this moved since its last pass began: a pass that never
     * started counts exactly like one stopped half-way, and only a pass that finished (or never
     * met the gate) leaves it unchanged. `0` by default.
     *
     * ⚠ Imports only. The outbox drain has its own resumption — the repository re-drains on every
     * release unconditionally — so counting its stops here would only buy a redundant import.
     */
    fun realmHeldStops(): Long = 0L

    /**
     * Runs [action] once per change of [realmHeldStops] since [since]. ⚠⚠ The stop half of the
     * re-run: a release can land BETWEEN a refusal and the stop being
     * counted, and then the release's handler sees no new stop and the held pass is never re-run.
     * [HealthImportBootstrap] therefore also requests a re-run on every stop; that request waits for
     * the gate, so whichever of the two arrives second triggers it. By default there is nothing to
     * subscribe to, so it returns at once.
     */
    suspend fun onEachRealmHeldStop(since: Long, action: suspend () -> Unit) = Unit

    // ── Onboarding (D5: detection is data, not expect/actual) ──────────────────────

    /**
     * Import sources available on this device, in display order. Health Connect appears
     * only when actually present; file import and start-fresh are always offered —
     * de-Googled is a first-class path (fit-agent.md §0.3).
     */
    fun availableSources(): List<ImportSource>

    /** Per-category consent rows with estimated record counts (empty categories dim). */
    fun importCategories(): List<ImportCategory>

    /** Live first-import progress; null until [startImport] is called. */
    fun importProgress(): Flow<List<ImportProgress>?>

    /**
     * What the last import actually *did* — the signal Home and Profile render so that "no data",
     * "no permission", "no Health Connect", "sample fixture" and "it failed" stop looking alike.
     *
     * Defaulted to [ImportStatus.Idle] because most implementations genuinely never import: the
     * mock, the previews, and the iOS surface serve fixed data. Only the real projector overrides.
     *
     * A `StateFlow` (not a bare `Flow`) so the import-liveness foreground service can observe it —
     * [app.tenshu.liveness.NotificationLiveness] drives off a always-has-a-current-value stream.
     */
    fun importStatus(): StateFlow<ImportStatus> = MutableStateFlow(ImportStatus.Idle)

    /**
     * Kicks off the import of the selected categories, reading back as far as [window] asks.
     *
     * [ImportWindow.FullHistory] is only *meaningfully* different where the platform has a depth
     * gate the user has opened — see [historyAccess]. Asking for it without that grant is harmless
     * and reads the ordinary window, so a caller never has to branch.
     *
     * ⚠⚠ **Pass `automatic = true` from every unattended caller.** It is what the user's
     * per-source *auto-import* switch gates (`fit-data-sources-restructure.md` D4) — a manual
     * "Import now" must still work on a source whose automatic passes are switched off, so the
     * gate cannot live in the switch alone. There are three unattended callers and they are easy
     * to miss because two of them do not go through `HealthImportBootstrap` at all:
     * `HealthImportBootstrap.runPass`, and iOS's `FitLocalRuntime` + `EpcFitClient`, which each
     * re-implement the foreground refresh.
     */
    suspend fun startImport(
        selection: Set<CategoryId>,
        window: ImportWindow = ImportWindow.Recent,
        automatic: Boolean = false,
    )

    /**
     * What the sharing wizard needs to know about this device right now (`fit-app.md` §15.6) —
     * store present, may we read, how deep, and whether Google Fit is allowed to feed it.
     *
     * A flow rather than a one-shot so the wizard re-renders as the user fixes things; the values
     * only change when [refreshSharingReadiness] re-probes, because none of this can be *observed*
     * — the platform offers no callback when a permission is granted inside another app's settings.
     */
    fun sharingReadiness(): StateFlow<SharingReadiness> = MutableStateFlow(SharingReadiness())

    /**
     * The *Data sources* section (`fit-data-sources-restructure.md`), already projected —
     * one flow, not a stream per input.
     *
     * ⚠ Deliberately returns finished [DataSourceRow]s rather than raw registry rows. The
     * "which state wins" rules need this device's id and its live [importStatus] as well as the
     * stored rows, and folding all three here keeps the decision in one desktop-tested place
     * (D10) — and keeps `ProfileViewModel`'s already-five-arity `combine` from growing a sixth
     * source.
     *
     * Defaulted empty so the mock, previews and the iOS not-connected surface need no override.
     * ⚠⚠ Empty means *"we have nothing to show"*, never *"you have no sources"* — a surface that
     * cannot read the registry must not render an empty state that claims the user's realm is
     * bare.
     */
    fun dataSources(): Flow<List<DataSourceRow>> = flowOf(emptyList())

    /**
     * The *Batch import* row, or null where this shell can pick no file — the section is then
     * absent rather than empty (D11: Takeout is an act, not a standing connection, so it has no
     * source row and derives entirely from its runs).
     */
    fun batchImport(): Flow<BatchImportSummary?> = flowOf(null)

    /**
     * One source's import history, newest first — the detail screens' body. [kind] null selects
     * the batch-import history.
     *
     * Spans **every device**, which is the point: it is what makes an iPhone's Apple Health
     * imports readable on an Android phone.
     */
    fun importHistory(kind: DataSourceKind?): Flow<List<ImportRunEntry>> = flowOf(emptyList())

    /**
     * Flips one source's automatic-import switch (D4).
     *
     * ⚠ Works for a source on **another** device — the flag is ours and it syncs, and the owning
     * device honours it on its next foreground. What stays device-local is *triggering* an import
     * and *fixing* permissions, which is why the detail screen disables "Import now" for a
     * non-local source rather than offering a button that silently does nothing.
     */
    suspend fun setSourceAutoImport(
        kind: DataSourceKind,
        deviceId: String,
        enabled: Boolean,
    ) = Unit

    /**
     * Re-probes [sharingReadiness]. The wizard calls this on **every resume**: the user leaves for
     * Health Connect, does something we cannot watch, and comes back — re-probing is the only way
     * to learn what happened, and it is precisely what lets the wizard verify rather than instruct.
     */
    suspend fun refreshSharingReadiness() = Unit

    /**
     * Whether this device can import health data from before the platform's default window.
     *
     * A `StateFlow` so a screen can offer the action, then watch it flip to
     * [HistoryAccess.Granted] once the user answers the system sheet — the grant lands
     * asynchronously and out-of-process, so there is nothing to await. Refreshed by
     * [refreshHistoryAccess]; defaults to [HistoryAccess.Unsupported], which is the honest answer
     * for every surface without a live platform importer (mock, previews, desktop).
     */
    fun historyAccess(): StateFlow<HistoryAccess> = MutableStateFlow(HistoryAccess.Unsupported)

    /**
     * Re-reads [historyAccess] from the platform. Called on the screens that offer the action and
     * again when the app comes back from the consent sheet. No-op by default.
     */
    suspend fun refreshHistoryAccess() = Unit

    /**
     * Imports user-picked files — a Google Takeout archive, or a bare GPX/TCX/FIT activity.
     *
     * A separate entry point from [startImport] because it is a different *kind* of act: the user
     * hands us bytes once, rather than us polling a source. It shares [importStatus] all the same, so
     * a file import reports itself exactly like a Health Connect one.
     *
     * This is the only route by which real Google Fit *history* can reach the app: Google Fit does
     * not feed Health Connect, so the years of it live solely in a Takeout export.
     *
     * Takes an [ActivityFileSource], not a `List`, so a huge archive is consumed one file at a time
     * rather than held in memory whole — see that type. Defaults to a no-op; the mock and preview
     * surfaces have nothing to import into.
     */
    suspend fun importFiles(source: ActivityFileSource) = Unit

    /** Marks onboarding done — the import may still be running ("you can start now"). */
    suspend fun completeOnboarding()

    // ── Profile mutations ───────────────────────────────────────────────────────────

    suspend fun updateGoals(goals: Goals)

    suspend fun updateUnits(units: Units)

    /**
     * The owner's declared person (`fit-app.md` §18.2). Blank/`null` clears back to "not set" —
     * a real answer, not a failure, since nothing but the user can supply these on Android.
     *
     * Defaulted to a no-op like [importFiles]: a repository with no proxy behind it (the
     * not-connected iOS surface, previews, fakes) has nowhere to write, and a silent no-op is the
     * honest behaviour there rather than a throw.
     */
    suspend fun updateOwnerName(name: String) = Unit

    suspend fun updateBirthDate(date: LocalDate?) = Unit

    suspend fun updateSex(sex: BiologicalSex?) = Unit

    /**
     * A user-entered body measurement, recorded as a `HealthSample` rather than a setting — the
     * same row Health Connect writes, so Profile and Browse → Body can never disagree (§18.2).
     * [Source.Manual] provenance distinguishes it from an imported reading.
     */
    suspend fun recordHeight(centimetres: Int) = Unit

    suspend fun recordWeight(kilograms: Double) = Unit
}
