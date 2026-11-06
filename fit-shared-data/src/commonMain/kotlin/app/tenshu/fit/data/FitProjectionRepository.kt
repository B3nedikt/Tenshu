package app.tenshu.fit.data

import androidx.paging.PagingConfig
import androidx.paging.PagingData
import app.tenshu.fit.data.projection.FitProjection
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.userInitiatedWork
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.paging.toPagingData
import org.tenshu.sync.client.withBulkWriteTask
import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.retryingOnThrow
import app.tenshu.reachability.ProxyHost
import app.tenshu.reachability.RealmVerdict
import app.tenshu.reachability.RealmWritesHeldException
import app.tenshu.reachability.OPEN_REALM_GATE
import app.tenshu.reachability.UnreachableReason
import app.tenshu.fit.data.projection.FitProjection.DailyActivityInput
import app.tenshu.fit.data.projection.FitProjection.DailyNutritionInput
import app.tenshu.fit.data.projection.FitProjection.MedicalRecordInput
import app.tenshu.fit.data.projection.FitProjection.MenstruationDayInput
import app.tenshu.fit.data.projection.FitProjection.SampleInput
import app.tenshu.fit.data.projection.FitProjection.SleepSessionInput
import app.tenshu.fit.data.projection.FitProjection.WorkoutInput
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ChartRange
import app.tenshu.liststate.ListLoadState
import app.tenshu.liststate.ListLoadState.Companion.itemsOrEmpty
import app.tenshu.liststate.ListLoadState.Companion.mapItems
import org.tenshu.core.getOrNull
import org.tenshu.core.health.HealthImportRun
import org.tenshu.identity.Device
import org.tenshu.identity.client.identityService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.timeline.client.timelineService
import app.tenshu.fit.model.DetectedMovement
import app.tenshu.fit.model.CategorySummary
import app.tenshu.fit.model.CycleEntry
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.NutritionDay
import app.tenshu.fit.model.FitProfile
import app.tenshu.fit.model.Goals
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.ImportCategory
import app.tenshu.fit.model.ImportProgress
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.BatchImportSummary
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.DataSourceProjection
import app.tenshu.fit.model.DataSourceRow
import app.tenshu.fit.model.HealthSourceInput
import app.tenshu.fit.model.ImportRunEntry
import app.tenshu.fit.model.ImportRunInput
import app.tenshu.fit.model.TenshuHealthInput
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.SharingReadiness
import app.tenshu.fit.model.MedicalRecord
import app.tenshu.fit.model.Metric
import app.tenshu.fit.model.RecordedTrack
import app.tenshu.fit.model.SampleLatest
import app.tenshu.fit.model.SleepSession
import app.tenshu.fit.model.Units
import org.tenshu.preference.BiologicalSex
import org.tenshu.preference.client.NoopOwnerProfile
import org.tenshu.preference.client.OwnerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.searchAll
import org.tenshu.core.inRange
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.core.TContext
import org.tenshu.core.TBoolean
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.fold
import org.tenshu.core.number.TInteger
import org.tenshu.core.time.UnixInstant
import app.tenshu.fit.data.StepBoutDetector.toDetectedMovement
import org.tenshu.core.health.deleteWorkout
import org.tenshu.core.health.recordHealthSample
import org.tenshu.health.client.healthService
import org.tenshu.core.equalTo
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.health.Start
import org.tenshu.core.health.Streams
import org.tenshu.core.health.recordWorkout
import org.tenshu.core.orThrow
import org.tenshu.health.common.HrSample
import org.tenshu.health.common.RouteSample
import org.tenshu.health.common.WorkoutStreams
import org.tenshu.health.common.WorkoutStreamsCodec
import kotlin.math.roundToInt
import kotlin.time.Clock
import app.tenshu.fit.model.Workout as UiWorkout
import org.tenshu.core.health.DailyActivity as SvcDailyActivity
import org.tenshu.core.health.DailyNutrition as SvcDailyNutrition
import org.tenshu.core.health.HealthSample as SvcHealthSample
import org.tenshu.core.health.LatestSample as SvcLatestSample
import org.tenshu.core.health.MenstruationDay as SvcMenstruationDay
import org.tenshu.core.health.MedicalRecord as SvcMedicalRecord
import org.tenshu.core.health.SleepSession as SvcSleepSession
import org.tenshu.core.health.Workout as SvcWorkout
import org.tenshu.core.timeline.MovementTrace as SvcTrace

/** The GenericTaskSpec kind id the Fit import registers under. */
private const val FIT_IMPORT_TASK_KIND = "fit/health-import"

/** Renew the task lease every N record writes (see withImportTask). */
private const val HEARTBEAT_EVERY_RECORDS = 100

/**
 * The real [FitRepository] — a **read-model projector** over the `:health-service` `Workout`
 * store (`docs/proposals/fit-app.md` §3 D1 + `timeline-services.md` §6). Reads workouts via one
 * [TenshuClient], extracts lightweight inputs, and maps them to the presentation model with
 * [FitProjection]. Binds behind the same Koin `single<FitRepository>` the mock uses — **zero
 * VM/UI change**.
 *
 * **What is real vs. stubbed (honest thinness — see [FitProjection] KDoc):**
 *  - **Real, from the store:** `workouts()`, `workout(id)`, `addWorkout()` (→ `recordWorkout`),
 *    the merged `aggregates()` (stored `DailyActivity` over per-workout-day fallback),
 *    `sleepSessions()` (stored `SleepSession`), `samplesLatest()` (stored `HealthSample`, latest
 *    per metric), `medicalRecords()` (stored `MedicalRecord`), and `categories()` (all teasers
 *    but Nutrition/Cycle).
 *  - **No store:** Nutrition + Cycle category details (dim to "No entries" / "Not set up").
 *  - **Real, from REALM preference rows (fit-app.md §18):** `goals` (Fit's own) and the declared
 *    half of `profile` — name / birth date / sex / units, which live in the platform-level
 *    `OwnerPreferences` because they describe the *user*, not their fitness. `profile`'s weight
 *    joins in the latest `HealthSample` measurement rather than storing a second copy of it.
 *  - **Real, over the [HealthImporter] seam (§4.2):** `availableSources()` / `startImport()`
 *    read from the bound importer and persist via the shared [HealthImportWriter]. Android binds
 *    the Health Connect importer, iOS HealthKit; absent a live source the importer offers none
 *    ([NoHealthSource]). ⚠⚠ An [ImportOutcome.Sample] is **never persisted** — see `runImport`.
 *    `onboarded()` stays `true` (a real store boots to the shell; the import surface is
 *    reachable from Settings/onboarding but not gated on).
 *
 * **The [TenshuClient] is Control's** (`fit-app.md` §10): in the app it reaches Control's proxy over
 * IPC, so every row read and written here lands in Control's durable, synced store. Fit hosts no
 * proxy of its own. Tests inject a client over an in-process `TestProxy`
 * (`FitPipelineIntegrationTest` drives this repository end-to-end over that stack).
 */
