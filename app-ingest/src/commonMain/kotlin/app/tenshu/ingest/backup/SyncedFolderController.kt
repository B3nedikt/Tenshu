package app.tenshu.ingest.backup

import org.tenshu.core.userInitiatedWork
import app.tenshu.ingest.backup.BackupUploadOutcome
import app.tenshu.ingest.backup.StorageStatusModel
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.file.BACKUP_ROOT
import org.tenshu.core.ingest.IngestSource

/**
 * Consecutive per-file failures before the drain gives up on that bucket for this pass.
 *
 * ⚠ Not a retry policy — the frozen cursor already retries everything from the failure on. This is
 * the line between *one bad file* (keep draining) and *nothing here works* (stop, so a systemic
 * failure costs five round trips rather than one per file in the folder). Any success resets it.
 */
const val MAX_CONSECUTIVE_BUCKET_FAILURES = 5

/**
 * Candidates the drain hashes ahead of their turn, so the content skip asks the realm about a batch
 * in one walk instead of one walk per photo (`photos-app.md` §40).
 *
 * ⚠ Each realm walk is O(realm) in Control, so a bigger batch is fewer walks — but every candidate
 * in it is read off the disk before the first of them uploads, and a Stop lands only after the
 * batch is hashed. [DUPLICATE_LOOKAHEAD_BYTES] caps that second cost for a folder of videos.
 */
const val DUPLICATE_LOOKAHEAD_ITEMS = 32

/** The most candidate bytes one lookahead batch reads (at least one candidate is always taken). */
const val DUPLICATE_LOOKAHEAD_BYTES = 64L * 1024 * 1024

/**
 * The candidates from [from] on that the content skip should hash together: up to
 * [DUPLICATE_LOOKAHEAD_ITEMS], within [DUPLICATE_LOOKAHEAD_BYTES], and never one [seenUris]
 * already holds — the drain skips those without reading them. The item at [from] is always first.
 *
 * ⚠⚠ An item of **unknown** size ends the batch — it is not a free one. Counting it as zero let a
 * source that sizes nothing (iOS: PhotoKit has no cheap size) hash 32 videos of any length before
 * the first upload or the next Stop. So such a source gets a batch of one, i.e. one realm walk per
 * candidate: the cost of not knowing, and smaller than the cost of guessing.
 */
internal fun duplicateLookahead(items: List<MediaItem>, from: Int, seenUris: Set<String>): List<MediaItem> {
    val batch = mutableListOf(items[from])
    var bytes = items[from].sizeBytes ?: return batch
    val taken = mutableSetOf(items[from].uri)
    for (next in items.drop(from + 1)) {
        if (batch.size >= DUPLICATE_LOOKAHEAD_ITEMS) break
        if (next.uri in seenUris || next.uri in taken) continue
        val size = next.sizeBytes ?: break
        if (bytes + size > DUPLICATE_LOOKAHEAD_BYTES) break
        batch += next
        taken += next.uri
        bytes += size
    }
    return batch
}