class FitProjectionRepository(
    private val client: TenshuClient,
    private val context: TContext,
    private val importer: HealthImporter = NoHealthSource,
    /**
     * The wall clock behind [today] and the zone conversions. Injectable so a test can cross
     * midnight without waiting for one — see `FitTodayRolloverTest`.
     *
     * ⚠⚠ Read through [today] / [zone], which are **getters**. This repository is a process-scoped
     * singleton: a value captured at construction keeps serving yesterday's date to a process alive
     * across midnight (a `Today` header over the previous day's rows), and shifts every displayed
     * time by an hour across a DST change or a flight.
     */
    private val clock: Clock = Clock.System,
    /**
     * Parses picked files (Takeout / GPX / TCX / FIT). Platform-bound, because parsing needs an XML
     * reader: Android, desktop and iOS bind one — see [importFiles].
     */
    private val fileParser: TakeoutParser? = null,
    /**
     * The durable recorder outbox (fit-app.md §9.13). Platform-bound (it needs a storage
     * directory): absent one (tests) a failed recorder
     * save throws instead of journalling.
     */
    private val outbox: RecordedWorkoutOutbox? = null,
    /**
     * The owner's own person — name / date of birth / sex / units — as REALM preference rows
     * (`fit-app.md` §18.2). Platform-level rather than Fit's, because it describes the *user*;
     * production DI binds `TenshuOwnerProfile` over the same client. Defaults to the no-op so a
     * harness with no preference proxy still constructs.
     */
    private val ownerProfile: OwnerProfile = NoopOwnerProfile,
    /** Fit's daily targets, likewise persisted (§18.5). */
    private val goalPreferences: FitGoalPreferences = NoopFitGoalPreferences,
    /**
     * Scope for the reachability probe (fit-app.md §9.14) **and the shared read streams**
     * (fit-read-scaling.md). Platform DI passes the app scope; absent (tests,
     * previews) no probe runs, reachability tracks only organic read/write outcomes, and
     * every collector opens its own store stream.
     */
    probeScope: CoroutineScope? = null,
    private val probeIntervalMs: Long = 5_000,
    /** Test seam for the reachability probe; production uses the real one-row select. */
    probe: (suspend () -> Boolean)? = null,
    /**
     * The schema-skew verdict (`core-schema-skew-surface.md`), so the shell banner can say
     * *update Control* instead of *can't reach Control* when the proxy is answering and refusing.
     *
     * ⚠ Absent (tests, previews, any wiring site that does not bind it) every outage reads as a
     * transport problem, not a crash.
     */
    schemaSkew: StateFlow<UnreachableReason.Skew?>? = null,
    /**
     * The realm schema pull fence (`core-schema-skew-surface.md`) — the services this device
     * is behind on, empty when in step.
     *
     * ⚠⚠ Absent, a fenced device renders **no banner at all**, the worst of the three outcomes: the
     * fence gates `pullAllowed` only, so the app answers every read and looks completely healthy
     * while silently receiving nothing.
     */
    schemaFence: StateFlow<List<String>>? = null,
    /**
     * Which binary owns this app's proxy — read from the proxy's own `RuntimeIdentity`
     * row, not assumed. ⚠ `null` means no host declared itself; the banner then reports the fence
     * without naming an app rather than guessing wrong on one platform.
     */
    proxyHost: StateFlow<ProxyHost?>? = null,
    /**
     * Fit's own detection setting — the Tenshu Health row's *status*. ⚠ Read here rather
     * than in a view model because the row is built by `DataSourceProjection`, and D10 keeps every
     * "what does this row say" decision in that one tested place.
     *
     * ⚠⚠ **Nullable, and NOT defaulted to the Noop.** `NoopAutoDetectPreferences.enabled()` emits
     * the preference's *default* (`false`), so binding it here would render "Detection off" on
     * every surface with no preference store — a confident claim about the user's configuration
     * made by something that cannot read it. Absent, the row is omitted instead.
     */
    private val autoDetect: AutoDetectPreferences? = null,
    /**
     * Resolves this device's sync id for the Data sources registry
     * (`fit-data-sources-restructure.md`). Same shape and same contract as
     * `TenshuAutoDetectPreferences`: ⚠ **null means the identity is still coming up, and is
     * never a licence to substitute one** — a run written under a borrowed device id lands in
     * another device's row, and the row syncs. Defaults to the real sync-status read.
     */
    private val resolveDeviceId: suspend () -> String? = {
        runCatchingCancellable { client.syncService.getSyncStatus().getOrNull()?.deviceId?.get() }.getOrNull()
    },
    /**
     * Whether Control's device has a realm (ADR 0025 §Decision 8) — wired on Android/desktop to
     * `syncService.getSyncStatus()`. ⚠ Last and defaulted so iOS route-1 constructs untouched;
     * absent, `NotSetUp` never fires.
     */
    private val controlHasRealm: app.tenshu.reachability.RealmProbe,
) : FitRepository {

    /**
     * The durable half of an import. ⚠ Best-effort throughout: bookkeeping about an
     * import must never be able to fail the import it describes.
     */
    private val runRecorder = HealthImportRunRecorder(
        client = client,
        context = context,
        resolveDeviceId = resolveDeviceId,
        // Explicitly open: this recorder serves user acts only (a file import the user picked, the
        // auto-import switch), and the shell is interactive only once the wall is down (§8.6).
        beforeEachWrite = OPEN_REALM_GATE,
    )

    /**
     * The run recorder the GATED automatic import uses (ADR 0025 §8.6 write boundary): its
     * `registerSource` and `record` writes refuse while the realm gate holds. The ungated
     * [runRecorder] stays for user acts (a file import the user picked, the auto-import switch),
     * which no gated pipeline reaches.
     */
    private val gatedRunRecorder = HealthImportRunRecorder(
        client = client,
        context = context,
        resolveDeviceId = resolveDeviceId,
        beforeEachWrite = { reach.refuseIfRealmWritesHeld() },
    )

    /**
     * The health writer every GATED pipeline uses (ADR 0025 §8.6 write boundary) — the outbox drain
     * and the automatic import. Refuses each batch, chunk and dense workout while the gate holds.
     */
    private fun gatedHealthWriter() =
        HealthImportWriter(client, context, beforeEachWrite = { reach.refuseIfRealmWritesHeld() })

    private val outboxDrainMutex = Mutex()

    /** See [probeScope]'s KDoc — doubles as the share scope for the read streams. */
    private val shareScope: CoroutineScope? = probeScope

    /**
     * Share one proxy-side stream per store source across every collector
     * (fit-read-scaling.md), so a screen's `combine` and a tab revisit do not each re-pay the
     * full subscribe + proxy-side sort + IPC ship. `WhileSubscribed` keeps the upstream alive
     * across a quick tab switch; `replay = 1` repaints a revisited screen instantly (the
     * live stream then refreshes it). Without a [shareScope] (tests, previews) the raw
     * flow is returned — per-collector subscription.
     */
    private fun <T> shared(build: () -> Flow<T>): Flow<T> {
        val scope = shareScope ?: return build()
        return build().shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_TIMEOUT_MS), replay = 1)
    }

    /**
     * The shared reachability engine (`:app-reachability`, fit-app.md §9.14): probe-owned detection
     * driving the shell banner, plus Fit's recovery hook, which replays the recorder outbox (§9.13
     * — Control being back is exactly when journalled saves can land). Internal for the pipeline
     * test's reachability assertions.
     *
     * ⚠ Fit does not consume `ControlReachability.readEpoch`: `TList.flow()` retries a failed
     * subscribe itself, so wrapping every read in `resubscribing { }` as well would tear down and
     * rebuild nine already-recovering streams on each recovery, re-shipping a full snapshot per
     * stream. See [retryingOnThrow].
     */
    internal val reach = ControlReachability(
        probe = probe ?: { probeControl() },
        scope = probeScope,
        probeIntervalMs = probeIntervalMs,
        onRecovered = { drainPendingWorkouts() },
        // The transport signal: reports the outage instantly instead of after a probe interval,
        // and suppresses probing across a channel that cannot carry one.
        transportConnected = client.proxyConnected,
        schemaSkew = schemaSkew,
        schemaFence = schemaFence,
        proxyHost = proxyHost,
        // ADR 0025: lets the shell say "Control isn't installed" instead of "open Control and unlock
        // it" — an instruction naming an app that is not on the device. `null` (desktop, every
        // in-process runtime) leaves the outage worded generically.
        controlInstalled = { client.controlInstalled() },
        // One event per call the proxy served, used to WAKE the probe rather than to answer for
        // it — see `ControlReachability.answered`.
        answered = client.proxyAnswered,
        controlHasRealm = controlHasRealm,
    )

    init {
        // ⚠⚠ The resumption of held recorder saves (ADR 0025 §Decision 8.6). With a realm probe
        // wired, [drainPendingWorkouts] is held while the verdict is PENDING or ABSENT — which
        // includes the instant `onRecovered` fires — so the drain rides this edge instead: once
        // after each recovery's realm read, and once when a realm appears.
        // Never fires where no probe is wired (iOS route-1, tests), so `onRecovered` carries on there.
        // ⚠ Declared after [reach]: property initialisers and init blocks run in order.
        // ⚠⚠ The marker is taken HERE, synchronously, before anything can drain — the
        // subscribe-before-work rule: a release that lands while some other drain is running
        // still produces one more drain afterwards.
        val releasesSeen = reach.realmReleaseMarker
        probeScope?.launch {
            reach.onEachRealmWritableEdge(since = releasesSeen) { drainPendingWorkouts() }
        }
    }

    /**
     * The device's zone, re-read on every use. A captured value is wrong the moment the user
     * changes zone (a flight) or the offset does (DST) — see [clock].
     */
    private val zone: TimeZone get() = TimeZone.currentSystemDefault()

    override val today: LocalDate get() = clock.todayIn(zone)

    private val importProgressState = MutableStateFlow<List<ImportProgress>?>(null)
    private val importStatusState = MutableStateFlow<ImportStatus>(ImportStatus.Idle)
    /** Guards against two imports running at once (see [startImport]). */
    private val importLock = Mutex()


    // Aggregates come from the maintained DailyActivity rows (a precomputed daily aggregate) plus a
    // **bounded** recent-workout window for the per-workout-day fallback — not the whole workout set.
    // A 10-year Takeout is ~10k workouts; loading them all to compute a weekly total OOM'd Control
    // (docs/proposals/fit-read-scaling.md). Daily rows carry the history; the workout window only
    // fills recent days that lack one (e.g. a just-recorded workout).
    override fun aggregates(): Flow<ListLoadState<DailyAggregate>> =
        combine(dailyActivityInputs(), aggregateWorkoutInputs()) { daily, workouts ->
            mergedLoad(daily, workouts) {
                FitProjection.dailyAggregates(daily.itemsOrEmpty(), workouts.itemsOrEmpty(), zone)
            }
        }.flowOn(Dispatchers.Default)

    // ── The projected reads, as load state ────────────────────────────────────
    // Private, because only [categories] needs the phase of each part; the public reads below are
    // the flattened last mile. Keeping the pair adjacent is deliberate — a future surface that
    // wants the three states on (say) Sleep promotes the public one to the private one's type
    // rather than re-deriving where the failure went.

    private fun nutritionState(): Flow<ListLoadState<NutritionDay>> =
        dailyNutritionInputs().map { it.mapItems(FitProjection::nutritionDays) }

    private fun cycleState(): Flow<ListLoadState<CycleEntry>> =
        menstruationDayInputs().map { it.mapItems(FitProjection::cycleDays) }

    private fun sleepState(): Flow<ListLoadState<SleepSession>> =
        sleepInputs().map { state -> state.mapItems { FitProjection.sleeps(it, zone) } }

    private fun samplesLatestState(): Flow<ListLoadState<SampleLatest>> =
        latestSampleInputs().map { state -> state.mapItems { FitProjection.samples(it, zone) } }

    private fun medicalRecordsState(): Flow<ListLoadState<MedicalRecord>> =
        medicalRecordInputs().map { it.mapItems(FitProjection::medicalRecords) }

    override fun nutrition(): Flow<List<NutritionDay>> =
        nutritionState().map { it.itemsOrEmpty() }.flowOn(Dispatchers.Default)

    override fun cycle(): Flow<List<CycleEntry>> =
        cycleState().map { it.itemsOrEmpty() }.flowOn(Dispatchers.Default)

    // The History feed + Home's latest-workout + Profile's sources all read this **windowed** to the
    // most recent [WORKOUT_WINDOW] — newest-first at the store (`sortByDescending(Start).take(n)`
    // bounds the read; History reaches older workouts through [workoutsPaged]). Reading every
    // workout here breaks the app after a large import.
    override fun workouts(): Flow<List<UiWorkout>> =
        recentWorkoutInputs().map { state -> state.mapItems { FitProjection.workouts(it, zone) }.itemsOrEmpty() }
            .flowOn(Dispatchers.Default)

    /**
     * The History feed, paged — the same relation as [workouts] with no `take` at all, cut into
     * pages by `:core-paging` (`fit-history-paging-handover.md` §4). Reaches past
     * [WORKOUT_WINDOW], which is what makes History's "Earlier" affordance real rather than a
     * dead label; it does **not** replace [workouts], which Home, Profile, the detection dedup
     * passes and History's own week roll-up still read as a list.
     *
     * Four things here are load-bearing:
     *
     * - **The sort sits on the receiver and the page is `drop(pos).take(n)`.** Ordered-window
     *   fusion (`core-ordered-window-fusion.md` F1) folds that window under the `sortBy` into one
     *   bounded thin pass per page. Both `drop`/`take` orders fold to the same window
     *   (`core-keyset-paging.md` §6.3); what does not fuse is an unbounded window — a `drop` with
     *   no `take`.
     * - **The `select` rides the projection slot, not the base chain.** Baked in ahead of the
     *   window it re-runs over the entire relation on every change (Drive's 2k-file folder ANR —
     *   `TenshuPagingSource` KDoc), and the change observer would stream the whole projected list
     *   over IPC.
     * - ⚠⚠ **`cachedIn` downstream is allowed, and `HistoryViewModel` applies it**
     *   (`core-paging-scroll-restoration.md` §0). The guard that a newly saved workout still
     *   appears without re-entering the screen is `HistoryFeedReactivityTest`, not this comment.
     * - **The lambda field-selector**, not `sortByDescending(Workout.Start)` — see
     *   [buildRecentWorkoutInputs] for why the property overload cannot round-trip.
     *
     * ⚠ Not `shared()` like the list reads: each collector gets its own `Pager`, which is what
     * `:core-paging` expects (a shared, replayed `PagingData` would hand a second collector a
     * generation whose presenter is already driving someone else's scroll).
     */
    override fun workoutsPaged(): Flow<PagingData<UiWorkout>> =
        client.healthService.workouts
            .sortByDescending { start }
            .toPagingData(
                mapper = { e: Entity<SvcWorkout> -> FitProjection.workout(workoutInput(e), zone) },
                pagingConfig = PagingConfig(
                    // initialLoadSize = pageSize, as Drive does: Paging's default 3× triples the
                    // first-paint cost for rows a screenful never shows. A day card is tall, so
                    // 20 workouts is already several screens.
                    pageSize = WORKOUT_PAGE_SIZE,
                    initialLoadSize = WORKOUT_PAGE_SIZE,
                    enablePlaceholders = false,
                ),
            ) { window ->
                // Metadata only — deliberately no `Workout.Streams`. It is the byte-carrying
                // content-plane property, and a paging window loads through `getAll()`, a one-shot
                // terminal that does not materialise `PendingOnDemandProperty` at all (see
                // [workout], which uses `get()` precisely for that). Projecting it here would cost
                // the blob per row and still read as null.
                window.select { fields(*WORKOUT_FIELDS) }
            }

    override fun categories(): Flow<ListLoadState<CategorySummary>> =
        // `combine` maxes at five typed flows, so the day-aggregate trio is folded first.
        combine(
            combine(aggregates(), nutritionState(), cycleState()) { a, n, c -> Triple(a, n, c) },
            sleepState(), samplesLatestState(), medicalRecordsState(),
        ) { (aggs, nutrition, cycle), sleep, samples, medical ->
            mergedLoad(aggs, nutrition, cycle, sleep, samples, medical) {
                FitProjection.categories(
                    aggs.itemsOrEmpty(), nutrition.itemsOrEmpty(), cycle.itemsOrEmpty(),
                    sleep.itemsOrEmpty(), samples.itemsOrEmpty(), medical.itemsOrEmpty(), today,
                )
            }
        }.flowOn(Dispatchers.Default)

    // The maintained latest-per-metric rollup (`LatestSample`, fit-read-scaling.md) —
    // one row per (metric, resting), kept fresh by the proxy-side maintainer on every sample
    // save, so this never scans the whole time-series (a 10-year import is tens of thousands of
    // samples; Browse's categories() rides this read).
    override fun samplesLatest(): Flow<List<SampleLatest>> =
        samplesLatestState().map { it.itemsOrEmpty() }.flowOn(Dispatchers.Default)

    override fun sampleHistory(metric: Metric): Flow<List<SampleLatest>> =
        sampleInputsFor(metric).map { state ->
            state.mapItems { inputs -> FitProjection.samples(inputs, zone) }.itemsOrEmpty()
        }.flowOn(Dispatchers.Default)

    override fun sleepSessions(): Flow<List<SleepSession>> =
        sleepState().map { it.itemsOrEmpty() }.flowOn(Dispatchers.Default)

    override fun medicalRecords(): Flow<List<MedicalRecord>> =
        medicalRecordsState().map { it.itemsOrEmpty() }.flowOn(Dispatchers.Default)

    override fun goals(): Flow<Goals> = goalPreferences.observe()

    /**
     * The user as Profile renders them — the owner's **preference** rows joined with their latest
     * body **measurement** (`fit-app.md` §18.2).
     *
     * The join is the whole point of the split. Name / birth date / sex / units are declared, and
     * only the user can declare them (⚠ Health Connect has no demographics API at all, so on
     * Android there is nowhere else they could come from); weight is *measured*, already flows in
     * from Health Connect as a `HealthSample`, and is already rendered by Browse → Body — so
     * reading it here rather than storing a second copy is what stops the two from disagreeing
     * after the next import.
     *
     * Height joins the same way: both platforms record it, so it is read rather than declared.
     * Absent a reading it stays `null` and reads as "Not set" — never an invented default.
     */
    override fun profile(): Flow<FitProfile> =
        combine(ownerProfile.observe(), samplesLatest()) { owner, samples ->
            FitProfile(
                name = owner.name,
                // Rounded to whole centimetres: the model is an `Int` and no display anywhere
                // renders a fraction of a centimetre (the imperial path re-derives ft/in from it).
                heightCm = samples.firstOrNull { it.metric == Metric.Height }?.value?.roundToInt(),
                weightKg = samples.firstOrNull { it.metric == Metric.Weight }?.value,
                birthDate = owner.birthDate,
                sex = owner.sex,
                units = owner.units.toFitUnits(),
            )
        }

    /** A real store implies onboarding is done — the app boots to the shell (see class KDoc). */
    override fun onboarded(): Flow<Boolean> = flowOf(true)

    override fun controlReachable(): StateFlow<Boolean?> = reach.state

    override fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<UnreachableReason?> = reach.reason

    override fun recheckControl() = reach.recheck()

    /**
     * The detail read — includes the byte-carrying `Workout.Streams` property (excluded from the
     * reactive [workouts] list select) and decodes it to the route + HR curve. Filtered to the one
     * matching entity so only its blob is materialised.
     */
    override suspend fun workout(id: String): WorkoutRead =
        // A one-shot snapshot read — a Maybe `resolve()` terminal, NOT `flow().first()` (`flow()`
        // is a reactive *stream* that never replays a snapshot to a late subscriber — `.first()`
        // hangs when nothing changes after subscription) and NOT `getAll()`: with the
        // content-plane `Workout.Streams` (read-scaling option B), only the Maybe/stream
        // terminals materialise a `PendingOnDemandProperty`, so `resolve()` is what makes a
        // not-yet-local blob fetch from the relay before the entity crosses IPC — the
        // `getRemoteFile` shape.
        try {
            val entity = client.healthService.workouts
                .search<SvcWorkout> { workoutId equalTo id }
                .select { fields(*WORKOUT_FIELDS); relations(SvcWorkout.Streams) }
                .resolve()
                .foldTracked(null as org.tenshu.core.entity.Entity<SvcWorkout>?) { it }
                ?: return WorkoutRead.NotFound
            // ⚠⚠ The decode is INSIDE the guard on purpose: a `WorkoutStreamsCodec` throw on the
            // `Workout.Streams` carrier (`No type id registered for bytes: …`) must become
            // [WorkoutRead.Failed], not propagate out of a `viewModelScope.launch` with no handler.
            val streams = WorkoutStreamsCodec.decode(entity.get(SvcWorkout.Streams)?.get())
            WorkoutRead.Found(FitProjection.workout(workoutInput(entity), zone, streams))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ⚠⚠ Log it, and do NOT mark unreachable — the same two departures `retryingOnThrow`
            // records for the nine read streams, applied to this one-shot read. A throw
            // is not evidence the proxy is gone (`flow()` retries a failed subscribe itself and
            // reports it as a `TResult.Failure` emission), so claiming so parks the §9.14
            // "Can't reach Tenshu Control" banner on what is almost always a client-side bug —
            // and sends the reader hunting a connection fault instead of the decode that threw.
            fitLog("workout($id): read failed", e)
            WorkoutRead.Failed
        }

    override suspend fun addWorkout(workout: UiWorkout, track: RecordedTrack?): WorkoutSaveOutcome {
        // One mapping, one write path: the summary + dense streams become the same
        // [ImportedWorkout] every importer produces, and persistence goes through the shared
        // [HealthImportWriter] (which encodes the streams blob). A first-party recording hands us
        // its dense [track] with REAL GPS fixes → persist them (E7 SI truth). Without a track (a
        // summary add, or the mock recorder), fall back to the real HR series carried on the
        // workout; its route there is the abstract 0..1 form, not real GPS, so it is not stored.
        // §9.7.
        val imported = ImportedHealth(workouts = listOf(importedWorkout(workout, track)))
        val outbox = outbox ?: run {
            // No durable journal bound: fail LOUDLY so the recorder engine keeps the session and
            // Finish retries (tech-debt "TResult ops discardable").
            // Gated (ADR 0025 §8.6): a refusal throws, so the recorder keeps the session and retries.
            gatedHealthWriter().write(imported)
            return WorkoutSaveOutcome.Synced
        }
        // Durable-first (the CapturedVisitWriter recipe): journal, then drain oldest-first —
        // which includes this entry, so a healthy proxy sees it immediately and a dead one keeps
        // it for replay.
        val id = outbox.persist(imported)
        drainPendingWorkouts()
        return if (outbox.pending().none { it.first == id }) {
            WorkoutSaveOutcome.Synced
        } else {
            WorkoutSaveOutcome.SavedLocally
        }
    }

    override suspend fun awaitRealmWritable() = reach.awaitRealmWritable()

    override fun refuseIfRealmWritesHeld() = reach.refuseIfRealmWritesHeld()

    /**
     * What a refused import becomes (ADR 0025 §8.6). ⚠⚠ The two kinds part here: an AUTOMATIC pass
     * is counted as held and the bootstrap re-runs it on the next release; a MANUAL import is never
     * folded into that — it surfaces [ImportStatus.WaitingForControl] with the user's exact
     * selection and window, writes nothing, and waits for the user to retry it.
     *
     * ⚠⚠ An automatic held stop never overwrites the status wholesale: while an automatic pass
     * holds `importLock`, a manual request can arrive under the closed gate and store its
     * WaitingForControl — a plain `= Idle` here would erase the user's request, and the release
     * would re-run the generic recent import instead. So the stop only retracts the
     * `Running` its own pass published ([ownsRun]; compare-and-set, never a WaitingForControl or
     * another pass's outcome), and a pass refused at entry, which published nothing, touches nothing.
     */
    private suspend fun holdImport(
        selection: Set<CategoryId>,
        window: ImportWindow,
        automatic: Boolean,
        ownsRun: Boolean,
    ) {
        if (automatic) {
            // Test seam for the release-between-refusal-and-count race.
            beforeHeldStopRecorded?.invoke()
            heldStops.value += 1
            if (ownsRun) importStatusState.update { if (it is ImportStatus.Running) ImportStatus.Idle else it }
        } else {
            importStatusState.value = ImportStatus.WaitingForControl(selection, window)
        }
    }

    override fun realmReleaseMarker(): Long = reach.realmReleaseMarker

    override suspend fun onEachRealmRelease(since: Long, action: suspend () -> Unit) =
        reach.onEachRealmWritableEdge(since, action)

    /**
     * See [FitRepository.realmHeldStops]. Bumped by EVERY import pass the gate holds — rejected at
     * entry or stopped mid-run; an uncounted entry rejection would never be re-run.
     */
    private val heldStops = MutableStateFlow(0L)

    override fun realmHeldStops(): Long = heldStops.value

    override suspend fun onEachRealmHeldStop(since: Long, action: suspend () -> Unit) {
        var seen = since
        heldStops.collect { count ->
            if (count != seen) {
                seen = count
                action()
            }
        }
    }

    /** Test-only: runs between a held automatic pass's refusal and its stop being counted. */
    internal var beforeHeldStopRecorded: (suspend () -> Unit)? = null

    override suspend fun drainPendingWorkouts() {
        val outbox = outbox ?: return
        // ⚠⚠ ADR 0025 §Decision 8.6: with Control's device known to have no realm (or the realm read
        // still pending), the entries STAY in the durable outbox. Writing them now would land them
        // in a store that belongs to no realm, where the sync observer only holds them in memory —
        // replayed into whatever realm appears later, or stranded local by a process restart.
        // Resumed by `onEachRealmWritableEdge` (see [reach]'s collector) when a realm appears.
        if (reach.realmWritesHeld) {
            fitLog("drainPendingWorkouts: held — Control has no realm here yet (or the read is pending)")
            return
        }
        outboxDrainMutex.withLock {
            // ⚠⚠ The write boundary (ADR 0025 §8.6): every op this writer makes refuses while the
            // realm gate holds, so the drain needs no check of its own. An entry is removed only
            // after ALL of its writes landed — a refusal anywhere in it leaves it in the outbox for
            // the release-edge drain.
            val writer = gatedHealthWriter()
            for ((id, health) in outbox.pending()) {
                try {
                    writer.write(health)
                } catch (e: RealmWritesHeldException) {
                    fitLog("drainPendingWorkouts: held by the realm gate — remaining entries retained")
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Entries stay for the next trigger (next save / next launch); a dead proxy
                    // isn't hammered once per entry. The writer already logged the failure.
                    fitLog("drainPendingWorkouts: write failed — pending recordings retained for replay")
                    reach.markUnreachable()
                    return
                }
                outbox.remove(id)
            }
        }
    }

    /**
     * The cheapest possible "is Control there?" round-trip: a one-row, one-field select. Used
     * only by the recovery loop (never on a healthy path), so its cost is a slow ping while the
     * app is knowingly broken.
     */
    private suspend fun probeControl(): Boolean =
        try {
            client.healthService.workouts.take(1)
                .select { fields(SvcWorkout.Fields.WORKOUT_ID) }.getAll()
                .fold(onSuccess = { true }, onFailure = { false })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dead dispatch may throw rather than fold (see [unreachable]).
            false
        }

    /**
     * As [foldTracked], but keeping the failure instead of flattening it to empty rows.
     *
     * This is the read-side shape every `*Inputs` stream uses. The reachability signal
     * (§9.14) explains an unreachable *Control*; it cannot explain a read that failed for any
     * other reason — an announce-gate refusal, a `NotFound`, a decode error — and folded to
     * `emptyList()` those render as "you have no data". `ListLoadState` is the
     * type that makes that unrepresentable; [aggregates] and [categories] carry it all the way
     * to Home and Browse, and the remaining reads flatten it with `itemsOrEmpty()` at the last
     * mile because their surfaces do not (yet) distinguish the three states.
     */
    private fun <T : org.tenshu.core.Data?, R> TResult<T>.loadTracked(
        onSuccess: (T) -> List<R>,
    ): ListLoadState<R> =
        fold(
            onSuccess = { v ->
                ListLoadState.Loaded(onSuccess(v))
            },
            onFailure = {
                reach.markUnreachable()
                ListLoadState.Failed
            },
        )

    /**
     * One phase for a set of reads that a single surface stands on: [ListLoadState.Failed] if any
     * of them failed, else `Loaded` of [merge].
     *
     * ⚠ Any-failed rather than best-effort, deliberately. These are all IPC reads against the same
     * proxy, so in practice they fail together; and a partially-merged answer rendered as complete
     * is the same lie in a smaller font — Browse would show "Sleep: not set up" because the sleep
     * read broke, not because the user has never slept. `Failed` keeps the screen's last good rows
     * (the UI branches on the phase, it does not blank), so nothing is lost by saying so.
     */
    private fun <R> mergedLoad(
        vararg parts: ListLoadState<*>,
        merge: () -> List<R>,
    ): ListLoadState<R> =
        if (parts.any { it is ListLoadState.Failed }) ListLoadState.Failed
        else ListLoadState.Loaded(merge())

    /**
     * Fold a result while tracking Control reachability (§9.14): a success proves the proxy
     * answered; a failure — the executor folds transport errors ("Proxy not connected") into a
     * `TResult.Failure` — flips the signal driving the shell banner + recovery probe. Reads still
     * degrade to [empty] so screens render (blank, but *explained*).
     *
     * ⚠ Retained only for the non-list reads. A *list* read must use [loadTracked] — see its KDoc.
     */
    private fun <T : org.tenshu.core.Data?, R> TResult<T>.foldTracked(empty: R, onSuccess: (T) -> R): R =
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
     * Last-resort guard on a read stream: survive a *throw* into the collector, retrying rather
     * than completing. [app.tenshu.reachability.retryingOnThrow] carries the reasoning — this is
     * the Fit-shaped wrapper that gives it [fitLog]'s tag.
     *
     * ⚠ It is **not** the recovery path. [org.tenshu.core.collection.list.flow] retries a failed
     * subscribe itself and reports the failure as a `TResult.Failure` **emission**, which
     * [foldTracked] already handles — so an unreachable Control never reaches here.
     */
    private fun <T> Flow<T>.retrying(read: String): Flow<T> =
        retryingOnThrow(read) { message, error -> fitLog(message, error) }

    private fun importedWorkout(workout: UiWorkout, track: RecordedTrack?): ImportedWorkout =
        ImportedWorkout(
            workoutId = workout.workoutId,
            activityType = FitProjection.activityTypeName(workout.activityType),
            source = FitProjection.sourceName(workout.source),
            startMs = workout.start.epochMillis(),
            endMs = workout.end.epochMillis(),
            durationSec = workout.durationSec.toLong(),
            title = workout.title,
            distanceM = workout.distanceM?.toLong() ?: 0,
            activeEnergyKj = workout.activeEnergyKj?.toLong() ?: 0,
            avgHr = workout.avgHr?.toLong() ?: 0,
            steps = workout.steps?.toLong() ?: 0,
            route = track?.route.orEmpty().map {
                RouteSample(
                    tSec = it.tSec,
                    latE7 = (it.lat * 1e7).roundToInt(),
                    lngE7 = (it.lng * 1e7).roundToInt(),
                    altCm = it.altM?.let { m -> (m * 100).roundToInt() } ?: RouteSample.NO_ALT,
                )
            },
            heartRate = (if (track != null) track.hr else workout.hrSeries).map { HrSample(it.t, it.bpm) },
        )

    // ── Onboarding + import (real, over the [HealthImporter] seam — §4.2) ──────────────────

    /** The sources the bound importer can read from, plus the always-available "start fresh". */
    override fun availableSources(): List<ImportSource> = importer.availableSources() + ImportSource.StartFresh

    override fun importCategories(): List<ImportCategory> = emptyList()

    /** Import progress; `null` until [startImport] runs, then a completed snapshot per category. */
    override fun importProgress(): Flow<List<ImportProgress>?> = importProgressState.asStateFlow()

    /** The last import's outcome, as the UI renders it. [ImportStatus.Idle] until one runs. */
    override fun importStatus(): StateFlow<ImportStatus> = importStatusState.asStateFlow()

    /**
     * Parses picked files (a Takeout archive's members, or a bare activity file) and persists them
     * through the same [HealthImportWriter] every other importer uses, so a file-imported day or
     * workout reads back through the projector identically. Reports through [importStatus] like any
     * other import — including the empty case, which for a file is a real answer worth saying out
     * loud: "we read your export and found nothing we could use" is not the same as silence.
     *
     * A platform with no [fileParser] bound (no XML reader) reports SourceUnavailable
     * rather than pretending the file imported.
     */
    override suspend fun importFiles(source: ActivityFileSource) {
        val parser = fileParser
        if (parser == null) {
            fitLog("importFiles: no file parser on this platform")
            importStatusState.value = ImportStatus.SourceUnavailable
            return
        }
        // ⚠⚠ WAITS for an import in flight, never steps aside as `startImport` does. Two Health
        // Connect passes cover each other; a Health Connect pass does not cover a picked file. And
        // the race is built in on Android: coming back from the system picker foregrounds Fit, which
        // starts the automatic refresh a few milliseconds before the file arrives here — so a
        // `tryLock` would drop the user's file, silently, on an ordinary pick (store-screenshots.md
        // §8.1; FileImportPipelineTest).
        if (!importLock.tryLock()) {
            fitLog("importFiles: an import is in flight — waiting for it")
            importLock.lock()
        }
        try {
            fitLog("importFiles: streaming ${source.count?.toString() ?: "an unknown number of"} file(s)")
            importStatusState.value = ImportStatus.Running(0)
            // Explicitly open: a file import is a user act, and the shell — with its import button —
            // is interactive only once the wall (Checking / NotSetUp) is down (ADR 0025 §8.6).
            val writer = HealthImportWriter(client, context, beforeEachWrite = OPEN_REALM_GATE)
            val pacer = ImportPacer()
            var total = 0
            // Rows the store had never seen, summed across the archive's chunks — the same
            // figure the platform path records, for the same reason.
            var added = 0
            // A file import is always a deliberate act, so *every* outcome is recorded —
            // including "we read your export and found nothing", which is the one case the
            // no-op rule suppresses for automatic passes but which here is the answer the user
            // came for. There is no source row to register: Takeout is not a connection (D11).
            val startedAt = clock.now().toEpochMilliseconds()
            var workouts = 0L
            suspend fun recordRun(status: String, error: String? = null) = runRecorder.record(
                kind = HealthImportRun.KIND_TAKEOUT,
                window = HealthImportRun.WINDOW_FILE,
                startedAt = startedAt,
                finishedAt = clock.now().toEpochMilliseconds(),
                status = status,
                itemCount = added.toLong(),
                workoutCount = workouts,
                byteCount = source.totalBytes,
                fileName = source.displayName,
                error = error,
            )

            // Stream: parse and persist each file as the source yields it, so a member's bytes are
            // collectable before the next is read — peak memory is one file, not the whole archive
            // (~96 MB on a real 10-year export). Each record is written through [pacer], which both
            // updates progress and applies **backpressure**: a bulk Takeout import fires tens of
            // thousands of writes at Control's proxy, and unpaced they outrun its flush + GC and blow
            // its heap (a real 10-year export OOMs it). The pacer pauses periodically, and pauses
            // *longer* as the running total grows — the deeper in, the more pressure, so the more slack.
            try {
                // ⚠ User-started (`control-background-work-off-fgs.md` §5.7): a file import is
                // always picked by the user, so Control may hold its foreground service for the
                // task's writes. Fit's own import service keeps Fit up; this keeps Control.
                userInitiatedWork {
                    withImportTask { heartbeat ->
                        source.forEach { file ->
                            val chunk = parser.parse(file)
                            if (!chunk.isEmpty) {
                                // Counted before the write, from the parsed chunk: `onRecord` is an
                                // untyped tick, so it cannot tell a workout apart from a sample.
                                workouts += chunk.workouts.size.toLong()
                                added += writer.write(chunk) {
                                    total++
                                    importStatusState.value = ImportStatus.Running(total)
                                    pacer.onRecord(total)
                                    heartbeat()
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Partial rows already written stay — the ops are content-addressed, so a re-run folds
                // them rather than duplicating. But this attempt did not *finish*, so it is not a
                // Failed, and not an Imported; back to Idle (a re-import resumes cleanly).
                importStatusState.value = ImportStatus.Idle
                throw e
            } catch (e: Throwable) {
                fitLog("importFiles: THREW after $total record(s)", e)
                importStatusState.value = ImportStatus.Failed(e.message ?: e::class.simpleName.orEmpty())
                // Partial rows already written stay, so the run is a real fact with a real
                // count — recorded as FAILED so the history says "1 200 in, then it broke"
                // rather than losing the attempt entirely.
                recordRun(HealthImportRun.STATUS_FAILED, error = e.errorCode())
                throw e
            }

            importStatusState.value =
                if (total == 0) ImportStatus.NoData(ImportSource.ImportFile)
                else ImportStatus.Imported(ImportSource.ImportFile, total)
            recordRun(
                // ⚠ `total == 0` still: an archive whose every row the store already had is
                // "nothing new", but the user did pick a file and it did parse — NO_DATA is
                // reserved for an export with nothing usable in it.
                if (total == 0) HealthImportRun.STATUS_NO_DATA
                else HealthImportRun.STATUS_SUCCEEDED
            )
            fitLog("importFiles: COMPLETE — status=${importStatusState.value}")
        } finally {
            importLock.unlock()
        }
    }

    private val sharingReadinessState = MutableStateFlow(SharingReadiness())

    override fun sharingReadiness(): StateFlow<SharingReadiness> = sharingReadinessState

    // ── Data sources ───────────────────────────────────────────────────────────────────

    /**
     * This device's sync id, resolved once and cached — the registry reads need it on every
     * emission to decide which rows are *this* device's, and re-resolving per emission would
     * put an IPC round trip inside a reactive read.
     *
     * ⚠ Cached only once it is *known*. A null is not cached: identity comes up asynchronously
     * after a cold start, and latching "unknown" would leave every row looking like another
     * device's for the life of the process.
     */
    private var cachedDeviceId: String? = null

    private suspend fun thisDeviceId(): String? =
        cachedDeviceId ?: runCatchingCancellable { resolveDeviceId() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { cachedDeviceId = it }

    /**
     * The device roster, as `deviceId → name`.
     *
     * ⚠⚠ **An upgrade, never a gate.** `HealthSource.deviceLabel` is stamped from the platform
     * name at register time and is always renderable; this replaces it only where the roster
     * actually answers. An empty map is "the roster has not loaded", which is
     * **indistinguishable** from "that device is gone" — revocation *deletes* the `Device` row
     * (`RevokeDeviceImplementation`) rather than flagging it — so absence here must never dim,
     * hide or annotate a row (§17 Q2).
     */
    private fun deviceNames(): Flow<Map<String, String>> = flow {
        // Stamped labels first, always. Everything below is an improvement on them.
        emit(emptyMap())
        // ⚠⚠ Built inside the flow, and guarded. `client.identityService` **throws
        // synchronously** when the service is not registered — a Control without it, a runtime
        // that never declared the client module — so a bare `client.identityService.devices…` at
        // construction time takes the whole Data sources section down with it, not just the
        // labels (`No schema registered for service id: tenshu/identity`).
        val upstream = runCatching {
            client.identityService.devices
                .select { fields(Device.Fields.DEVICE_ID, Device.Fields.NAME) }
                .flow()
        }.getOrElse {
            fitLog("deviceNames: identity service unavailable — keeping the stamped labels", it)
            return@flow
        }
        emitAll(
            upstream.map { r ->
                r.foldTracked(emptyMap<String, String>()) { rows ->
                    rows.toList().mapNotNull { e ->
                        val id = runCatching {
                            e.getFieldValue<TString>(Device.Fields.DEVICE_ID).get()
                        }.getOrNull() ?: return@mapNotNull null
                        val name = runCatching {
                            e.getFieldValue<TString>(Device.Fields.NAME).get()
                        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        id to name
                    }.toMap()
                }
            }
        )
    }.catch {
        // Likewise for a failure mid-stream: the stamped labels are perfectly serviceable, and a
        // Data sources section must not go down with the device list.
        // ⚠ Cancellation first: `catch` does not exempt it, and a collector that went away is not
        // a roster failure to fall back from.
        if (it is CancellationException) throw it
        fitLog("deviceNames: roster read failed — keeping the stamped labels", it)
        emit(emptyMap())
    }

    private fun sourceInputs(): Flow<List<HealthSourceInput>> =
        client.healthService.sources
            .select { fields(*SOURCE_FIELDS) }
            .flow()
            .map { r -> r.foldTracked(emptyList<HealthSourceInput>()) { it.toList().map(::healthSourceInput) } }
            .retrying("healthSources")

    private fun runInputs(): Flow<List<ImportRunInput>> =
        client.healthService.importRuns
            .select { fields(*RUN_FIELDS) }
            .flow()
            .map { r -> r.foldTracked(emptyList<ImportRunInput>()) { it.toList().map(::importRunInput) } }
            .retrying("importRuns")

    /**
     * ⚠ One flow, folding the stored rows with the *live* import status and this device's id —
     * see `FitRepository.dataSources`. The projection (`DataSourceProjection`) owns every rule;
     * this only supplies its three inputs.
     */
    override fun dataSources(): Flow<List<DataSourceRow>> =
        combine(
            combine(sourceInputs(), deviceNames()) { sources, names -> sources.withRosterNames(names) },
            importStatusState,
            autoDetect?.enabled()
                ?.map<Boolean, TenshuHealthInput?> { TenshuHealthInput(autoDetect = it) }
                // ⚠ `onStart` so a registry emission is never held back waiting on a preference
                // read; null until it arrives, and null means "omit the row".
                ?.onStart { emit(null) }
                ?: flowOf(null),
        ) { sources, live, tenshu -> Triple(sources, live, tenshu) }
            .map { (sources, live, tenshu) ->
                DataSourceProjection.dataSourceRows(
                    sources = sources,
                    thisDeviceId = thisDeviceId(),
                    liveStatus = live,
                    tenshu = tenshu,
                )
            }

    override fun batchImport(): Flow<BatchImportSummary?> =
        runInputs().map { runs ->
            DataSourceProjection.batchImportSummary(runs, canImportFiles = fileParser != null)
        }

    override fun importHistory(kind: DataSourceKind?): Flow<List<ImportRunEntry>> =
        combine(
            runInputs(),
            combine(sourceInputs(), deviceNames()) { sources, names -> sources.withRosterNames(names) },
        ) { runs, sources -> runs to sources }
            .map { (runs, sources) ->
                DataSourceProjection.importHistory(
                    runs = runs,
                    kind = kind,
                    thisDeviceId = thisDeviceId(),
                    deviceLabels = sources.associate { it.deviceId to it.deviceLabel },
                )
            }


    override suspend fun setSourceAutoImport(
        kind: DataSourceKind,
        deviceId: String,
        enabled: Boolean,
    ) {
        val raw = when (kind) {
            DataSourceKind.HealthConnect -> DataSourceProjection.KIND_HEALTH_CONNECT
            DataSourceKind.AppleHealth -> DataSourceProjection.KIND_APPLE_HEALTH
            // Fit's own recordings have no registry row; auto-detect is a preference.
            DataSourceKind.TenshuHealth -> return
        }
        runRecorder.setAutoImport(raw, deviceId, enabled)
    }

    /**
     * ⚠ A failed probe leaves the previous verdict standing rather than resetting to the default.
     * The default reads as "there is nothing here to fix", which would close the wizard on the user
     * mid-flow — the one moment they are certainly looking at it.
     */
    override suspend fun refreshSharingReadiness() {
        val readiness = runCatchingCancellable { importer.sharingReadiness() }
            .onFailure { fitLog("refreshSharingReadiness: importer THREW → leaving the previous verdict", it) }
            .getOrNull() ?: return
        if (sharingReadinessState.value != readiness) fitLog("sharingReadiness: $readiness")
        sharingReadinessState.value = readiness
    }

    /**
     * Mirrors the importer's own verdict — see [FitRepository.historyAccess]. Seeded
     * [HistoryAccess.Unsupported] and only ever moved by [refreshHistoryAccess], so a screen that
     * never asks is never told the action is available.
     */
    private val historyAccessState = MutableStateFlow(HistoryAccess.Unsupported)

    override fun historyAccess(): StateFlow<HistoryAccess> = historyAccessState

    override suspend fun refreshHistoryAccess() {
        val access = runCatchingCancellable { importer.historyAccess() }
            .onFailure { fitLog("refreshHistoryAccess: importer THREW → leaving the previous verdict", it) }
            .getOrNull() ?: return
        if (historyAccessState.value != access) fitLog("historyAccess: $access")
        historyAccessState.value = access
    }

    /**
     * Runs a real import over the seam: reads [selection] from the first available source, persists
     * it via the shared [HealthImportWriter] (idempotent — content-addressed record ops), and
     * records what happened in [importStatus] so the app can *say* what happened. Every exit path
     * sets a status, never silence.
     */
    override suspend fun startImport(
        selection: Set<CategoryId>,
        window: ImportWindow,
        automatic: Boolean,
    ) {
        // Two callers legitimately race on a cold start (the proxy's first-run import and, on
        // Android, the shell's post-grant import), and both drive the same status flow and the same
        // write path. Rather than have the loser's stale outcome overwrite the winner's, the second
        // caller simply steps aside: the import already running covers it. ⚠⚠ ADR 0025 §Decision
        // 8.6: no health rows into a store that belongs to no realm. Checked HERE, not only in the
        // bootstrap, for the reason the auto-import switch below is: iOS re-implements the
        // foreground refresh. The engine's single predicate — PENDING as well as ABSENT. ⚠⚠ A pass
        // rejected here is a HELD pass, counted exactly like one stopped mid-run, so the bootstrap
        // re-runs it on the next release (PENDING → PRESENT included). Nothing is lost: Health
        // Connect still has it. ⚠⚠ A manual request waiting on the gate outranks an unattended
        // pass: the launch bootstrap that was suspended behind PENDING resumes on the same release,
        // and running its all-category recent import here would overwrite the user's
        // WaitingForControl — and with it their exact selection and window. So an automatic pass
        // steps aside and leaves the status alone; the request stands until the user retries it.
        // (The recorder-outbox drain is not an import and runs regardless — see the bootstrap.)
        val waiting = importStatusState.value
        if (automatic && waiting is ImportStatus.WaitingForControl) {
            fitLog("startImport: automatic pass skipped — a manual request is waiting (${waiting.selection}, ${waiting.window})")
            return
        }
        if (reach.realmWritesHeld) {
            fitLog("startImport: held at entry — verdict=${reach.realmVerdict.value}, automatic=$automatic")
            holdImport(selection, window, automatic, ownsRun = false)
            return
        }
        if (!importLock.tryLock()) {
            fitLog("startImport: an import is already in flight — skipping this one")
            return
        }
        try {
            // The auto-import switch (D4). Checked HERE rather than at the three unattended call
            // sites, because two of them are iOS re-implementations of the foreground refresh that
            // a gate added to `HealthImportBootstrap` would silently miss. ⚠ A manual import is
            // never gated — switching off automatic passes is not the same as switching off the
            // source, and the detail screen offers both.
            if (automatic && !autoImportAllowed()) {
                fitLog("startImport: automatic passes are switched off for this source — skipping")
                return
            }
            try {
                // ⚠ A manual import is user-started (`control-background-work-off-fgs.md` §5.7), so
                // Control may hold its foreground service for it; an automatic pass never does.
                if (automatic) runImport(selection, window, automatic)
                else userInitiatedWork { runImport(selection, window, automatic) }
            } catch (e: RealmWritesHeldException) {
                // ⚠⚠ The ONE place a gated import's refusal is handled (ADR 0025 §8.6): whichever
                // write the boundary refused — source registration, a batch, supersession, the run
                // record, the demographic seed — the pass is held.
                fitLog("startImport: held by the realm gate mid-pass (verdict=${reach.realmVerdict.value})")
                holdImport(selection, window, automatic, ownsRun = true)
            }
        } finally {
            importLock.unlock()
        }
    }

    /**
     * Whether an *unattended* pass may run against whatever source this device has.
     *
     * ⚠ Fails open on every uncertainty — no source resolved yet, no row written yet, a read that
     * threw. A switch the user has never touched must not stop their data arriving, and a device
     * that quietly stopped importing with no explanation is a worse bug than one that imported
     * once too often.
     */
    private suspend fun autoImportAllowed(): Boolean {
        val source = runCatching { importer.availableSources().firstOrNull() }.getOrNull()
            ?: return true
        val kind = HealthImportRunRecorder.kindOf(source) ?: return true
        return runRecorder.autoImportEnabled(kind)
    }

    /**
     * `core-background-processing.md` §10.6: run [block] — an import's
     * whole write phase — as ONE registered bulk task, so every record write
     * inside joins the task's sync lane and rides the relay as
     * `DeltaBatchPayload` blocks (~N/500) instead of one `SyncBlock` per
     * record. [block] receives a heartbeat to call per record; it renews the
     * task's D9 lease every [HEARTBEAT_EVERY_RECORDS] records (constituent
     * calls don't renew).
     *
     * **Graceful degradation:** if the TaskService is unavailable (a Control
     * without it, or a runtime without the sync client module), [block] runs
     * bare — byte-identical per-save sync. Mixed-version pairings keep working.
     *
     * The lifecycle itself lives in the shared [withBulkWriteTask] (C4);
     * what stays here is the only part that is Fit's own — the record-counted
     * heartbeat, since a Fit record is small and steady, unlike Drive's
     * per-file unit.
     */
    private suspend fun <T> withImportTask(block: suspend (heartbeat: suspend () -> Unit) -> T): T =
        client.withBulkWriteTask(FIT_IMPORT_TASK_KIND) { task ->
            if (task == null) {
                fitLog("import: TaskService unavailable — per-save sync (no task batching)")
                return@withBulkWriteTask block {}
            }
            var sinceHeartbeat = 0
            block {
                if (++sinceHeartbeat >= HEARTBEAT_EVERY_RECORDS) {
                    sinceHeartbeat = 0
                    bestEffort("FitProjection -> renewing the import task lease") { task.renewLease() }
                }
            }
        }

    private suspend fun runImport(selection: Set<CategoryId>, window: ImportWindow, automatic: Boolean) {
        // ⚠⚠ Every status this pass publishes goes through here: an
        // AUTOMATIC pass never overwrites a WaitingForControl. A manual request stored while this
        // pass ran is the user's exact selection and window, and an unattended pass's Running,
        // outcome or failure must not erase it — the request stands until the user retries it.
        fun publish(next: ImportStatus) {
            if (automatic) importStatusState.update { if (it is ImportStatus.WaitingForControl) it else next }
            else importStatusState.value = next
        }
        val source = importer.availableSources().firstOrNull()
        if (source == null) {
            fitLog("startImport: no source available from ${importer::class.simpleName} — nothing to do")
            publish(ImportStatus.SourceUnavailable)
            return
        }
        fitLog("startImport: source=$source via ${importer::class.simpleName}")

        // From here on every exit is also a *recorded* outcome, not just a displayed one.
        // The kind is null for a source that is not a standing connection (D11) — there is
        // nothing to register and nothing to record against.
        val kind = HealthImportRunRecorder.kindOf(source)
        val runWindow = HealthImportRunRecorder.windowOf(window)
        val startedAt = clock.now().toEpochMilliseconds()
        // Registering here rather than at construction: this is the moment the platform has
        // actually told us the store exists, and it is idempotent, so re-running costs a no-op
        // upsert that preserves whatever the user chose.
        if (kind != null) gatedRunRecorder.registerSource(kind)

        suspend fun record(
            status: String,
            itemCount: Long = 0L,
            workoutCount: Long = 0L,
            error: String? = null,
        ) {
            if (kind == null) return
            gatedRunRecorder.record(
                kind = kind,
                window = runWindow,
                startedAt = startedAt,
                finishedAt = clock.now().toEpochMilliseconds(),
                status = status,
                itemCount = itemCount,
                workoutCount = workoutCount,
                error = error,
            )
        }

        // Health Connect reads then writes in bulk (no per-record progress), so a bare Running(0).
        publish(ImportStatus.Running())

        val outcome = try {
            importer.import(source, selection, window)
        } catch (e: CancellationException) {
            // Not a failure: the caller's scope went away (a rotation, a cleared ViewModel). Reporting
            // it as Failed would put a red "Import failed" banner on a perfectly healthy app — and on
            // this repository, which is a DI singleton, that lie would outlive the screen that caused it.
            publish(ImportStatus.Idle)
            throw e
        } catch (e: Throwable) {
            fitLog("startImport: importer THREW", e)
            publish(ImportStatus.Failed(e.message ?: e::class.simpleName.orEmpty()))
            record(HealthImportRun.STATUS_FAILED, error = e.errorCode())
            throw e
        }

        val health = when (outcome) {
            is ImportOutcome.Success -> outcome.health
            is ImportOutcome.Sample -> {
                // ⚠⚠ THE store guard, for every platform (fit-app.md §9.15). A fixture is not the
                // user's health data, and this repository writes into Control's durable, SYNCED
                // realm — persisting it pushes fabricated workouts to every paired device. A manual
                // import (onboarding, Profile, Home's retry) is deliberately not gated on
                // `autoRefreshReady`, so this refusal is what keeps an importer that falls back to
                // the fixture from writing it.
                // Nothing is written and nothing is recorded: to the user this is no source at all.
                fitLog("startImport: ${importer::class.simpleName} answered SAMPLE data — REFUSED, nothing written")
                publish(ImportStatus.SourceUnavailable)
                return
            }
            is ImportOutcome.PermissionsRequired -> {
                publish(ImportStatus.PermissionsMissing(source))
                record(HealthImportRun.STATUS_PERMISSIONS_MISSING)
                return
            }
            ImportOutcome.SourceUnavailable -> {
                publish(ImportStatus.SourceUnavailable)
                return
            }
        }

        val added = try {
            withImportTask { heartbeat ->
                // ⚠⚠ ADR 0025 §8.6: the gated writer refuses each batch while the gate holds; what
                // was not written stays in Health Connect, and `startImport` counts the held pass.
                gatedHealthWriter().write(health) { heartbeat() }
            }
        } catch (e: RealmWritesHeldException) {
            throw e // not a failure: no Failed status, no failure record (that would be a write too)
        } catch (e: CancellationException) {
            publish(ImportStatus.Idle)
            throw e
        } catch (e: Throwable) {
            publish(ImportStatus.Failed(e.message ?: e::class.simpleName.orEmpty()))
            record(HealthImportRun.STATUS_FAILED, error = e.errorCode())
            throw e
        }

        // D3: the imported rows have landed, so any auto-detected inference covering the
        // same window is now a duplicate of real device data. ⚠ **After** the write, not before:
        // running it first would delete detected workouts on an import that then fails, losing
        // them for nothing. Best-effort — a failure here leaves a duplicate, which the next
        // import retries away; it must never fail the import that already succeeded.
        // ⚠ Supersession deletes and the run record write too: both go through the boundary (the
        // gated deletes below, [gatedRunRecorder]), and a refusal propagates to `startImport`.
        try {
            supersedeDetected()
        } catch (e: RealmWritesHeldException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fitLog("supersede: failed", e)
        }

        importProgressState.value = selection.map { ImportProgress(it, health.count, health.count) }
        // The importer's notice rides both completed verdicts: "nothing at all" and "some rows, but
        // not the ones you came for" are the same question from the user's side, and the answer to
        // both is the same export.
        val notice = (outcome as? ImportOutcome.Success)?.notice
        publish(
            when {
                health.isEmpty -> ImportStatus.NoData(source, notice)
                else -> ImportStatus.Imported(source, health.count, notice)
            },
        )
        // ⚠⚠ `added`, not `health.count`. The read count is the size of the source's
        // window and is identical on every refresh — recording it would fill a device's
        // history with identical entries and stop D7's "a no-op refresh writes nothing" rule
        // from firing, since a re-read of 164 unchanged rows still counts 164.
        record(
            status = if (added == 0) HealthImportRun.STATUS_NO_DATA
            else HealthImportRun.STATUS_SUCCEEDED,
            itemCount = added.toLong(),
            // ⚠ Still the read figure: the proxy reports one total, not a per-kind
            // breakdown, so this is "workouts in this read" rather than "new workouts".
            // Left as the honest approximation rather than silently derived from `added`.
            workoutCount = health.workouts.size.toLong(),
        )
        seedOwnerDemographics()
        fitLog("startImport: COMPLETE — status=${importStatusState.value}")
    }

    override suspend fun completeOnboarding() = Unit

    /**
     * Fill the owner's date of birth and sex from the platform, if it knows them and the user has
     * not already said (`fit-app.md` §18.12).
     *
     * Runs **after** an import, because the grant that makes the read possible is the one the
     * import just obtained — and because the About-you step is evaluated after the import too, so
     * a seeded value is in place before the step decides whether it has anything left to ask.
     *
     * ⚠ **Only ever a seed.** The one-shot marker lives in `OwnerPreferences.DemographicsSeeded`
     * and the per-field guard is inside `seedDemographics`; between them, a platform answer can
     * never overwrite a user's — including a user who deliberately cleared a value back to unset.
     *
     * A platform that knows **nothing** does not burn the one-shot (the early return below), so a
     * grant obtained later can still seed. A platform that knows **part** of the answer does burn
     * it — the rest is then the user's to give, which is the right default once they have already
     * been shown the About-you step.
     *
     * ⚠ Deliberately silent on failure: an importer that cannot read characteristics (every one
     * but HealthKit's) or a user who declined that specific grant both mean "we do not know",
     * which is a state the profile already renders honestly. Nothing here is worth a banner.
     */
    private suspend fun seedOwnerDemographics() {
        try {
            val characteristics = importer.readCharacteristics()
            if (characteristics.birthDate == null && characteristics.sex == null) return
            // ⚠⚠ Through the write boundary (ADR 0025 §8.6): DOB, sex and
            // the one-shot marker are synced preference writes, and reading the characteristics can
            // take long enough for the realm gate to start holding. A refusal propagates to
            // `startImport`, which counts the held pass for the re-run.
            ownerProfile.seedDemographics(
                characteristics.birthDate,
                characteristics.sex,
                beforeEachWrite = { reach.refuseIfRealmWritesHeld() },
            )
        } catch (e: RealmWritesHeldException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fitLog("seedOwnerDemographics: skipped — ${e.message}")
        }
    }

    // ── Profile mutations ──────────────────────────────────────────────────────────────────

    override suspend fun updateGoals(goals: Goals) {
        goalPreferences.set(goals)
    }

    override suspend fun updateUnits(units: Units) {
        ownerProfile.setUnits(units.toMeasurementSystem())
    }

    override suspend fun updateOwnerName(name: String) {
        ownerProfile.setName(name)
    }

    override suspend fun updateBirthDate(date: LocalDate?) {
        ownerProfile.setBirthDate(date)
    }

    override suspend fun updateSex(sex: BiologicalSex?) {
        ownerProfile.setSex(sex)
    }

    override suspend fun recordHeight(centimetres: Int) {
        recordBodySample(metric = "Height", value = centimetres.toString(), unit = "cm")
    }

    override suspend fun recordWeight(kilograms: Double) {
        // One decimal, matching how every importer stores a weight (and how Body renders it) —
        // a scale reports 74.2, not 74.19999.
        recordBodySample(metric = "Weight", value = ((kilograms * 10).roundToInt() / 10.0).toString(), unit = "kg")
    }

    /**
     * The cross-service read: Timeline's `MovementTrace` rows, newest first.
     *
     * ⚠ `TRACE_FIELDS` omits `POLYLINE` on purpose. It is a plain eager field, not a deferred or
     * on-demand property, so the *only* way not to load the blob is to not name it — exclusion is
     * by omission, and a field added to this list is a path decoded per candidate.
     *
     * ⚠ There is no search op on `traces`, so this is a full-relation scan with a projection and
     * a client-side sort — acceptable for a retrospective pass with nothing waiting on it, and
     * the reason promotion runs at open/refresh rather than per row.
     */
    override fun detectedMovements(): Flow<List<DetectedMovement>> =
        client.timelineService.traces
            .select { fields(*TRACE_FIELDS) }
            .flow()
            .map { r ->
                r.foldTracked(emptyList<DetectedMovement>()) { rows ->
                    rows.toList()
                        .map(::detectedMovement)
                        .sortedByDescending { it.startAtEpochMs }
                }
            }
            .retrying("traces")

    private fun detectedMovement(e: Entity<out SvcTrace>) = DetectedMovement(
        traceId = e.getFieldValue<TString>(SvcTrace.Fields.TRACE_ID).get(),
        mode = e.getFieldValue<TString>(SvcTrace.Fields.MODE).get(),
        modeConfidence = e.getFieldValue<TInteger>(SvcTrace.Fields.MODE_CONFIDENCE).get().toInt(),
        distanceM = e.getFieldValue<TInteger>(SvcTrace.Fields.DISTANCE_M).get().toInt(),
        startAtEpochMs = e.getFieldValue<UnixInstant>(SvcTrace.Fields.START_AT).get(),
        endAtEpochMs = e.getFieldValue<UnixInstant>(SvcTrace.Fields.END_AT).get(),
        classifierVersion = e.getFieldValue<TInteger>(SvcTrace.Fields.CLASSIFIER_VERSION).get().toInt(),
        // `-1` = not counted (`MovementTrace.steps`); anything negative is unknown, never zero.
        steps = e.getFieldValue<TInteger>(SvcTrace.Fields.STEPS).get().takeIf { it >= 0 }?.toInt(),
    )

    /**
     * Tier-1 detections: walks and runs read out of the platform's step data (§9.2).
     *
     * ⚠ Delegated to the importer because the read is platform-specific while the bout rules are
     * shared — the split §9.2 asks for.
     */
    override suspend fun detectedStepBouts(): List<DetectedMovement> =
        runCatchingCancellable { importer.readStepBouts().map { it.toDetectedMovement() } }
            .onFailure { fitLog("step bouts: read failed", it) }
            .getOrDefault(emptyList())

    /**
     * The delete counterpart of [addWorkout] — and deliberately **not** shaped like it.
     * [addWorkout] routes through [HealthImportWriter] + the durable outbox because a write has a
     * batched upsert op behind it and a journal to replay from. A delete has neither: it is one
     * keyed op, and if the proxy is unreachable the honest answer is `false` (the caller keeps
     * the row on screen) rather than a queued deletion the user believes already happened.
     */
    override suspend fun deleteWorkout(id: String): Boolean =
        runCatchingCancellable { client.healthService.deleteWorkout(id).orThrow("delete workout") }
            .onFailure { reach.markUnreachable() }
            .isSuccess

    /**
     * Deletes the auto-detected workouts a real one has made redundant (D3).
     *
     * ⚠ Reads the **store** rather than the incoming rows, and runs after the write, so the
     * imported workouts are already there. That is what lets the promotion path reuse the same
     * rule — either side can arrive second.
     *
     * ⚠ Lossless: a detected workout stores no route (its geometry lives on Timeline's
     * `MovementTrace`, which this never touches), so removing it drops an inference, not data.
     */
    private suspend fun supersedeDetected() {
        // The gated pipeline's deletes go through the boundary, one check per delete (§8.6).
        val removed = supersedeDetectedWorkouts(zone, beforeEachDelete = { reach.refuseIfRealmWritesHeld() })
        if (removed > 0) fitLog("supersede: $removed detected workout(s) replaced by real ones")
    }

    /**
     * A hand-entered body measurement, written as the *same* `HealthSample` row Health Connect
     * writes — never a preference (§18.2). That is what keeps Profile and Browse → Body from
     * diverging: there is one store for a body measurement, and this is a second writer to it,
     * not a second copy of it.
     *
     * ⚠ Stamped `Source.Manual` and at `now`. The row is keyed `"$metric::$timeMs"`, so two
     * entries in the same millisecond fold onto one row and anything later simply becomes the
     * newest reading — a correction is a new measurement, never an edit of the old one.
     */
    private suspend fun recordBodySample(metric: String, value: String, unit: String) {
        val sample = SvcHealthSample.create(
            context = context,
            metric = metric,
            value = value,
            unit = unit,
            time = UnixInstantDto(Clock.System.now().toEpochMilliseconds()),
            source = FitProjection.sourceName(Source.Manual),
        )
        runCatchingCancellable { client.healthService.recordHealthSample(sample).orThrow("record $metric") }
            .onFailure { reach.markUnreachable() }
    }

    // ── read plumbing: health-service Workout entities → lightweight inputs ────────────────

    // Each store source is read through ONE cached, shared stream (see [shared]); the
    // `build*` functions below construct the underlying chain.
    private val recentWorkoutInputsShared by lazy { shared(::buildRecentWorkoutInputs) }
    private val aggregateWorkoutInputsShared by lazy { shared(::buildAggregateWorkoutInputs) }
    private val dailyActivityInputsShared by lazy { shared(::buildDailyActivityInputs) }
    private val sleepInputsShared by lazy { shared(::buildSleepInputs) }
    private val latestSampleInputsShared by lazy { shared(::buildLatestSampleInputs) }
    private val medicalRecordInputsShared by lazy { shared(::buildMedicalRecordInputs) }
    private val dailyNutritionInputsShared by lazy { shared(::buildDailyNutritionInputs) }
    private val menstruationDayInputsShared by lazy { shared(::buildMenstruationDayInputs) }

    private fun recentWorkoutInputs(): Flow<ListLoadState<WorkoutInput>> = recentWorkoutInputsShared
    private fun aggregateWorkoutInputs(): Flow<ListLoadState<WorkoutInput>> = aggregateWorkoutInputsShared
    private fun dailyActivityInputs(): Flow<ListLoadState<DailyActivityInput>> = dailyActivityInputsShared
    private fun sleepInputs(): Flow<ListLoadState<SleepSessionInput>> = sleepInputsShared
    private fun latestSampleInputs(): Flow<ListLoadState<SampleInput>> = latestSampleInputsShared
    private fun medicalRecordInputs(): Flow<ListLoadState<MedicalRecordInput>> = medicalRecordInputsShared
    private fun dailyNutritionInputs(): Flow<ListLoadState<DailyNutritionInput>> = dailyNutritionInputsShared
    private fun menstruationDayInputs(): Flow<ListLoadState<MenstruationDayInput>> = menstruationDayInputsShared

    /**
     * The most recent [WORKOUT_WINDOW] workouts, newest-first, as lightweight inputs.
     * `sortByDescending { start }` + `take` + `select` is the ordered-window-fusion shape
     * (core-ordered-window-fusion.md): the engine executes it as ONE projected pass + bounded top-N
     * — the sort never materialises full rows or interprets the selector per element. This is the
     * read that must not scale with total history — [workouts] and [aggregates] both go through it.
     */
    private fun buildRecentWorkoutInputs(limit: Int = WORKOUT_WINDOW): Flow<ListLoadState<WorkoutInput>> =
        client.healthService.workouts
            // Lambda field-selector (`{ start }`), NOT the `sortByDescending(Workout.Start)`
            // property overload: `Workout.Start` aliases the START **schema field**, and the
            // wire reference-weight system makes a field reference win over a value-property
            // one — so the property selector (a StaticPropertyRelation) can't round-trip and
            // the whole stream dies with `tenshu/core/error/internal` on the EPC hop. The
            // lambda ships a plain field read that round-trips fine (the
            // `notifications.sortByDescending { timestamp }` pattern). See
            // WorkoutSortWireTest + fit-app.md §9.17.
            .sortByDescending { start }
            .take(limit)
            .select { fields(*WORKOUT_FIELDS) }.flow()
            .map { r -> r.loadTracked { it.toList().map(::workoutInput) } }
            .retrying("workouts")

    /**
     * The workouts [aggregates] needs — every one inside the widest chart range, **bounded by
     * date rather than by count**.
     *
     * ⚠⚠ **A second read over the same relation, deliberately.** [buildRecentWorkoutInputs] bounds
     * on the wrong axis for this consumer. `FitProjection.dailyAggregates` merges workout-derived
     * values into the stored
     * `DailyActivity` rows *per field* — notably `activeMinutes`, which Health Connect always
     * imports as 0 — so under a count bound a user who trained more than [WORKOUT_WINDOW] times
     * in a year would have the oldest days of the Year chart silently lose their corrections: the
     * ring and the trend fall back to a stored 0 on days they had actually trained.
     *
     * ⚠ The two bounds cannot be one read, which is why the duplication is accepted rather than
     * factored away. History wants *the most recent N whatever their age* (a three-year-old
     * workout must still appear when it is among the newest); this wants *everything inside the
     * last `ChartRange.Year` however many that is*. A date bound truncates History's reach to a
     * year; a count bound under-covers this chart. `max(date, count)` is not expressible as one
     * chain. ⚠ Merging them truncates one consumer — tests whose fixtures are dated 2023 pin the
     * History half, which is what a real user with an old import would otherwise see.
     *
     * ⚠ Does not fuse (a filter ahead of the sort disarms the peephole —
     * `core-ordered-window-fusion.md` gate 2), the same trade [sampleInputsFor] takes.
     */
    private fun buildAggregateWorkoutInputs(): Flow<ListLoadState<WorkoutInput>> =
        client.healthService.workouts
            .searchAll<SvcWorkout> { start.inRange(coverageFromMs, COVERAGE_END_MS) }
            .sortByDescending { start }
            // A ceiling against pathological data, NOT the coverage bound — ~3 workouts every day
            // for a year. Deliberately far above any real training load rather than tuned close to
            // one, because exceeding it re-opens the under-coverage above.
            .take(AGGREGATE_WORKOUT_CAP)
            .select { fields(*WORKOUT_FIELDS) }.flow()
            .map { r -> r.loadTracked { it.toList().map(::workoutInput) } }
            .retrying("workouts[aggregates]")

    /**
     * Start of the covered window, epoch millis — [today] minus the widest chart range.
     *
     * ⚠ Evaluated per read, like [today] itself: a construction-time value would freeze the far
     * edge of the window to the process's start date. Two reads a second apart still agree; two
     * reads a *day* apart correctly do not.
     */
    private val coverageFromMs: Long
        get() = today.minus(DatePeriod(days = ChartRange.Year.days))
            .atStartOfDayIn(zone)
            .toEpochMilliseconds()

    private fun buildDailyActivityInputs(): Flow<ListLoadState<DailyActivityInput>> =
        client.healthService.dailyActivities.select { fields(*DAILY_ACTIVITY_FIELDS) }.flow()
            .map { r -> r.loadTracked { it.toList().map(::dailyActivityInput) } }
            .retrying("dailyActivities")

    // Windowed to the most recent [SLEEP_WINDOW] nights (fit-read-scaling.md): the ISO
    // `date` string sorts chronologically, and sort+take+select is the ordered-window-fusion
    // shape (core-ordered-window-fusion.md) — one projected pass, not a full-store sort.
    private fun buildSleepInputs(): Flow<ListLoadState<SleepSessionInput>> =
        client.healthService.sleepSessions
            .sortByDescending { date }
            .take(SLEEP_WINDOW)
            .select { fields(*SLEEP_SESSION_FIELDS) }.flow()
            .map { r -> r.loadTracked { it.toList().map(::sleepSessionInput) } }
            .retrying("sleepSessions")

    /**
     * The retained readings for **one metric**, newest first, windowed to
     * [SAMPLE_HISTORY_WINDOW] (`fit-read-scaling.md`).
     *
     * Bounded per metric: a ten-year import is tens of thousands of samples, and an unbounded
     * read ships all of them over IPC and projects them to open one Body/Vitals category detail.
     *
     * ⚠ **The filter has to be server-side, and it cannot be a plain `take`.** The obvious
     * bound — `sortByDescending { time }.take(n)` like [buildRecentWorkoutInputs] — is wrong
     * here: the relation holds every metric, so a global top-N would silently drop a metric
     * whose readings are older than the newest N of some other metric. Weight measured monthly
     * would vanish behind daily heart rate. The predicate is what makes the window per-metric
     * and therefore correct.
     *
     * ⚠ **This chain deliberately does NOT fuse**, unlike the workout and sleep windows.
     * `core-ordered-window-fusion.md` §gate 2 is explicit: a filter ahead of the sort disarms
     * the peephole ("no filter pushdown … same for any filtered sort"), so the proxy
     * materialises this metric's rows and sorts them rather than streaming a bounded top-N.
     * That is accepted knowingly — the cost that mattered was the **ship and the projection**
     * (tens of thousands of rows over IPC, then mapped and re-sorted on the client), and both
     * are bounded by the window. A filter-aware fused path is that document's own "separate,
     * later peephole if ever needed".
     *
     * ⚠ Not shared through [shared], unlike the other reads: sharing is keyed by nothing here —
     * there would be one stream per metric — and a mutable per-metric cache would need locking
     * for a read that one screen opens at a time. Re-entering the screen re-reads, which is
     * cheap precisely because the read is bounded.
     */
    private fun sampleInputsFor(wanted: Metric): Flow<ListLoadState<SampleInput>> {
        // Bound outside the predicate: inside it, `metric` is the *entity's* field, so a parameter
        // of the same name would shadow one of the two things being compared.
        val wantedName = FitProjection.metricName(wanted)
        return client.healthService.healthSamples
            .searchAll<SvcHealthSample> { metric equalTo wantedName }
            // Lambda field-selector, not the property overload — see [buildRecentWorkoutInputs]
            // for why the latter cannot round-trip.
            .sortByDescending { time }
            .take(SAMPLE_HISTORY_WINDOW)
            .select { fields(*HEALTH_SAMPLE_FIELDS) }.flow()
            .map { r -> r.loadTracked { it.toList().map(::sampleInput) } }
            .retrying("healthSamples[$wantedName]")
    }

    private fun buildLatestSampleInputs(): Flow<ListLoadState<SampleInput>> =
        client.healthService.latestSamples.select { fields(*LATEST_SAMPLE_FIELDS) }.flow()
            .map { r -> r.loadTracked { it.toList().map(::latestSampleInput) } }
            .retrying("latestSamples")

    private fun latestSampleInput(e: Entity<out SvcLatestSample>) = SampleInput(
        metric = e.getFieldValue<TString>(SvcLatestSample.Fields.METRIC).get(),
        value = e.getFieldValue<TString>(SvcLatestSample.Fields.VALUE).get().toDoubleOrNull() ?: 0.0,
        unit = e.getFieldValue<TString>(SvcLatestSample.Fields.UNIT).get(),
        timeMs = e.getFieldValue<UnixInstant>(SvcLatestSample.Fields.TIME).toEpochMilliseconds(),
        source = e.getFieldValue<TString>(SvcLatestSample.Fields.SOURCE).get(),
        resting = e.getFieldValue<TBoolean>(SvcLatestSample.Fields.RESTING).get(),
    )

    private fun sampleInput(e: Entity<out SvcHealthSample>) = SampleInput(
        metric = e.getFieldValue<TString>(SvcHealthSample.Fields.METRIC).get(),
        value = e.getFieldValue<TString>(SvcHealthSample.Fields.VALUE).get().toDoubleOrNull() ?: 0.0,
        unit = e.getFieldValue<TString>(SvcHealthSample.Fields.UNIT).get(),
        timeMs = e.getFieldValue<UnixInstant>(SvcHealthSample.Fields.TIME).toEpochMilliseconds(),
        source = e.getFieldValue<TString>(SvcHealthSample.Fields.SOURCE).get(),
        resting = e.getFieldValue<TBoolean>(SvcHealthSample.Fields.RESTING).get(),
    )

    private fun buildMedicalRecordInputs(): Flow<ListLoadState<MedicalRecordInput>> =
        client.healthService.medicalRecords.select { fields(*MEDICAL_RECORD_FIELDS) }.flow()
            .map { r -> r.loadTracked { it.toList().map(::medicalRecordInput) } }
            .retrying("medicalRecords")

    private fun medicalRecordInput(e: Entity<out SvcMedicalRecord>) = MedicalRecordInput(
        recordId = e.getFieldValue<TString>(SvcMedicalRecord.Fields.RECORD_ID).get(),
        kind = e.getFieldValue<TString>(SvcMedicalRecord.Fields.KIND).get(),
        title = e.getFieldValue<TString>(SvcMedicalRecord.Fields.TITLE).get(),
        detail = e.getFieldValue<TString>(SvcMedicalRecord.Fields.DETAIL).get(),
        effectiveDate = LocalDate.parse(e.getFieldValue<TString>(SvcMedicalRecord.Fields.EFFECTIVE_DATE).get()),
        issuer = e.getFieldValue<TString>(SvcMedicalRecord.Fields.ISSUER).get(),
        source = e.getFieldValue<TString>(SvcMedicalRecord.Fields.SOURCE).get(),
    )

    private fun sleepSessionInput(e: Entity<out SvcSleepSession>) = SleepSessionInput(
        date = LocalDate.parse(e.getFieldValue<TString>(SvcSleepSession.Fields.DATE).get()),
        startMs = e.getFieldValue<UnixInstant>(SvcSleepSession.Fields.START).toEpochMilliseconds(),
        endMs = e.getFieldValue<UnixInstant>(SvcSleepSession.Fields.END).toEpochMilliseconds(),
        durationSec = e.getFieldValue<TInteger>(SvcSleepSession.Fields.DURATION_SEC).get().toInt(),
        source = e.getFieldValue<TString>(SvcSleepSession.Fields.SOURCE).get(),
        awakeMin = e.getFieldValue<TInteger>(SvcSleepSession.Fields.AWAKE_MIN).get().toInt(),
        remMin = e.getFieldValue<TInteger>(SvcSleepSession.Fields.REM_MIN).get().toInt(),
        lightMin = e.getFieldValue<TInteger>(SvcSleepSession.Fields.LIGHT_MIN).get().toInt(),
        deepMin = e.getFieldValue<TInteger>(SvcSleepSession.Fields.DEEP_MIN).get().toInt(),
    )

    private fun dailyActivityInput(e: Entity<out SvcDailyActivity>) = DailyActivityInput(
        date = LocalDate.parse(e.getFieldValue<TString>(SvcDailyActivity.Fields.DATE).get()),
        steps = e.getFieldValue<TInteger>(SvcDailyActivity.Fields.STEPS).get().toInt(),
        distanceM = e.getFieldValue<TInteger>(SvcDailyActivity.Fields.DISTANCE_M).get().toInt(),
        activeEnergyKj = e.getFieldValue<TInteger>(SvcDailyActivity.Fields.ACTIVE_ENERGY_KJ).get().toInt(),
        activeMinutes = e.getFieldValue<TInteger>(SvcDailyActivity.Fields.ACTIVE_MINUTES).get().toInt(),
    )

    private fun buildDailyNutritionInputs(): Flow<ListLoadState<DailyNutritionInput>> =
        client.healthService.dailyNutritions.select { fields(*DAILY_NUTRITION_FIELDS) }.flow()
            .map { r -> r.loadTracked { it.toList().map(::dailyNutritionInput) } }
            .retrying("dailyNutritions")

    private fun dailyNutritionInput(e: Entity<out SvcDailyNutrition>) = DailyNutritionInput(
        date = LocalDate.parse(e.getFieldValue<TString>(SvcDailyNutrition.Fields.DATE).get()),
        energyKj = e.getFieldValue<TInteger>(SvcDailyNutrition.Fields.ENERGY_KJ).get().toInt(),
        hydrationMl = e.getFieldValue<TInteger>(SvcDailyNutrition.Fields.HYDRATION_ML).get().toInt(),
    )

    private fun buildMenstruationDayInputs(): Flow<ListLoadState<MenstruationDayInput>> =
        client.healthService.menstruationDays.select { fields(*MENSTRUATION_DAY_FIELDS) }.flow()
            .map { r -> r.loadTracked { it.toList().map(::menstruationDayInput) } }
            .retrying("menstruationDays")

    private fun menstruationDayInput(e: Entity<out SvcMenstruationDay>) = MenstruationDayInput(
        date = LocalDate.parse(e.getFieldValue<TString>(SvcMenstruationDay.Fields.DATE).get()),
        flow = e.getFieldValue<TString>(SvcMenstruationDay.Fields.FLOW).get(),
        periodStart = e.getFieldValue<TBoolean>(SvcMenstruationDay.Fields.PERIOD_START).get(),
    )

    private fun workoutInput(e: Entity<out SvcWorkout>) = WorkoutInput(
        workoutId = e.getFieldValue<TString>(SvcWorkout.Fields.WORKOUT_ID).get(),
        activityType = e.getFieldValue<TString>(SvcWorkout.Fields.ACTIVITY_TYPE).get(),
        source = e.getFieldValue<TString>(SvcWorkout.Fields.SOURCE).get(),
        startMs = e.getFieldValue<UnixInstant>(SvcWorkout.Fields.START).toEpochMilliseconds(),
        endMs = e.getFieldValue<UnixInstant>(SvcWorkout.Fields.END).toEpochMilliseconds(),
        durationSec = e.getFieldValue<TInteger>(SvcWorkout.Fields.DURATION_SEC).get(),
        title = e.getFieldValue<TString>(SvcWorkout.Fields.TITLE).get(),
        distanceM = e.getFieldValue<TInteger>(SvcWorkout.Fields.DISTANCE_M).get(),
        activeEnergyKj = e.getFieldValue<TInteger>(SvcWorkout.Fields.ACTIVE_ENERGY_KJ).get(),
        avgHr = e.getFieldValue<TInteger>(SvcWorkout.Fields.AVG_HR).get(),
        steps = e.getFieldValue<TInteger>(SvcWorkout.Fields.STEPS).get(),
    )

    private fun LocalDateTime.epochMillis(): Long = toInstant(zone).toEpochMilliseconds()

    // ⚠ `internal`, not `private`: the Timeline bridge test asserts `POLYLINE` stays out of
    // [TRACE_FIELDS]. That guard has to read the list, and the list is the only thing keeping
    // the blob off a per-candidate read (a plain eager field is excluded by omission alone).
    internal companion object {
        /**
         * Recent-workout read cap (fit-read-scaling.md). ⚠ Not History's bound — the feed
         * pages ([workoutsPaged]); this bounds the *list* read Home, Profile and the week roll-up
         * share.
         */
        const val WORKOUT_WINDOW = 300

        /** Rows per History page. Sized like Drive's listing; a day card is taller than a file row. */
        const val WORKOUT_PAGE_SIZE = 20

        /**
         * Safety ceiling on the *aggregate* workout read — **not** its coverage bound, which is a
         * date range (see [buildAggregateWorkoutInputs]). ~3 workouts every day for a year.
         */
        const val AGGREGATE_WORKOUT_CAP = 1_000

        /**
         * Upper bound of the covered window. Far future rather than "now": a workout may carry a
         * start slightly ahead of the device clock (an import from a device in another timezone,
         * a clock that has since been corrected), and excluding it would drop the user's most
         * recent session from the ring on exactly the days that are hardest to explain.
         */
        const val COVERAGE_END_MS = 4_102_444_800_000L // 2100-01-01

        /** Sleep read cap (fit-read-scaling.md): a year of nights covers every chart range. */
        const val SLEEP_WINDOW = 366

        /**
         * Readings kept for one metric's category-detail history + trend line
         * (`fit-read-scaling.md`). Sized like [SLEEP_WINDOW]: a year of daily readings, which is
         * what the trend polyline can usefully render and more than the list is scrolled through.
         *
         * ⚠ It bounds the **trend** as well as the list — both are derived from this one series
         * in `CategoryDetailViewModel`. A metric sampled more often than daily therefore trends
         * over a shorter span than a year. That is the deliberate trade for a bounded read.
         */
        const val SAMPLE_HISTORY_WINDOW = 366

        /**
         * How long a shared read stream survives its last collector — long enough
         * to cover a tab switch, short enough that a backgrounded app drops its streams.
         */
        const val SHARE_STOP_TIMEOUT_MS = 5_000L


        val WORKOUT_FIELDS = arrayOf(
            SvcWorkout.Fields.WORKOUT_ID, SvcWorkout.Fields.ACTIVITY_TYPE, SvcWorkout.Fields.SOURCE,
            SvcWorkout.Fields.START, SvcWorkout.Fields.END, SvcWorkout.Fields.DURATION_SEC,
            SvcWorkout.Fields.TITLE, SvcWorkout.Fields.DISTANCE_M, SvcWorkout.Fields.ACTIVE_ENERGY_KJ,
            SvcWorkout.Fields.AVG_HR, SvcWorkout.Fields.STEPS,
        )
        /**
         * ⚠ `POLYLINE` is deliberately absent and must stay absent: it is a plain eager
         * field, so omission is the *only* way not to load the blob, and a promoted workout never
         * needs it (`workoutId` embeds the `traceId`, so the route is fetchable on demand).
         */
        val TRACE_FIELDS = arrayOf(
            SvcTrace.Fields.TRACE_ID, SvcTrace.Fields.MODE, SvcTrace.Fields.MODE_CONFIDENCE,
            SvcTrace.Fields.DISTANCE_M, SvcTrace.Fields.START_AT, SvcTrace.Fields.END_AT,
            SvcTrace.Fields.CLASSIFIER_VERSION, SvcTrace.Fields.STEPS,
        )
        val DAILY_ACTIVITY_FIELDS = arrayOf(
            SvcDailyActivity.Fields.DATE, SvcDailyActivity.Fields.STEPS, SvcDailyActivity.Fields.DISTANCE_M,
            SvcDailyActivity.Fields.ACTIVE_ENERGY_KJ, SvcDailyActivity.Fields.ACTIVE_MINUTES,
        )
        // DATE is the content-address key (§12 B2 gotcha) — project it or the read drops rows.
        val DAILY_NUTRITION_FIELDS = arrayOf(
            SvcDailyNutrition.Fields.DATE, SvcDailyNutrition.Fields.ENERGY_KJ, SvcDailyNutrition.Fields.HYDRATION_ML,
        )
        val MENSTRUATION_DAY_FIELDS = arrayOf(
            SvcMenstruationDay.Fields.DATE, SvcMenstruationDay.Fields.FLOW, SvcMenstruationDay.Fields.PERIOD_START,
        )
        val SLEEP_SESSION_FIELDS = arrayOf(
            SvcSleepSession.Fields.DATE, SvcSleepSession.Fields.START, SvcSleepSession.Fields.END,
            SvcSleepSession.Fields.DURATION_SEC, SvcSleepSession.Fields.SOURCE, SvcSleepSession.Fields.AWAKE_MIN,
            SvcSleepSession.Fields.REM_MIN, SvcSleepSession.Fields.LIGHT_MIN, SvcSleepSession.Fields.DEEP_MIN,
        )
        val HEALTH_SAMPLE_FIELDS = arrayOf(
            // SAMPLE_ID is the content-address key (§12 B2) — project it, or the entity can't be keyed
            // and the read drops it (every sibling relation includes its key field for the same reason).
            SvcHealthSample.Fields.SAMPLE_ID,
            SvcHealthSample.Fields.METRIC, SvcHealthSample.Fields.VALUE, SvcHealthSample.Fields.UNIT,
            SvcHealthSample.Fields.TIME, SvcHealthSample.Fields.SOURCE, SvcHealthSample.Fields.RESTING,
        )
        val LATEST_SAMPLE_FIELDS = arrayOf(
            // LATEST_KEY is the content-address key — project it or the read drops rows (§12 B2 gotcha).
            SvcLatestSample.Fields.LATEST_KEY,
            SvcLatestSample.Fields.METRIC, SvcLatestSample.Fields.VALUE, SvcLatestSample.Fields.UNIT,
            SvcLatestSample.Fields.TIME, SvcLatestSample.Fields.SOURCE, SvcLatestSample.Fields.RESTING,
        )
        val MEDICAL_RECORD_FIELDS = arrayOf(
            SvcMedicalRecord.Fields.RECORD_ID, SvcMedicalRecord.Fields.KIND, SvcMedicalRecord.Fields.TITLE,
            SvcMedicalRecord.Fields.DETAIL, SvcMedicalRecord.Fields.EFFECTIVE_DATE, SvcMedicalRecord.Fields.ISSUER,
            SvcMedicalRecord.Fields.SOURCE,
        )
    }
}