/**
 * App-scoped owner of the Synced-Folders auto-backup loop
 * (`docs/proposals/drive-synced-folders.md` §4.3 — the **in-process,
 * app-alive** tier). The auto-driven sibling of `FolderUploadController`: it runs
 * on a process-lifetime [scope] owned by a Koin `single`, observes the device's
 * backup settings + storage + each source's change signal, and on each trigger —
 * when **enabled** *and* **storage available** *and* **constraints satisfied** —
 * drains every selected bucket of every [BackupSource] through the existing
 * per-file upload data plane ([BackupDataPlane.uploadForBackup] — Drive's
 * `FilesRepository` implements it, `photos-app.md` §19), advancing the per-(source, bucket)
 * cursor (§3.5).
 *
 * The engine is **source-generic**: it drives a [List] of [BackupSource]s, each
 * its own adapter + per-source settings + registry row + kill switch, so a gallery
 * source and a SAF document-tree source need no engine change. The public [state]
 * is a single aggregate [StateFlow] for the Android FGS launcher — per-source
 * progress is folded together by [aggregateState].
 *
 * It is **not** a Control-resident `HeadlessSafe` op (§3.3 model divergence):
 * enumeration + FD-opening can't leave the app's own permission context, so this
 * is the app-resident loop. ⚠ §§3.3/3.4/4.4's **Control-owned engine with a
 * Control→app reverse trigger is superseded** — the decision
 * (`milestones.md` "Background-service story") is *capture app-side,
 * policy Control-side*, with no reverse trigger. [ingestNow] is
 * therefore the "Back up now" entry (view models and the iOS surface call it),
 * not a hook Control drives.
 *
 * All decision logic ([evaluateBackupGate], [backupDestinationFolder], the drain
 * sequencing) is pure / desktop-unit-tested; this class is the flow wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncedFolderController(
    private val preferences: BackupPreferences,
    private val sources: List<BackupSource>,
    private val dataPlane: BackupDataPlane,
    private val environment: BackupEnvironment,
    private val registry: IngestRegistry = NoopIngestRegistry,
    /** Surfaces the "a new folder appeared — back it up?" prompt (Android only;
     *  [NoopNewFolderAnnouncer] everywhere else). */
    private val announcer: NewFolderAnnouncer = NoopNewFolderAnnouncer,
    /** Whether this app can see the whole media library — gates the prompt, which
     *  is meaningless over a user-filtered view (Android 14 partial access). */
    private val mediaAccess: MediaAccessProvider = FullMediaAccessProvider,
    /** The ingest-time content dedup (`photos-app.md` §16). [NoopDuplicateContentGate] skips nothing. */
    private val duplicates: DuplicateContentGate = NoopDuplicateContentGate,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    // Public state is ONE aggregate flow (the Android FGS launcher reads it as-is);
    // per-source progress is tracked separately in [sourceStates] and folded in.
    private val _state = MutableStateFlow<BackupState>(BackupState.Disabled)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /**
     * The user's own *Back up now* while its run is in flight, else `null`. Drains read it to stamp
     * [BackupState.BackingUp.userInitiated], which is what the Android foreground service keys on
     * ([startsForegroundService]): Play's `dataSync` policy allows a foreground service for a
     * transfer the user started and names "daily auto backup of all new photos" as the
     * counter-example (`drive-synced-folders-phase2.md` §4.1a).
     *
     * ⚠⚠ Not for the launcher to combine with [state]. The two propagate independently, so a pair
     * built from them can show an automatic drain beside a request that has already been cleared —
     * or a user run beside none. The stamp travels inside the one value; this flow is the loop's.
     *
     * Identity, not equality, is what the loop compares: a second tap while the first run is
     * finishing is a new request and must survive the first one being cleared.
     */
    private val _userRequest = MutableStateFlow<UserBackupRequest?>(null)
    val userRequest: StateFlow<UserBackupRequest?> = _userRequest.asStateFlow()

    // Per-source live state — the drain mutates the owning source's flow; the public
    // [_state] is the [aggregateState] of these, and status is reported per source.
    private val sourceStates: Map<String, MutableStateFlow<BackupState>> =
        sources.associate { it.sourceId to MutableStateFlow<BackupState>(BackupState.Disabled) }

    private var started = false

    /**
     * Whether this process runs the drain itself — true after [start] (Android / iOS),
     * false after [startRegistrationOnly] (desktop, where Control's `FolderWatcher` owns
     * ingest). Gates the settings screen's *Back up now*: on desktop [ingestNow] has no
     * consumer to wake, and a button that cannot do anything is worse than no button.
     */
    var runsDrainInProcess: Boolean = false
        private set

    // Conflated: triggers arriving while a drain runs collapse to a single pending
    // rerun, so a burst of MediaStore changes coalesces into one follow-up drain.
    private val triggers = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var latestSettings: BackupSettings? = null
    @Volatile private var latestStorage: StorageStatusModel? = null

    // Serialises drains so the trigger-loop path and the awaitable [ingestAndAwait]
    // path (the headless Android BackupWorker, §4.1) can't run a source's
    // drain concurrently and double-scan.
    private val drainMutex = Mutex()
    private val clearJobs = mutableMapOf<String, Job?>()

    /** The drain currently in flight, so [stop] can cancel it. Null between drains. */
    @Volatile private var drainJob: Job? = null

    /**
     * **Android (app-alive Layer-2 tier).** Register every source + run the
     * in-process drain: observe settings / storage / folder-changes, drain on each
     * trigger, and report live per-source status. Idempotent; call once at app
     * startup so backup runs while the Drive process is alive even if the user never
     * opens the Synced Folders screen (§3.4 Layer 2).
     *
     * On **desktop** the resident Control daemon owns the ingest loop (its
     * `FolderWatcher`, §4.2), so desktop calls [startRegistrationOnly] instead —
     * see that method for why the drain must not run on both.
     */
    fun start() {
        if (started) return
        started = true
        runsDrainInProcess = true

        val settings = settingsFlow()
        launchRegistration()
        launchStatusReporting()
        launchStateAggregation()

        scope.launch {
            settings.collect {
                latestSettings = it
                triggers.trySend(Unit)
            }
        }
        scope.launch {
            dataPlane.storageStatus().collect {
                latestStorage = it
                triggers.trySend(Unit)
            }
        }
        // Re-evaluate when the network or the charger moves. ⚠ Not for the uploads — the
        // WorkManager tier already wakes on its own constraints — but for the STATE: the gate is
        // only read inside a drain, so without this "Paused — on mobile data" would stay on screen
        // after Wi-Fi came back, until the next photo or the periodic wake.
        scope.launch {
            environment.changes().collect { triggers.trySend(Unit) }
        }
        // Re-drain when a source's watched buckets gain content (Layer-2 trigger).
        sources.forEach { source ->
            scope.launch {
                combine(settings, source.selectedBuckets) { s, buckets -> s.enabled to buckets }
                    .flatMapLatest { (enabled, buckets) ->
                        if (enabled && buckets.isNotEmpty()) source.provider.observeChanges(buckets)
                        else emptyFlow()
                    }.collect { triggers.trySend(Unit) }
            }
        }
        // Re-drain when the user changes *which* folders are backed up. Adding a folder
        // is a request to back up what is already in it, and nothing else here would
        // notice: the observeChanges subscription above is re-established on a selection
        // change but a ContentObserver only speaks when the *library* changes, so a newly
        // ticked folder full of old photos would wait for the next unrelated media event
        // or the 6-hour periodic wake. (Removing one costs a cheap no-op drain, which is
        // the right trade for keeping this a plain "the selection moved" signal.)
        sources.forEach { source ->
            scope.launch {
                source.selectedBuckets.distinctUntilChanged().collect { triggers.trySend(Unit) }
            }
        }
        // Single serialized drain consumer. A best-effort background backup must
        // never crash the host app or silently kill the loop: a transient
        // environment/IO failure on one tick (e.g. a ConnectivityManager read, an
        // upload error) is swallowed so the next trigger retries. Cancellation still
        // propagates so scope teardown works.
        scope.launch {
            for (signal in triggers) {
                // The request this iteration has served, re-read before every drain so a tap that
                // lands mid-run joins it. Cleared only if it is still the current one — see
                // [userRequest] — so a tap after the last drain carries into the next iteration.
                var served: UserBackupRequest? = null
                try {
                    served = _userRequest.value
                    runDrainGated()
                    // ⚠ Coalesce into ONE run (`drive-upload-progress.md` §14.3). A trigger that
                    // is *already* pending means more work arrived while this drain ran — a
                    // burst of media events, the settings write that follows a folder tick —
                    // so it is the same backup as far as the user is concerned. Draining it
                    // here, before publishing a terminal state, is what keeps the on-screen
                    // count from restarting with a fresh denominator part-way through.
                    // The channel is CONFLATED, so this drains at most one pending trigger per
                    // round and cannot spin: each extra drain needs its own new trigger.
                    while (triggers.tryReceive().isSuccess) {
                        served = _userRequest.value
                        runDrainGated()
                    }
                    finishRuns()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    // Drop this tick; the next trigger re-drains. The run is closed either way,
                    // so a failure cannot strand the UI mid-count.
                    runCatching { finishRuns() }
                } finally {
                    // ⚠ After finishRuns: the terminal summary is published while the request is
                    // still set, so the service it started shows it before demoting.
                    if (served != null) _userRequest.compareAndSet(served, null)
                }
            }
        }
    }

    /**
     * **Desktop.** Control's resident in-process `FolderWatcher`
     * (`drive-synced-folders-phase2.md` §4.2) owns the desktop ingest loop, so
     * Drive-desktop must **not** also drain — both running would double-scan and
     * write status twice (the §3.5 dedup backstop dedups the *uploads*, but the
     * redundant scan + sync-block status writes are wasteful). Drive-desktop keeps
     * only the **registration** (so Control knows which directory paths to watch)
     * and the settings UI; **status is reported by Control's watcher, not here.**
     */
    fun startRegistrationOnly() {
        if (started) return
        started = true
        launchRegistration()
    }

    /** The **global** gate inputs — shared across all sources. Per-source buckets /
     *  video selection live on each [BackupSource], not here. */
    private fun settingsFlow(): Flow<BackupSettings> = combine(
        preferences.observeEnabled(),
        preferences.observeWifiOnly(),
        preferences.observeChargingOnly(),
        preferences.observePausedByUser(),
    ) { enabled, wifiOnly, chargingOnly, pausedByUser ->
        BackupSettings(enabled, wifiOnly, chargingOnly, pausedByUser)
    }

    /**
     * The same three gate inputs, read **once**. ⚠ Not `settingsFlow().first()`: the
     * preference flows seed a default ahead of the stored row for the benefit of screens,
     * so the first combined tuple usually carries `enabled = false` and the gate would
     * report `Disabled` on a device where backup is on. Callers that only get one look —
     * the headless [ingestAndAwait] — must not read the seed.
     */
    private suspend fun readSettings(): BackupSettings = BackupSettings(
        enabled = preferences.readEnabled(),
        wifiOnly = preferences.readWifiOnly(),
        chargingOnly = preferences.readChargingOnly(),
        pausedByUser = preferences.readPausedByUser(),
    )

    /**
     * Register each device source with Control's ingest registry (both tiers) and
     * keep its `watchedPaths` fresh as the selected buckets change: on **desktop** a
     * bucket id is either a raw directory path for unsandboxed development or an opaque
     * security-scoped grant for MAS macOS (what Control's `FolderWatcher` resolves); on
     * Android the ids are `MediaStore` bucket ids (inert — Control hosts no Android
     * watcher). Idempotent upsert (preserves the kill switch + status); fires on the
     * first (possibly empty) emission so an un-configured source still shows in the
     * inventory.
     */
    private fun launchRegistration() {
        sources.forEach { source ->
            scope.launch {
                source.selectedBuckets.distinctUntilChanged().collect { buckets ->
                    registry.registerSource(
                        sourceId = source.sourceId,
                        displayName = source.displayName,
                        sourceKind = source.sourceKind,
                        watchedPaths = buckets.joinToString("\n"),
                    )
                }
            }
        }
    }

    /**
     * Mirror each source's state into the registry's live status (Android tier only —
     * on desktop Control's watcher reports status). Pushed only when the *category*
     * changes (deduped on the status code), not per file: `IngestSource` is
     * `Syncable(Eager)`, so a per-file write would emit a sync block per photo.
     */
    private fun launchStatusReporting() {
        sources.forEach { source ->
            scope.launch {
                sourceStates.getValue(source.sourceId).map { backupStatusFor(it) }
                    .distinctUntilChangedBy { it.status }
                    .collect { registry.updateStatus(source.sourceId, it.status, it.pendingCount) }
            }
        }
    }

    /** Fold the per-source states into the single public [_state]. */
    private fun launchStateAggregation() {
        if (sourceStates.isEmpty()) return
        scope.launch {
            combine(sourceStates.values.toList()) { states -> aggregateState(states.toList()) }
                .collect { _state.value = it }
        }
    }

    /**
     * Fire-and-forget Layer-3 entry (§4.4): nudge the serialized trigger loop to run
     * one gated drain. Used by live in-process wakers; the headless background worker
     * uses [ingestAndAwait] instead (it needs to await the result).
     */
    fun ingestNow() {
        triggers.trySend(Unit)
    }

    /**
     * **The user's *Back up now*** (Android). Like [ingestNow], plus two things only a tap may do:
     *
     *  - it marks the run as **user-initiated** ([userRequest]), which is the one thing allowed to
     *    start the Android foreground service; and
     *  - it may [ignore] the user's own Wi-Fi-only / charging-only switches **for this run only**
     *    — the one-time override the screen offers after asking ([overridableBlockersNow]). The
     *    saved settings are never written; the next automatic drain obeys them again.
     *
     * ⚠ [ignore] lifts exactly what it names. No network, no storage and the user's Stop still
     * apply — those are not the user's preferences, and no confirmation can make a byte fit.
     */
    fun backUpNow(ignore: Set<BackupConstraint> = emptySet()) {
        _userRequest.value = UserBackupRequest(ignore)
        triggers.trySend(Unit)
    }

    /**
     * Which of the user's own constraints are, right now, the *only* thing standing between
     * *Back up now* and a backup — what the screen offers to ignore once. Empty when nothing
     * blocks, and also when something else does (no network, no storage, Stop, backup off):
     * asking "use mobile data this once?" on a phone with no connection would offer a choice
     * that cannot change the answer.
     */
    suspend fun overridableBlockersNow(): Set<BackupConstraint> = overridableBlockers(
        settings = readSettings(),
        storage = latestStorage ?: dataPlane.storageStatus().first(),
        network = environment.network(),
        charging = environment.isCharging(),
    )

    /**
     * **The Android background worker (§4.1).** Run one gated drain pass inline
     * and report whether it ran, for a `WorkManager` `Result`. Reads settings +
     * storage **fresh** (a headless worker may run before [start]'s collectors have
     * emitted, so the cached [latestSettings]/[latestStorage] can't be relied on).
     *
     * Returns `false` only when this device's **sync identity isn't resolved yet**
     * (worth a retry); `true` otherwise — including when the gate pauses or disables
     * the drain, because "nothing to do right now" is not a failure (per-file errors
     * already hold the cursor and retry on the next drain).
     */
    suspend fun ingestAndAwait(): Boolean =
        drainInBackground(shouldYield = { false }) != BackgroundDrainResult.NOT_READY

    /**
     * **The Android WorkManager job's drain** (`drive-synced-folders-phase2.md` §4.1a): one gated
     * pass, inline, that stops starting new files once [shouldYield] says the job's budget is
     * spent. An OS job has a hard ~10-minute limit, and a backlog of videos is longer than that;
     * the job yields at a **file boundary** and enqueues a continuation, rather than letting the OS
     * cancel it mid-file.
     *
     * ⚠⚠ Why the boundary matters (`drive-synced-folders-phase2.md` §4.1b): Control saves a
     * file's row *before* it journals the file's bytes, so a cancellation landing between the two
     * leaves a row the next drain reads as "already at destination" and skips. A cancel after the
     * journal is free — the journal owns the retry — but nothing on this side can tell the two
     * apart, so the budget never cancels; only an OS stop can.
     *
     * A yielded run stays **open**: its figures read "40 of 240", not "Backed up 40", and the next
     * drain re-bases the total on a fresh enumeration instead of adding to it.
     */
    suspend fun drainInBackground(shouldYield: () -> Boolean): BackgroundDrainResult {
        // Identity not ready (e.g. woke before sync resolved) → ask WorkManager to retry.
        if (preferences.deviceLabel() == null) return BackgroundDrainResult.NOT_READY
        val settings = readSettings()
        val storage = dataPlane.storageStatus().first()
        // ⚠ No user request here, ever: this is the automatic path, and the override belongs to
        // the tap that confirmed it.
        val yielded = drainAllSources(
            settings,
            storage,
            ignore = { emptySet() },
            shouldYield = shouldYield,
            userInitiated = { false },
        )
        if (yielded) return BackgroundDrainResult.YIELDED
        // One wake == one run: there is no trigger loop behind this path to coalesce with,
        // so the terminal state is published here (§14.3).
        finishRuns()
        return BackgroundDrainResult.COMPLETE
    }

    /**
     * ⚠ The drain runs as a **sibling** job rather than inline, so [stop] can cancel *this drain*
     * without killing the trigger loop that owns it — cancelling the loop would stop backup for
     * the process lifetime, which is not what Stop means (D1: a pause).
     *
     * `async` + `await` rather than `launch` + `join` so a drain failure still surfaces here and
     * is handled by the loop's existing catch, instead of escaping to the scope's supervisor
     * where the run would never be closed.
     */
    private suspend fun runDrainGated(): Unit = coroutineScope {
        val settings = latestSettings ?: return@coroutineScope
        val job = launch {
            drainAllSources(
                settings = settings,
                storage = latestStorage,
                // Read per use, not captured: a tap that lands mid-drain promotes this run.
                ignore = { _userRequest.value?.ignore.orEmpty() },
                shouldYield = { false },
                userInitiated = { _userRequest.value != null },
            )
            // ⚠⚠ Drive's gate is only half of Wi-Fi-only: Control's content plane enforces the
            // same realm setting, and parks every byte this drain handed it while on mobile data.
            // The user's one-time override has to reach that gate too, or "use mobile data this
            // once" backs up nothing until Wi-Fi returns.
            if (BackupConstraint.WIFI_ONLY in _userRequest.value?.ignore.orEmpty()) {
                runCatchingCancellable { dataPlane.uploadOverMeteredOnce() }
            }
        }
        drainJob = job
        try {
            job.join()
        } finally {
            drainJob = null
        }
        // ⚠⚠ Cancelling a CHILD does not cancel its parent, but a child that FAILS does — which
        // is exactly the split needed here, and why this is a child of `coroutineScope` rather
        // than `scope.async`. [stop] cancels: `join` returns, the loop lives, Resume works. The
        // drain throws: the scope fails and it reaches the loop's existing catch, which closes
        // the run. ⚠ `scope.async` looks equivalent and is not — a drain failure would cancel
        // the whole controller scope unless that scope happened to carry a SupervisorJob, killing
        // the trigger loop for the process lifetime. Pinned by
        // `SyncedFolderControllerTest.drainSurvivesThrowingEnvironment_doesNotCrashOrKillLoop`.
    }

    /**
     * **Stop** — a pause, not a cancel (`drive-upload-stop-and-reclamation.md` D1).
     *
     * Persists the pause first, so the decision survives the process and the trigger the write
     * itself raises cannot start a fresh drain, then cancels the in-flight one. ⚠ Order matters:
     * cancelling first would race the trigger loop into starting the next drain before the gate
     * could see the pause.
     *
     * ⚠⚠ Cancelling reaches the *current file*, not just the queue: `uploadForBackup` awaits via
     * `awaitOrWireCancel`, which sends a **wire cancel** for the task on cancellation — so the
     * upload stops at a chunk boundary (D2, at most `chunkTransferConcurrency × 2 MB` in flight)
     * rather than after the whole file. That is the difference between Stop feeling instant and
     * Stop meaning "in a few minutes" on a 200 MB video.
     *
     * ⚠ D3: the abandoned chunks are orphaned on the relay and nothing reclaims them (§3.4 D3,
     * §4.15).
     * Stopping a large upload has a storage cost the user cannot see (§3.2).
     */
    suspend fun stop() {
        preferences.setPausedByUser(true)
        drainJob?.cancel()
    }

    /** **Resume** — clear the pause and drain from the cursors the stop left behind. */
    suspend fun resume() {
        preferences.setPausedByUser(false)
        triggers.trySend(Unit)
    }

    /**
     * The gate + per-source kill-switch + drain, serialized by [drainMutex] so the
     * trigger-loop and [ingestAndAwait] paths never drain a source concurrently.
     *
     * @return whether the drain stopped early because [shouldYield] said so.
     */
    private suspend fun drainAllSources(
        settings: BackupSettings,
        storage: StorageStatusModel?,
        ignore: () -> Set<BackupConstraint>,
        shouldYield: () -> Boolean,
        userInitiated: () -> Boolean,
    ): Boolean {
        return drainMutex.withLock {
            // Announce *before* the gate: a prompt costs no bandwidth and no quota,
            // so being paused on cellular or out of space is no reason to withhold
            // it. The master switch is the only gate that applies (a user with
            // backup off has not asked to be told about their folders).
            if (settings.enabled) announceNewFolders()

            val gate = evaluateBackupGate(
                settings = settings.ignoring(ignore()),
                storage = storage,
                network = environment.network(),
                charging = environment.isCharging(),
            )
            // ⚠ Once per drain, not per source: the view remembers the realm's answers, and every
            // source asks the same question of it. Opening reads only the setting — the realm is
            // asked per batch of candidates (§40), so a run with nothing new asks it nothing.
            val duplicateView = if (gate == BackupGate.Allowed) duplicates.openForDrain() else null

            for (source in sources) {
                if (shouldYield()) return@withLock true
                // §5 read-gate preamble (per source): honour Control's central kill
                // switch (`IngestSource.enabled`) for this source before the shared
                // gate. One-shot, fail-open read; the in-app master toggle (the gate,
                // below) remains the primary gate.
                if (!registry.isEnabled(source.sourceId)) {
                    setStable(source, BackupState.Disabled)
                    continue
                }
                when (gate) {
                    BackupGate.Disabled -> setStable(source, BackupState.Disabled)
                    BackupGate.PausedByUser -> setStable(source, BackupState.PausedByUser)
                    is BackupGate.Paused ->
                        setStable(source, BackupState.Paused(gate.reason, countWaiting(source)))
                    BackupGate.Allowed ->
                        if (drainSource(source, settings, storage, duplicateView, ignore, shouldYield, userInitiated)) {
                            return@withLock true
                        }
                }
            }
            false
        }
    }

    /**
     * "A new folder appeared — back it up?" (`NewFolderDetector`). Runs inside the
     * drain because that inherits both trigger tiers for free — the app-alive
     * `ContentObserver` and the headless `BackupWorker` wake — so a folder that
     * appears while Drive is closed is still announced at the next wake.
     *
     * Failures are swallowed per source: the prompt is a nicety, and a
     * `listBuckets` error must not take down the drain that follows it.
     */
    private suspend fun announceNewFolders() {
        if (!preferences.readBackupNewFolders()) return
        // ⚠ Android 14 partial access: MediaStore silently filters every query to the
        // user's selection, so a bucket listing enumerates "folders holding something
        // I picked", not "folders on this device". Announcing over that view is worse
        // than useless — it would prompt about a folder because the user picked one
        // photo from it, AND it would poison the known-set with a filtered baseline,
        // so the real folders would read as already-seen once full access is granted.
        // Standing down entirely is the only correct behaviour.
        if (mediaAccess.current() != MediaAccess.FULL) return
        for (source in sources) {
            if (!source.announcesNewBuckets) continue
            try {
                val buckets =
                    source.provider.listBuckets(includeVideo = source.readIncludeVideo())
                val scan = scanForNewFolders(
                    buckets = buckets,
                    known = preferences.readKnownBuckets(),
                    selected = source.readSelectedBuckets(),
                )
                // Persist the known-set BEFORE announcing: a crash between the two
                // costs one missed prompt, whereas announcing first and crashing
                // would re-announce the same folder on every wake.
                preferences.addKnownBuckets(scan.known)
                scan.announce.forEach { announcer.announce(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Best-effort; the next trigger re-scans.
            }
        }
    }

    private suspend fun drainSource(
        source: BackupSource,
        settings: BackupSettings,
        storage: StorageStatusModel?,
        duplicateView: DuplicateContentView?,
        ignore: () -> Set<BackupConstraint>,
        shouldYield: () -> Boolean,
        userInitiated: () -> Boolean,
    ): Boolean {
        // One BackgroundTask per source drain (`core-background-processing.md` §10.6) — every backup upload's
        // saves stage into its sync lane (no-op batching when TaskService is
        // unavailable; [BackupDataPlane.withBulkUploadTask]'s default runs the drain bare).
        return dataPlane.withBulkUploadTask("drive/synced-folders") {
            drainSourceInner(source, settings, storage, duplicateView, ignore, shouldYield, userInitiated)
        }
    }

    /**
     * How many items [source] would offer a drain right now — what a paused backup is holding.
     *
     * ⚠ An upper bound, not a promise: it is the enumeration *before* the two skips an allowed
     * drain applies (already at the destination, already in the realm by content), so a few of
     * these may turn out to need no upload. Counting it exactly would mean hashing every file
     * while paused on battery, to refine a number whose job is to say "something is waiting".
     *
     * `0` when the source cannot be enumerated — a pause must still be reported as a pause.
     */
    private suspend fun countWaiting(source: BackupSource): Int = runCatchingCancellable {
        val includeVideo = source.readIncludeVideo()
        val cursors = preferences.readCursors(source.sourceId)
        source.readSelectedBuckets()
            .flatMap { bucketId -> source.provider.listNewItems(bucketId, cursors[bucketId], includeVideo) }
            // The same asset can sit in several selected albums — see `seenUris`.
            .distinctBy { it.uri }
            .size
    }.getOrDefault(0)

    /**
     * The gate as it stands **right now**, not as it stood when this drain started
     * (`drive-upload-stop-and-reclamation.md` §3.6).
     *
     * ⚠⚠ Re-read per file because turning backup off would otherwise only queue *another* drain:
     * the in-flight one holds [drainMutex] and would upload every remaining file regardless. On a
     * phone that has just picked up a hundred photos that is minutes of uploading after the user
     * said stop, which reads as the switch being broken.
     *
     * ⚠⚠ Reads the settings **directly** ([readSettings]) rather than the cached
     * [latestSettings]. The cache is written by a collector coroutine, so believing it would
     * make "did the user just turn this off?" depend on whether that coroutine happened to be
     * scheduled between two uploads — true in production only because a real upload suspends
     * on I/O. A stop must not be contingent on someone else getting a timeslice.
     * [readSettings] also deliberately bypasses the seeded default the preference flows emit
     * ahead of the stored row, which the cache can transiently hold.
     *
     * ⚠ That is three preference reads per file. Negligible against an upload (milliseconds
     * against seconds), and the alternative is a stop that does not stop. Storage keeps using the cache — it
     * moves slowly, and `storageStatus().first()` is a genuinely expensive read.
     */
    private suspend fun currentGate(
        settings: BackupSettings,
        storage: StorageStatusModel?,
        ignore: Set<BackupConstraint>,
    ): BackupGate = evaluateBackupGate(
        settings = runCatchingCancellable { readSettings() }.getOrDefault(settings).ignoring(ignore),
        storage = latestStorage ?: storage,
        network = environment.network(),
        charging = environment.isCharging(),
    )

    /** @return whether the drain stopped early because [shouldYield] said so. */
    private suspend fun drainSourceInner(
        source: BackupSource,
        settings: BackupSettings,
        storage: StorageStatusModel?,
        duplicateView: DuplicateContentView?,
        ignore: () -> Set<BackupConstraint>,
        shouldYield: () -> Boolean,
        userInitiated: () -> Boolean,
    ): Boolean {
        val deviceLabel = preferences.deviceLabel() ?: return false
        // ⚠ One-shot reads, NOT `selectedBuckets.first()` — see [BackupSource].
        val selectedBuckets = source.readSelectedBuckets()
        val includeVideo = source.readIncludeVideo()
        val cursors = preferences.readCursors(source.sourceId)
        val buckets = source.provider.listBuckets(includeVideo = includeVideo).associateBy { it.id }
        val stateFlow = sourceStates.getValue(source.sourceId)

        // Snapshot the new items per bucket once, so `total` is known up-front and
        // the cursor advances monotonically (items are oldest-first).
        val perBucket = selectedBuckets.map { bucketId ->
            bucketId to source.provider.listNewItems(bucketId, cursors[bucketId], includeVideo)
        }
        val total = perBucket.sumOf { it.second.size }
        val enumeratedBytes = perBucket.sumOf { (_, items) -> items.sumOf { it.sizeBytes ?: 0L } }
        // An interrupted run (a job that yielded, an OS stop) still counts the files it never
        // reached; this enumeration is the honest remainder, so it REPLACES that part of the
        // total rather than adding to it — else every continuation would count them twice.
        runs[source.sourceId]?.takeIf { it.interrupted }?.let { run ->
            run.total = run.completed
            run.totalBytes = run.handedOffBytes
            run.interrupted = false
        }
        if (total == 0) {
            // ⚠ Only when this drain stands alone. Mid-run — a coalesced drain that found
            // nothing new — reporting UpToDate would end the run on screen while the next
            // drain of the same run is about to start, which is the flicker [runs] exists to
            // stop. [finishRuns] publishes the terminal state for a run.
            if (runs[source.sourceId] == null) setStable(source, BackupState.UpToDate)
            return false
        }

        // Fold this drain into the source's RUN (drive-upload-progress.md §14.3): the figures
        // the UI shows span every drain since the engine was last idle, so the denominator
        // only ever grows by files that genuinely arrived, and the numerator never restarts.
        val run = runs.getOrPut(source.sourceId) { RunAccumulator() }
        run.total += total
        // The bytes behind that count, from the SAME enumeration (§14.4). ⚠ One unsizable item
        // forfeits the whole run's byte figure rather than understating it: a total that is
        // quietly short by one video is worse than no total, because nothing on screen says so.
        run.totalBytes += enumeratedBytes
        if (perBucket.any { (_, items) -> items.any { it.sizeBytes == null } }) {
            run.bytesKnown = false
        }

        var completed = 0
        var uploaded = 0
        var failed = 0
        var alreadyHeld = 0
        var doneBytes = 0L
        // ⚠ `completed` and the bytes it stands for must move together at EVERY exit an item can
        // take — uploaded, skipped by path, skipped by content hash, failed. A site that bumped
        // one without the other would leave the item counted as done while its bytes stayed
        // "remaining" for the rest of the run, so the banner would count files down to zero with
        // gigabytes still to go. One function, four call sites, no way to do half of it.
        fun itemDone(item: MediaItem) {
            completed++
            doneBytes += item.sizeBytes ?: 0L
        }
        // One string, not a list: the terminal summary has room for a sentence, and the case this
        // exists for is every file failing for the same reason.
        var lastFailureReason: String? = null
        // The typed twin of [lastFailureReason], for a failure this engine itself knows the cause
        // of — rendered from each platform's own catalogue, never shipped as English. At most one
        // of the two is set: the LAST failure decides which.
        var lastFailureKind: BackupFailureKind? = null
        // Cross-bucket dedupe (see [MediaItem.uri]). Buckets are NOT always disjoint:
        // an Android bucket is a directory, so a photo lives in exactly one, but an iOS
        // album is a *view* — the same asset can sit in several selected albums. Without
        // this the drain would upload it once per album, to a different destination path
        // each time, and nothing downstream would collapse them: `contentId` is random
        // per write and the CEK + AEAD nonce are fresh per emit, so two encryptions of
        // identical bytes yield different blob refs and the relay stores (and bills) two
        // full copies. That is deliberate — a plaintext-derived ref would be a
        // confirmation-of-file oracle — which is exactly why the dedupe has to happen
        // HERE, on the client that already holds the plaintext and learns nothing new.
        val seenUris = mutableSetOf<String>()
        /**
         * Fold this drain's figures into the run. ⚠ Called on EVERY way out — the normal end, a
         * yield, a cancellation (Stop, an OS job stop) — because the run outlives this drain: a
         * cancelled drain that left its counts behind would make the next summary under-report,
         * with the figures on screen frozen at a frame that never added up.
         */
        var folded = false
        fun foldIntoRun(interrupted: Boolean) {
            if (folded) return
            folded = true
            run.completed += completed
            run.handedOffBytes += doneBytes
            run.uploaded += uploaded
            run.failed += failed
            run.alreadyInRealm += alreadyHeld
            // ⚠ The run's cause is its LAST failure's, across drains as within one: a drain that
            // failed replaces both fields together (never one beside the other drain's), and a
            // drain in which nothing failed leaves them alone — the summary still counts the
            // earlier failures. Keyed on the count, not on a cause being present, so it holds for
            // a failure with neither.
            if (failed > 0) {
                run.failureReason = lastFailureReason
                run.failureKind = lastFailureKind
            }
            if (interrupted) run.interrupted = true
        }
        var yielded = false
        try {
        buckets@ for ((bucketId, items) in perBucket) {
            if (items.isEmpty()) continue
            val bucketName = buckets[bucketId]?.displayName ?: bucketId.substringAfterLast('/')
            val destination = backupDestinationFolder(deviceLabel, bucketName)
            // Once per bucket per drain, not per item — see
            // [BackupDataPlane.ensureBackupDestination]. ⚠ Lazily, before the bucket's first
            // UPLOAD (§3.3): a bucket whose every file the realm already holds must not leave an
            // empty `/Backup/<device>/<bucket>` behind, which reads as a backup that broke.
            var destinationEnsured = false
            var reachedInBucket = 0
            var cursorFrozen = false
            var consecutiveFailures = 0
            // ⚠⚠ Every "this item is dealt with" path advances through here, and none may do so
            // once something in this bucket has failed: the cursor is a watermark, not a ledger
            // ([BackupPreferences.clearCursor]), so moving it past a failed file loses that file.
            suspend fun advanceCursor(cursor: String) {
                if (!cursorFrozen) preferences.setCursor(source.sourceId, bucketId, cursor)
            }
            /**
             * Count [item] as failed and freeze the cursor at it. `true` when the bucket has now
             * failed [MAX_CONSECUTIVE_BUCKET_FAILURES] times running — the caller then stops it,
             * having given back what it will not reach (files AND bytes: the denominator spans
             * the whole RUN, `drive-upload-progress.md` §14.3).
             */
            fun failItem(item: MediaItem, reason: String?, kind: BackupFailureKind? = null): Boolean {
                failed++
                lastFailureReason = reason
                lastFailureKind = kind
                cursorFrozen = true
                consecutiveFailures++
                if (consecutiveFailures < MAX_CONSECUTIVE_BUCKET_FAILURES) return false
                // ⚠ `itemDone` because the caller breaks past the one at the end of the loop.
                itemDone(item)
                val dropped = items.drop(reachedInBucket)
                run.total -= dropped.size
                run.totalBytes -= dropped.sumOf { it.sizeBytes ?: 0L }
                return true
            }
            // Candidates hashed ahead of their turn, so the realm is asked about a batch at once
            // (§40). `null` = the bytes could not be read.
            val hashedAhead = mutableMapOf<String, String?>()
            for (item in items) {
                // ⚠ Before anything about this item happens — a yield is a FILE boundary: nothing
                // of this item reached Control, so the next drain offers it whole (§4.1b).
                if (shouldYield()) {
                    yielded = true
                    break@buckets
                }
                reachedInBucket++
                currentCoroutineContext().ensureActive()
                // "Off" must mean "stop", not "stop after this batch" — see [currentGate]. Both
                // re-reads are per ITEM, so a stop lands at a file boundary: at most one
                // upload finishes after the user acts, and no new one starts.
                when (val now = currentGate(settings, storage, ignore())) {
                    BackupGate.Allowed -> Unit
                    BackupGate.PausedByUser -> {
                        setStable(source, BackupState.PausedByUser)
                        return false
                    }
                    // Master switch off, or the environment moved off-policy (unplugged,
                    // dropped to cellular, filled up). setStable ENDS the run, so
                    // finishRuns cannot later paper over this with "Backed up N".
                    BackupGate.Disabled -> {
                        setStable(source, BackupState.Disabled)
                        return false
                    }
                    is BackupGate.Paused -> {
                        // Recounted rather than derived from this drain's own figures: the
                        // cursors have moved, so the enumeration is the honest remainder.
                        setStable(source, BackupState.Paused(now.reason, countWaiting(source)))
                        return false
                    }
                }
                // ⚠⚠ The gate does NOT cover the per-folder toggle: unticking "Camera" writes
                // the bucket SELECTION, which `evaluateBackupGate` never looks at. Re-reading
                // only the gate would leave that switch inert — the drain enumerates a snapshot
                // taken before the untick and would upload all of it.
                //
                // Deselecting one folder is not a reason to stop the others, so this breaks
                // the bucket rather than returning, and leaves the cursor where it is: the
                // folder is not backed up any more, so there is nothing to advance past.
                //
                // ⚠ Give back the items this bucket will now never process. The denominator
                // spans the whole RUN, so leaving them in would make the remaining
                // buckets count toward a total that includes files nobody is going to upload
                // — the same dishonest denominator the run accounting exists to prevent.
                if (bucketId !in source.readSelectedBuckets()) {
                    val dropped = items.drop(reachedInBucket - 1)
                    run.total -= dropped.size
                    run.totalBytes -= dropped.sumOf { it.sizeBytes ?: 0L }
                    break
                }
                // Already uploaded via an earlier selected bucket this drain. Treated
                // exactly like [BackupUploadOutcome.Skipped]: advance the cursor so the
                // bucket does not re-offer it forever, and count it toward neither
                // `uploaded` nor `failed`. The FIRST selected bucket wins, so which
                // folder an overlapping photo lands in follows the user's own selection
                // order rather than an invented rule.
                if (!seenUris.add(item.uri)) {
                    advanceCursor(item.cursor)
                    itemDone(item)
                    continue
                }
                // The cross-device content layer (`photos-app.md` §16). ⚠⚠ Ordered AFTER `seenUris` and the
                // per-bucket cursor on purpose — those two are free, this one reads the
                // candidate's bytes, so it must only ever run on an item the cheap layers have
                // already let through.
                //
                // ⚠ A null hash is "could not read", not "no match": we upload, and the upload
                // then fails with a reason the user can act on. Silently dropping an unreadable
                // candidate would look exactly like the skip working.
                if (duplicateView != null) {
                    if (item.uri !in hashedAhead) {
                        // ⚠ One realm walk per BATCH, not per candidate (§40): hash this item and
                        // the next few the drain will reach, then ask about all of them at once.
                        val batch = duplicateLookahead(items, reachedInBucket - 1, seenUris)
                        for (next in batch) hashedAhead[next.uri] = dataPlane.contentHashOf(next.uri)
                        duplicateView.prefetch(batch.mapNotNull { hashedAhead[it.uri] })
                    }
                    val hash = hashedAhead.remove(item.uri)
                    when (if (hash == null) ContentPresence.New else duplicateView.check(hash)) {
                        // Treated exactly like [BackupUploadOutcome.Skipped]: the cursor advances
                        // so the bucket stops re-offering it, and it counts toward neither
                        // `uploaded` nor `failed` — nothing was uploaded and nothing went wrong.
                        ContentPresence.Held -> {
                            // ⚠ The realm answered, so the streak of failures is broken — as it is
                            // by an upload or a data-plane skip. Without this, "could not look"
                            // alternating with "held" would stop the folder with no five in a row.
                            consecutiveFailures = 0
                            alreadyHeld++
                            advanceCursor(item.cursor)
                            itemDone(item)
                            continue
                        }
                        // ⚠⚠ The realm could not be read, so whether it holds this is unknown —
                        // and "unknown" is neither "skip" nor "upload" (§40). Uploading would
                        // silently re-bill every photo another device had already backed up, so
                        // it is a failure like any other: the cursor freezes here, the run's
                        // summary says why, and the next drain asks again.
                        ContentPresence.Unknown -> {
                            // ⚠⚠ Not handled, so not "seen": an iOS album is a view, and another
                            // selected album holding this asset must still offer it — else that
                            // album's cursor moves past it, and deselecting this album loses the
                            // photograph for good.
                            seenUris.remove(item.uri)
                            if (failItem(item, reason = null, kind = BackupFailureKind.COULD_NOT_CHECK_REALM)) break
                            itemDone(item)
                            continue
                        }
                        ContentPresence.New -> Unit
                    }
                }
                stateFlow.value = BackupState.BackingUp(
                    run.completed + completed,
                    run.total,
                    item.fileName,
                    run.remainingBytes(doneBytes),
                    userInitiated = userInitiated(),
                )
                if (!destinationEnsured) {
                    dataPlane.ensureBackupDestination(destination)
                    destinationEnsured = true
                }
                // ⚠⚠ Marked user-started only for a run the user asked for (*Back up now*): that is the
                // one case Control may hold its foreground service for. An automatic run's uploads stay
                // unmarked and ride Control's own jobs (control-background-work-off-fgs.md §5.1).
                val upload: suspend () -> BackupUploadOutcome =
                    { dataPlane.uploadForBackup(destination, item.fileName, item.uri) }
                when (val outcome = if (userInitiated()) userInitiatedWork(upload) else upload()) {
                    BackupUploadOutcome.Uploaded -> {
                        uploaded++
                        consecutiveFailures = 0
                        advanceCursor(item.cursor)
                    }
                    // Already at destination (§3.5) — advance past it, count neither.
                    BackupUploadOutcome.Skipped -> {
                        consecutiveFailures = 0
                        advanceCursor(item.cursor)
                    }
                    // ⚠⚠ Freeze the cursor, but keep draining. Stopping too would leave every file
                    // behind a single unuploadable one unreachable indefinitely (§8c).
                    is BackupUploadOutcome.Failed -> {
                        if (failItem(item, outcome.reason)) break
                    }
                }
                itemDone(item)
            }
        }
        } catch (cancellation: CancellationException) {
            // Stop, or an OS stop of the job. The figures survive; the files not reached stay
            // in the total until the next drain re-bases it (see the top of this function).
            foldIntoRun(interrupted = true)
            throw cancellation
        }
        // ⚠ No terminal state here: this drain may be one of several in a run, and
        // a `JustFinished` between two of them is exactly the "19 of 20 → 5 of 60" restart
        // the run accounting removes. [finishRuns] publishes it once the engine goes idle.
        foldIntoRun(interrupted = yielded)
        // Keep the bar at the run's own figures rather than leaving it on the last file's
        // frame — the next drain (if any) continues from exactly here.
        stateFlow.value =
            BackupState.BackingUp(run.completed, run.total, "", run.remainingBytes(0L), userInitiated())
        return yielded
    }

    /**
     * Publish the terminal state for every source whose run has ended, and forget the run.
     * Called once the drain loop finds no further trigger pending — i.e. at the first moment
     * the engine is genuinely idle (`drive-upload-progress.md` §14.3).
     */
    private fun finishRuns() {
        if (runs.isEmpty()) return
        for (source in sources) {
            val run = runs.remove(source.sourceId) ?: continue
            sourceStates.getValue(source.sourceId).value = BackupState.JustFinished(
                uploaded = run.uploaded,
                failed = run.failed,
                failureReason = run.failureReason,
                failureKind = run.failureKind,
                alreadyInRealm = run.alreadyInRealm,
            )
            scheduleClear(source)
        }
        runs.clear()
    }

    /**
     * One source's figures for the current **run** — every drain since the engine was last
     * idle, summed. See §14.3: a run is what the user perceives as "the backup that is
     * happening now", and it is not the same thing as a drain. A burst of media triggers
     * several drains (each with its own honest snapshot), and reporting them separately
     * would make the on-screen count restart with a new denominator part-way through.
     */
    private class RunAccumulator {
        var total: Int = 0
        var completed: Int = 0
        var uploaded: Int = 0
        var failed: Int = 0

        /** Files skipped because the realm already held their content — see [BackupState.JustFinished.alreadyInRealm]. */
        var alreadyInRealm: Int = 0

        /** Bytes behind [total], summed from the same enumeration. Meaningless while [bytesKnown]
         *  is false. */
        var totalBytes: Long = 0L

        /** Bytes of the items already handed to the data plane — the counterpart of [completed]. */
        var handedOffBytes: Long = 0L

        /**
         * Whether every item this run enumerated could be sized. Latches false, and stays false
         * for the rest of the run: a source that cannot size one item cannot state the run's
         * remaining bytes, and a partial sum presented as a total is exactly the kind of
         * plausible-but-short number the byte figure must never be.
         */
        var bytesKnown: Boolean = true

        /**
         * The last drain of this run stopped before reaching every file it enumerated — a yield or
         * a cancellation — so [total] still counts files nobody is processing. The next drain
         * re-bases on its own enumeration ([drainSourceInner]) and clears this.
         */
        var interrupted: Boolean = false

        /**
         * What this run has left to hand off, counting [extraHandedOff] bytes from a drain still
         * in flight; `null` when the run cannot say.
         *
         * ⚠ **Not "bytes left to upload".** An item leaves this figure when it is handed to the
         * data plane, which is a local write plus a journalled intent — the relay upload happens
         * afterwards, on the proxy's scheduler. The rest of the answer is the proxy's own
         * `pendingBytes` (the journal), and the banner adds the two: an item leaves this set
         * exactly as it enters that one, so the sum double-counts nothing. See
         * `SyncBanner.evaluate`.
         */
        fun remainingBytes(extraHandedOff: Long): Long? =
            if (!bytesKnown) null
            else (totalBytes - handedOffBytes - extraHandedOff).coerceAtLeast(0L)

        /** Why the last failure in this run failed — surfaced with the terminal summary. */
        var failureReason: String? = null

        /** The typed cause of the last failure, when the engine knows it — see [BackupFailureKind]. */
        var failureKind: BackupFailureKind? = null
    }

    /** Live run figures per `sourceId`; an absent entry means no run is in flight. */
    private val runs = mutableMapOf<String, RunAccumulator>()

    /** Set a non-transient state for [source] and cancel its pending JustFinished→UpToDate clear. */
    private fun setStable(source: BackupSource, state: BackupState) {
        clearJobs[source.sourceId]?.cancel()
        // ⚠ A stable state ENDS the run: disabled, paused off-policy or up-to-date all mean
        // this source is not backing up, and leaving the accumulator behind would let
        // [finishRuns] overwrite that state with a stale "Backed up N" summary — or worse,
        // fold the next run's figures into the abandoned one.
        runs.remove(source.sourceId)
        sourceStates.getValue(source.sourceId).value = state
    }

    private fun scheduleClear(source: BackupSource) {
        clearJobs[source.sourceId]?.cancel()
        clearJobs[source.sourceId] = scope.launch {
            delay(SUMMARY_MS)
            val stateFlow = sourceStates.getValue(source.sourceId)
            if (stateFlow.value is BackupState.JustFinished) {
                stateFlow.value = BackupState.UpToDate
            }
        }
    }

    private companion object {
        const val SUMMARY_MS = 5000L
    }
}

/**
 * Fold the per-source [BackupState]s into the single public state. A source
 * actively backing up dominates (its progress is summed); else a just-finished
 * summary; else any pause; else up-to-date; else disabled. For a single source
 * this is the identity.
 */
fun aggregateState(states: List<BackupState>): BackupState {
    if (states.isEmpty()) return BackupState.Disabled

    val backing = states.filterIsInstance<BackupState.BackingUp>()
    if (backing.isNotEmpty()) {
        return BackupState.BackingUp(
            completed = backing.sumOf { it.completed },
            total = backing.sumOf { it.total },
            currentFileName = backing.first().currentFileName,
            // ⚠ All or nothing, the same rule one run applies to one unsizable item: summing the
            // sources that CAN size their items would report a gallery's bytes as though they were
            // the whole backup's, while a document tree uploaded alongside it contributed none.
            remainingBytes = backing.map { it.remainingBytes }
                .takeIf { sizes -> sizes.all { it != null } }
                ?.sumOf { it ?: 0L },
            userInitiated = backing.any { it.userInitiated },
        )
    }
    val justFinished = states.filterIsInstance<BackupState.JustFinished>()
    if (justFinished.isNotEmpty()) {
        return BackupState.JustFinished(
            uploaded = justFinished.sumOf { it.uploaded },
            failed = justFinished.sumOf { it.failed },
            // The first source that has one. Sources fail independently, but the badge carries a
            // single sentence, and an arbitrary-but-real reason beats none.
            failureReason = justFinished.firstOrNull { it.hasFailureCause }?.failureReason,
            failureKind = justFinished.firstOrNull { it.hasFailureCause }?.failureKind,
            alreadyInRealm = justFinished.sumOf { it.alreadyInRealm },
        )
    }
    // ⚠ Ahead of the environment pauses: Stop is device-wide, so if any source reports it, that is
    // the sentence the whole badge should carry — and it is the one with a control attached.
    if (states.any { it is BackupState.PausedByUser }) return BackupState.PausedByUser
    val paused = states.filterIsInstance<BackupState.Paused>()
    // One gate verdict serves every source, so the reasons agree; the count is what differs.
    paused.firstOrNull()?.let { return it.copy(waiting = paused.sumOf { p -> p.waiting }) }
    if (states.any { it is BackupState.UpToDate }) return BackupState.UpToDate
    return BackupState.Disabled
}

/** The status code + pending count Drive reports to the ingest registry (§4). */
data class BackupStatusReport(val status: String, val pendingCount: Long)

/**
 * Pure map from the in-app [BackupState] to the registry's `IngestSource` status
 * code + pending count (§4). Desktop-unit-tested independently of the loop. The
 * controller pushes a report only when its [BackupStatusReport.status] changes,
 * so live per-file count churn during a run does not emit a sync block per photo.
 */
fun backupStatusFor(state: BackupState): BackupStatusReport = when (state) {
    BackupState.Disabled -> BackupStatusReport(IngestSource.STATUS_IDLE, 0)
    // ⚠ STATUS_PAUSED_POLICY, same as the environment pauses: from Control's registry view the
    // fact is "this source is not draining and that is intentional". The distinction between the
    // two kinds of pause is a sentence for the person looking at the phone, not a status code.
    BackupState.PausedByUser -> BackupStatusReport(IngestSource.STATUS_PAUSED_POLICY, 0)
    BackupState.UpToDate -> BackupStatusReport(IngestSource.STATUS_UP_TO_DATE, 0)
    is BackupState.BackingUp ->
        BackupStatusReport(IngestSource.STATUS_RUNNING, (state.total - state.completed).toLong().coerceAtLeast(0))
    is BackupState.Paused -> when (state.reason) {
        BackupPauseReason.NO_STORAGE, BackupPauseReason.OUT_OF_SPACE ->
            BackupStatusReport(IngestSource.STATUS_OUT_OF_SPACE, 0)
        BackupPauseReason.NO_NETWORK, BackupPauseReason.METERED_NETWORK, BackupPauseReason.NOT_CHARGING ->
            BackupStatusReport(IngestSource.STATUS_PAUSED_POLICY, 0)
    }
    // ⚠ A run that ended with failures is not up to date. The registry row is what a second device
    // — or anyone reading the store — sees, and `UP_TO_DATE` there would hide the failure (§8c).
    is BackupState.JustFinished ->
        if (state.failed > 0) BackupStatusReport(IngestSource.STATUS_ERROR, 0)
        else BackupStatusReport(IngestSource.STATUS_UP_TO_DATE, 0)
}

/** The flattened **global** backup settings the gate reads (§4.2). Per-source
 *  bucket / video selection lives on each [BackupSource]. */
data class BackupSettings(
    val enabled: Boolean,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    /**
     * The user pressed Stop (`drive-upload-stop-and-reclamation.md` §3.7). ⚠ Defaulted so
     * callers/tests that omit it read as "not paused".
     */
    val pausedByUser: Boolean = false,
)

/**
 * Observable backup state for the settings footer + the Android FGS notification
 * (§5 step 6). [Disabled]/[Paused] hold no FGS; [BackingUp] is the in-flight
 * anchor; [JustFinished] is the brief terminal summary that auto-clears to
 * [UpToDate].
 */
sealed interface BackupState {
    data object Disabled : BackupState
    /**
     * Stopped by the user (`drive-upload-stop-and-reclamation.md` §3.7) — resumable, and distinct
     * from every [Paused] reason.
     */
    data object PausedByUser : BackupState
    data object UpToDate : BackupState
    data class BackingUp(
        val completed: Int,
        val total: Int,
        val currentFileName: String,
        /**
         * Bytes this run has still to hand to the upload data plane, or `null` when at least one
         * enumerated item could not be sized (`drive-upload-progress.md` §14.4).
         *
         * ⚠ Defaulted, so a caller or test that omits it reads as "cannot say" — which is the
         * honest value for a source that does not populate [MediaItem.sizeBytes], and leaves the
         * banner without a byte figure rather than with a wrong one.
         */
        val remainingBytes: Long? = null,
        /**
         * Whether this frame belongs to a run the user started (*Back up now*) — the one thing the
         * Android foreground service may carry (`drive-synced-folders-phase2.md` §4.1a).
         * ⚠ In the value, not beside it: see [SyncedFolderController.userRequest]. Defaulted to
         * `false`, the automatic reading, so no caller can claim a user run by omission.
         */
        val userInitiated: Boolean = false,
    ) : BackupState
    data class Paused(
        val reason: BackupPauseReason,
        /**
         * Items the pause is holding back — see `countWaiting` for why this is an upper bound.
         * ⚠ Defaulted to `0`, which renders as the bare reason: the honest reading for a caller
         * that did not count.
         */
        val waiting: Int = 0,
    ) : BackupState
    data class JustFinished(
        val uploaded: Int,
        val failed: Int,
        /** Why the last failure failed. ⚠ Nullable: [failed] `> 0` and "we know why" are separate,
         *  and a data plane that reports a bare failure is still allowed to exist. */
        val failureReason: String? = null,
        /**
         * The typed cause, when it is one the engine itself knows (§40) — each platform renders it
         * from its own string catalogue. ⚠ At most one of this and [failureReason] is set; the
         * free text stays for what a data plane reports.
         */
        val failureKind: BackupFailureKind? = null,
        /**
         * Files skipped because the realm already holds their content (`photos-app.md` §16) —
         * neither [uploaded] nor [failed]. ⚠⚠ Shown, not dropped: a run whose every file was
         * already in the realm otherwise summarises as "Backed up 0 files" over an empty
         * destination, which is indistinguishable from backup being broken.
         * ⚠ Defaulted to `0`, the reading for a caller that did not count.
         */
        val alreadyInRealm: Int = 0,
    ) : BackupState {
        /** Whether either the free-text reason or the typed cause is known. */
        val hasFailureCause: Boolean get() = failureReason != null || failureKind != null
    }
}

/**
 * A backup failure whose cause the engine knows — so its copy lives in each platform's catalogue,
 * the way [BackupPauseReason]'s does, rather than reaching the user as an English sentence.
 */
enum class BackupFailureKind {
    /**
     * The "already backed up?" skip could not read the realm, so the candidates were deferred
     * rather than uploaded or dropped (`photos-app.md` §40). The next drain asks again.
     */
    COULD_NOT_CHECK_REALM,
}

/** Why the loop is paused — drives the §5 footer + §3.6/§3.8 gate copy. */
enum class BackupPauseReason { NO_STORAGE, OUT_OF_SPACE, NO_NETWORK, METERED_NETWORK, NOT_CHARGING }

/** Result of the pre-drain gate (§3.6 storage + §3.8 network/charging). */
sealed interface BackupGate {
    data object Disabled : BackupGate
    data object Allowed : BackupGate
    data class Paused(val reason: BackupPauseReason) : BackupGate

    /**
     * ⚠ Deliberately NOT a [BackupPauseReason]. Every member of that enum is a condition the
     * *device* is in and the user can only wait out; this one is a decision the user made and can
     * reverse. Folding it in would let one `when` branch render "Paused — you stopped this"
     * beside "Paused — no network" as if the remedy were the same.
     */
    data object PausedByUser : BackupGate
}

/**
 * Pure pre-drain gate (§3.6 + §3.8): storage is a **hard** gate, then the
 * network/charging constraints. Returns the specific [BackupPauseReason] so the
 * footer can explain *why* nothing is uploading. Desktop-unit-tested independently
 * of any Android runtime.
 */
fun evaluateBackupGate(
    settings: BackupSettings,
    storage: StorageStatusModel?,
    network: BackupNetworkStatus,
    charging: Boolean,
): BackupGate {
    if (!settings.enabled) return BackupGate.Disabled
    // ⚠ Before the environment checks, not after: a user who pressed Stop is owed "you stopped
    // this", not "out of space". Both may be true; only one is actionable by them.
    if (settings.pausedByUser) return BackupGate.PausedByUser
    if (storage == null || !storage.hasStorage) return BackupGate.Paused(BackupPauseReason.NO_STORAGE)
    if (storage.isFull) return BackupGate.Paused(BackupPauseReason.OUT_OF_SPACE)
    if (network == BackupNetworkStatus.NONE) return BackupGate.Paused(BackupPauseReason.NO_NETWORK)
    if (settings.wifiOnly && network != BackupNetworkStatus.UNMETERED) {
        return BackupGate.Paused(BackupPauseReason.METERED_NETWORK)
    }
    if (settings.chargingOnly && !charging) return BackupGate.Paused(BackupPauseReason.NOT_CHARGING)
    return BackupGate.Allowed
}

/**
 * The realm destination folder for a backed-up file (§4.5): a fixed
 * `/Backup/<device>/<bucket>` root so two devices' "Camera" don't merge and the
 * layout is greppable. Segments are sanitised (path separators → `_`) so a bucket
 * display name can't escape the root.
 *
 * ⚠ The root comes from [BACKUP_ROOT] rather than a literal, because Drive is not the only
 * component that has to recognise it: `photosCapturedBetween` reads "is this file under the backup
 * root" as its provenance signal (`timeline-photos.md` T-P2). A convention two modules must agree
 * on and neither owns is the join that degrades silently when one side changes it.
 */
fun backupDestinationFolder(deviceLabel: String, bucketName: String): String =
    "$BACKUP_ROOT/${sanitizeSegment(deviceLabel)}/${sanitizeSegment(bucketName)}"

private fun sanitizeSegment(segment: String): String =
    segment.replace('/', '_').replace('\\', '_').trim().ifEmpty { "unknown" }
