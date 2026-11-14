package org.tenshu.sdk.ios

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import app.tenshu.ingest.DeviceIdentity
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.drive.data.FilesRepository
import app.tenshu.ingest.backup.MediaAccess
import app.tenshu.ingest.backup.TenshuBackupPreferences
import app.tenshu.ingest.backup.BackupFailureKind
import app.tenshu.ingest.backup.BackupPauseReason
import app.tenshu.ingest.backup.BackupState
import app.tenshu.ingest.backup.NewFolderDecisions
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.ingest.backup.TenshuDuplicateContentGate
import app.tenshu.ingest.IngestSettingsStore
import app.tenshu.ingest.backup.galleryBackupSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.tenshu.core.common.TenshuClient
import org.tenshu.sync.common.SyncActivity

/**
 * iOS Synced-Folders (photo auto-backup) surface (ios-drive-parity.md P6b) — the "reframe, not
 * port" of the Android auto-backup.
 *
 * The backup ENGINE is unchanged: this wires the iOS PhotoKit source
 * ([IosMediaSourceProvider]) + the iOS environment ([IosBackupEnvironment]) into the SHARED,
 * source-generic `SyncedFolderController` (commonMain) over the commonMain `TenshuBackupPreferences`
 * — no engine change, exactly as Android/desktop plug in their own sources. Uploads ride
 * `FilesRepository.uploadForBackup`, which stages + enqueues the sealed chunks on the
 * background `URLSession` (survives suspension).
 *
 * It exposes the Swift Settings controls: enable/disable, live [observeStatus], and a foreground
 * [backupNow] catch-up — the reliable core of the honest "open the app to finish" iOS UX (iOS
 * grants no guaranteed background wake; the `BGAppRefresh` wake also nudges a drain, best-effort).
 *
 * ⚠ Photo-library authorization is requested Swift-side before [setEnabled]; enumeration /
 * change-detection / upload against a real library is verified only on a device
 * (`ios-drive-parity.md` P7). There is no live "new photo → auto-drain" trigger (a
 * `PHPhotoLibraryChangeObserver`): [IosMediaSourceProvider.observeChanges] is `emptyFlow()`, and
 * the foreground catch-up + BG wake drive drains.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object DriveBackupSurface {

    @kotlin.concurrent.Volatile
    private var controller: SyncedFolderController? = null

    /** The controller's own scope, so a stopped graph's loop ends with it (`ios-shared-store-sqlite.md` B.16 (228)). */
    @kotlin.concurrent.Volatile
    private var controllerScope: CoroutineScope? = null

    /** The client the controller writes through: what [detachIfOver] compares. */
    @kotlin.concurrent.Volatile
    private var controllerClient: TenshuClient? = null

    /**
     * The same controller as a flow, so an observer built **before** [start] still sees the loop
     * once it exists.
     *
     * ⚠ `controller?.state ?: flowOf(…)` reads the field at *call* time and then never looks
     * again — a subscriber that arrives first is pinned to the placeholder for the process's
     * lifetime. Installation and the first Settings render are ordered in practice, which is why
     * that never showed, but the fix is one flow and it removes the ordering assumption entirely.
     */
    private val controllerFlow = MutableStateFlow<SyncedFolderController?>(null)
    private var preferences: BackupPreferences? = null
    private var provider: IosMediaSourceProvider? = null
    /** ⚠ B.17 (282): read by an answer's entry off the thread that set it. */
    @kotlin.concurrent.Volatile
    private var decisions: NewFolderDecisions? = null
    private var promptBridge: NewFolderPromptBridge? = null
    private val mediaAccess = IosMediaAccessProvider()

    /**
     * Build + start the in-process backup loop over the installed runtime's client + repo.
     * Idempotent; called from [DriveLocalRuntime.install]. The loop gates on `enabled` (default
     * false), so nothing is scanned or uploaded until the user turns it on in Settings.
     */
    fun start(client: TenshuClient, filesRepository: FilesRepository) {
        if (controller != null && controllerClient === client) return
        // ⚠ A loop over another graph's client is stale whatever
        // stopped it, or failed to: it is ended and replaced, never kept.
        controllerClient?.let(::detachIfOver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = TenshuBackupPreferences(client)
        val mediaProvider = IosMediaSourceProvider(
            userLibrarySelected = {
                USER_LIBRARY_BUCKET_ID in prefs.readSelectedBuckets()
            },
        )
        val ctrl = SyncedFolderController(
            preferences = prefs,
            sources = listOf(galleryBackupSource(mediaProvider, prefs)),
            dataPlane = filesRepository,
            environment = IosBackupEnvironment(),
            announcer = IosNewFolderAnnouncer { promptBridge },
            mediaAccess = mediaAccess,
            // PH-6: skip content the realm already holds, per the shared device setting.
            duplicates = TenshuDuplicateContentGate(client, IngestSettingsStore(client)),
            scope = scope,
        )
        ctrl.start()
        preferences = prefs
        provider = mediaProvider
        decisions = NewFolderDecisions(prefs)
        controller = ctrl
        controllerScope = scope
        controllerClient = client
        controllerFlow.value = ctrl
        // B.16 (237), (243): an answer that arrived while no graph served is applied by this one,
        // and dropped only once applied.
        replayKeptAnswers()
    }

    /**
     * ⚠ B.17 (265): the kept answers applied by the graph serving now, if
     * any are kept. At this graph's [start], **and at each foreground while it serves**
     * (`onCurrentGraphForeground`): an apply that got no protected time, or a write the store
     * refused, would otherwise wait for the next graph, which a healthy graph never gives way to.
     * Only dispatches (the foreground hook runs inside the notification). Nothing with no graph.
     */
    internal fun replayKeptAnswers() {
        if (keptAnswerCount() == 0) return
        val d = decisions ?: return
        val scope = controllerScope ?: return
        scope.launch { applyKeptAnswers(d) }
    }

    /**
     * ⚠⚠ (B.16 (243)) The kept answers, applied under an entry and under
     * [answersMutex], read **at apply time** (a live answer since removed its bucket's), each
     * removed only once it is applied. No time granted, an expiry, or this graph's scope cancelled
     * first: what is not applied stays kept for the next graph's start.
     */
    private suspend fun applyKeptAnswers(d: NewFolderDecisions) {
        protect {
            answersMutex.withLock { applyKeptLocked(d) }
            true
        } ?: println("[drive-backup] ⚠ the kept new-folder answers got no protected time: still kept")
    }

    /**
     * The kept answers, each removed only once its write succeeded: over a not-ready stand-in, or a
     * store that refuses it, the answer stays kept for the next graph. Under [answersMutex].
     */
    private suspend fun applyKeptLocked(d: NewFolderDecisions) {
        keptSnapshot().forEach { (bucket, accept) ->
            if (applied(d, bucket, accept)) removeKeptIf(bucket, accept)
        }
    }

    /** One answer's preference write; false (logged) when it failed. A cancellation is rethrown. */
    private suspend fun applied(d: NewFolderDecisions, bucketId: String, accept: Boolean): Boolean = try {
        writeAnswer(d, bucketId, accept)
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        println("[drive-backup] ⚠ a new-folder answer ($bucketId, accept=$accept) could not be written (${t::class.simpleName}): kept")
        false
    }

    /**
     * ⚠⚠ B.16 (228): the graph whose client [client] is was stopped by
     * the shared store's host. The loop built over it is cancelled (its scope), and the controller,
     * preferences, provider and decisions over that graph are dropped, so the rebuild's [start]
     * builds them over the new graph; until then `backupNow()` answers false rather than running
     * the stopped graph's loop. The user's settings are rows of the store, not of this object, so
     * nothing is lost. A controller over another client is left alone.
     */
    internal fun detachIfOver(client: TenshuClient) {
        if (controllerClient !== client) return
        controllerScope?.cancel()
        controllerScope = null
        controllerClient = null
        controller = null
        controllerFlow.value = null
        preferences = null
        provider = null
        decisions = null
    }

    /** Test seam: the live controller. */
    internal fun controllerOrNull(): SyncedFolderController? = controller

    /** Test seam: the live controller's scope. */
    internal fun controllerScopeOrNull(): CoroutineScope? = controllerScope

    /**
     * Register the Swift new-folder prompt surface
     * (`drive-ios/Sources/NewFolderPrompt.swift`). Called once at app launch, ordering-independent
     * with [start] — the announcer reads this slot lazily, so a prompt raised before installation
     * is simply not shown (and the album stays recorded as known, i.e. "announced and ignored").
     */
    fun installNewFolderPromptBridge(bridge: NewFolderPromptBridge) {
        promptBridge = bridge
    }

    /**
     * Apply a prompt answer from the notification's inline actions.
     * [accept] adds the album to the backed-up selection; declining only records it, so it is
     * never asked about again. Both are idempotent.
     */
    suspend fun answerNewFolderPrompt(bucketId: String, accept: Boolean) {
        if (!switched()) {
            // With no binding installed: applied directly, no entry token.
            val d = decisions ?: return
            if (accept) d.accept(bucketId) else d.dismiss(bucketId)
            return
        }
        // ⚠ B.15: a notification action can run with Drive in the background (the lease
        // draft's §5.4 row): the decision's store write under an entry token.
        // ⚠⚠ (B.16 (237)) The decisions are looked up INSIDE the entry,
        // after its retry: a stopped graph's were dropped (228), and the entry's rebuild is what
        // brings the new graph's. Never the stopped graph's, and never a silent loss: an answer
        // with no graph to take it is kept, loudly, and applied when the next one starts.
        // ⚠⚠ B.17 (266): which realm's graph this answer was made for. A graph given up meanwhile
        // dropped the kept answers (244), and this one must not outlive that drop into the next
        // realm: every keep below is refused once the drop generation has moved.
        val madeFor = dropGenerationNow()
        val handled = try { protect {
            answersMutex.withLock {
                // ⚠ (243) A live answer supersedes a kept one for its bucket: an older kept answer
                // must never overwrite it.
                removeKept(bucketId)
                val d = decisions
                if (d == null) {
                    println("[drive-backup] ⚠ a new-folder answer ($bucketId, accept=$accept) arrived with no serving graph: kept, applied when the next one starts")
                    keepAnswer(bucketId, accept, madeFor)
                } else {
                    // ⚠ B.17 (265): a graph that takes this answer takes the others kept too
                    // (this bucket's was removed above, so the live answer is the one written).
                    applyKeptLocked(d)
                    if (!applied(d, bucketId, accept)) {
                        // ⚠ (243) A failed write keeps the answer too.
                        keepAnswer(bucketId, accept, madeFor)
                    }
                }
            }
            true
        } } catch (e: kotlinx.coroutines.CancellationException) {
            // ⚠ (243) The caller cancelled mid-answer: kept, never lost (re-applying is idempotent).
            keepAnswer(bucketId, accept, madeFor)
            throw e
        }
        if (handled == null) {
            // ⚠⚠ (243) No protected time, or it expired: kept, never lost.
            println("[drive-backup] ⚠ a new-folder answer ($bucketId, accept=$accept) got no protected time: kept, applied when the next graph starts")
            keepAnswer(bucketId, accept, madeFor)
        }
    }

    /** The entry an answer runs under; null when no time was granted or it expired. A test seam. */
    internal var protect: suspend (block: suspend () -> Boolean) -> Boolean? =
        { block -> SharedReplicaEntries.withProtection("drive-new-folder-prompt") { block() } }

    /** One answer's preference write. A test seam. */
    internal var writeAnswer: suspend (NewFolderDecisions, String, Boolean) -> Unit =
        { d, bucketId, accept -> if (accept) d.accept(bucketId) else d.dismiss(bucketId) }

    /** Serialises live answers and the kept answers' apply (243). */
    private val answersMutex = Mutex()

    /**
     * ⚠ (B.16 (244)) The graph the kept answers waited for was given up with no rebuild to follow
     * (a deprovision, a corrupt store, a latch): they belong to that realm, and are dropped rather
     * than applied to whatever is provisioned next. Called from the graph's abandon hook.
     */
    internal fun dropKeptAnswers() {
        val dropped = run {
            answersLock.lock()
            try {
                dropGeneration++
                keptAnswers.size.also { keptAnswers.clear() }
            } finally {
                answersLock.unlock()
            }
        }
        if (dropped > 0) println("[drive-backup] $dropped kept new-folder answer(s) dropped: their graph was given up")
    }

    /** Whether the SQLite switch has a binding installed in this process. A test seam. */
    internal var switched: () -> Boolean = { SharedReplicaEntries.isInstalled }

    private val answersLock = platform.Foundation.NSRecursiveLock()

    /** One kept answer per bucket, the latest (243). */
    private val keptAnswers = LinkedHashMap<String, Boolean>()

    /** B.17 (266): moved by every [dropKeptAnswers]; an answer is kept only under the generation it was made in. */
    private var dropGeneration = 0L

    private fun dropGenerationNow(): Long {
        answersLock.lock()
        try {
            return dropGeneration
        } finally {
            answersLock.unlock()
        }
    }

    private fun keepAnswer(bucketId: String, accept: Boolean, madeFor: Long) {
        val kept = run {
            answersLock.lock()
            try {
                // ⚠⚠ (266) Its graph was given up since the answer began: it belongs to that realm.
                if (dropGeneration != madeFor) return@run false
                keptAnswers.remove(bucketId)
                keptAnswers[bucketId] = accept
                true
            } finally {
                answersLock.unlock()
            }
        }
        if (!kept) println("[drive-backup] a new-folder answer ($bucketId, accept=$accept) is dropped: its graph was given up while it ran")
    }

    private fun keptSnapshot(): List<Pair<String, Boolean>> {
        answersLock.lock()
        try {
            return keptAnswers.entries.map { it.key to it.value }
        } finally {
            answersLock.unlock()
        }
    }

    private fun removeKept(bucketId: String) {
        answersLock.lock()
        try {
            keptAnswers.remove(bucketId)
        } finally {
            answersLock.unlock()
        }
    }

    /** Removes [bucketId]'s kept answer only if it is still [accept] (one kept since stays). */
    private fun removeKeptIf(bucketId: String, accept: Boolean) {
        answersLock.lock()
        try {
            if (keptAnswers[bucketId] == accept) keptAnswers.remove(bucketId)
        } finally {
            answersLock.unlock()
        }
    }

    /** Test seam: answers kept for the next graph. */
    internal fun keptAnswerCount(): Int {
        answersLock.lock()
        try {
            return keptAnswers.size
        } finally {
            answersLock.unlock()
        }
    }

    /**
     * The photo-library grant as a plain string for the Swift settings notice:
     * `"full"`, `"limited"`, or `"denied"`. Mirrors Android's media-access notice — under
     * `limited` PhotoKit silently scopes every fetch to the user's chosen assets, so backup
     * quietly covers a fraction of the library with nothing on screen to say so.
     *
     * Read live on every call, never cached: the grant can change in Settings while Drive is
     * backgrounded.
     */
    fun mediaAccessState(): String = when (mediaAccess.current()) {
        MediaAccess.FULL -> "full"
        MediaAccess.PARTIAL -> "limited"
        MediaAccess.DENIED -> "denied"
    }

    /**
     * Whether to prompt when a new album appears — the **new-album prompt's own**
     * device preference (`BackupNewFolders`), independent of the master backup switch
     * exactly as on Android. Turning it off never stops backup; it only stops the
     * notification.
     */
    fun observeAskAboutNewAlbums(): Flow<Boolean> =
        preferences?.observeBackupNewFolders() ?: flowOf(true)

    suspend fun setAskAboutNewAlbums(enabled: Boolean) {
        preferences?.setBackupNewFolders(enabled)
    }

    /** Whether photo auto-backup is on (durable device preference). */
    fun observeEnabled(): Flow<Boolean> = preferences?.observeEnabled() ?: flowOf(false)

    /** Live backup status as a ready-to-show string (mapping stays Kotlin-side — the Swift footer
     *  just displays it, no sealed-class bridging). */
    fun observeStatusText(): Flow<String> = observeBackupState().map { statusText(it) }

    /**
     * The drain's live state, resolved through [controllerFlow] so subscription order is irrelevant.
     *
     * ⚠ This state — not the proxy's upload-queue figures — is also what an iOS sync banner would
     * have to read for its "N of M", for the reason `drive-upload-progress.md` §14.2 records. There
     * is no banner on iOS today and deliberately no wiring for one here: [statusText] below already
     * renders these same figures, which is why the reported jumping denominator never appeared on
     * this platform at all.
     */
    private fun observeBackupState(): Flow<BackupState> =
        controllerFlow.flatMapLatest { it?.state ?: flowOf(BackupState.Disabled) }

    /**
     * The run's count for the sync-activity capsule (`ios-sync-activity-indicator.md` §6a.1), or
     * `null` when no run is counting. Every state but `BackingUp` is `null`: paused, finished and
     * up to date are not activity, and what is still on its way after a run is the transfers in
     * flight, which this does not know.
     */
    internal fun observeUploadRun(): Flow<SyncActivity.Progress?> =
        observeBackupState().map { state ->
            (state as? BackupState.BackingUp)?.let { SyncActivity.Progress(completed = fileInHand(it), total = it.total) }
        }

    /**
     * Which file of the run is being sent: one past the files done, never past the total.
     *
     * ⚠⚠ One helper for the Settings footer and the capsule. They show the same run on the same
     * device, and two spellings of the number is how "Backing up 21 of 400" comes to sit beside
     * "Uploading 20 of 400".
     */
    internal fun fileInHand(state: BackupState.BackingUp): Int = (state.completed + 1).coerceAtMost(state.total)

    /**
     * The copy for a typed failure cause (`photos-app.md` §40). Drive iOS has no string table, and
     * every other line of [statusText] is written here too, so this is its catalogue; the wording
     * follows Drive's `synced_folders_status_finished_could_not_check`.
     */
    private fun failureText(kind: BackupFailureKind): String = when (kind) {
        BackupFailureKind.COULD_NOT_CHECK_REALM ->
            "couldn't check whether they're already backed up, will retry"
    }

    private fun statusText(state: BackupState): String = when (state) {
        BackupState.Disabled -> "Off"
        BackupState.UpToDate -> "Your photos are backed up"
        is BackupState.BackingUp -> "Backing up ${fileInHand(state)} of ${state.total}…"
        // ⚠ Copy matches Drive's own `synced_folders_status_paused_by_user` ("Paused — you stopped
        // backup") rather than inventing an iOS-only wording: this surface renders the same states
        // the settings screen does, and the two disagreeing is how a string drifts.
        BackupState.PausedByUser -> "Paused — you stopped backup"
        is BackupState.Paused -> when (state.reason) {
            BackupPauseReason.NO_STORAGE, BackupPauseReason.OUT_OF_SPACE -> "Paused — storage full"
            BackupPauseReason.NO_NETWORK -> "Paused — no connection"
            BackupPauseReason.METERED_NETWORK -> "Waiting for Wi-Fi"
            BackupPauseReason.NOT_CHARGING -> "Waiting to charge"
        } + when (state.waiting) {
            0 -> ""
            1 -> " · 1 file waiting"
            else -> " · ${state.waiting} files waiting"
        }
        // ⚠ The reason rides along, for the same reason it does on the settings screen (§8c).
        is BackupState.JustFinished ->
            if (state.failed > 0) {
                "Backed up ${state.uploaded}, ${state.failed} failed" +
                    (state.failureKind?.let { " — ${failureText(it)}" } ?: state.failureReason?.let { " — $it" }.orEmpty())
            } else if (state.alreadyInRealm > 0 && state.uploaded == 0) {
                "Nothing new to back up · ${state.alreadyInRealm} already in your Drive"
            } else {
                "Backed up ${state.uploaded} photo" + (if (state.uploaded == 1) "" else "s") +
                    (if (state.alreadyInRealm > 0) " · ${state.alreadyInRealm} already in your Drive" else "")
            }
    }

    /**
     * Turn photo backup on/off. Enabling also selects the photo-library bucket(s) (so the drain
     * has something to scan) and kicks a first foreground catch-up. Photo-library authorization is
     * requested Swift-side BEFORE this is called; an unauthorized library selects no buckets.
     */
    suspend fun setEnabled(enabled: Boolean) {
        val prefs = preferences ?: return
        if (enabled) {
            // Select ONLY the user library, never the albums that listBuckets may also return:
            // an album is a view over the same assets, so selecting both double-uploads every
            // album member (see IosMediaSourceProvider.userLibrarySelected).
            prefs.setSelectedBuckets(listOf(USER_LIBRARY_BUCKET_ID))
        }
        prefs.setEnabled(enabled)
        if (enabled) controller?.ingestNow()
    }

    /**
     * Foreground "Back up now" catch-up: run one gated drain pass inline, returning whether it ran
     * (`false` only when this device's sync identity isn't resolved yet). The reliable path when
     * iOS grants no timely background wake.
     */
    suspend fun backupNow(): Boolean = controller?.ingestAndAwait() ?: false

    // ── Backup scope (per-album selection) ───────────────────────────────────────
    //
    // The scope is EXCLUSIVE by construction, not by preference — see
    // [BackupScopeSelection] for why, and for the rules themselves. Modelling it as a
    // two-way mode choice rather than a flat multi-select makes that constraint the
    // shape of the UI instead of a rule the UI must remember not to break.
    //
    // Picking "Selected albums" is also what makes the new-album prompt live: albums
    // only become enumerable — hence diffable — once All Photos is deselected.

    /** Whether backup is in "All Photos" mode (the default) rather than per-album. */
    suspend fun isAllPhotosScope(): Boolean {
        val prefs = preferences ?: return true
        return USER_LIBRARY_BUCKET_ID in prefs.readSelectedBuckets()
    }

    /**
     * The live selection, as the UI's single source of truth.
     *
     * ⚠ Read REACTIVELY, never by polling `first()` after a write. A preference write
     * lands via the store's write mailbox, so an immediate re-read of a fresh cold flow
     * can still observe the pre-write value — an earlier revision "verified" writes that
     * way and reported failures for writes that were landing perfectly well. This
     * mirrors `SyncedFoldersViewModel`, which has always collected the flow.
     */
    fun observeIsAllPhotosScope(): Flow<Boolean> =
        (preferences?.observeSelectedBuckets() ?: flowOf(BackupScopeSelection.allPhotos()))
            .map { BackupScopeSelection.isAllPhotos(it) }

    /** The selected album ids (the selection minus the user-library sentinel). */
    fun observeSelectedAlbumIds(): Flow<List<String>> =
        (preferences?.observeSelectedBuckets() ?: flowOf(emptyList()))
            .map { BackupScopeSelection.albumIds(it) }

    /** Switch to "All Photos": one bucket, everything, including future photos. */
    suspend fun setAllPhotosScope() {
        val prefs = preferences ?: return
        prefs.setSelectedBuckets(BackupScopeSelection.allPhotos())
        controller?.ingestNow()
    }

    /**
     * Switch to per-album mode, keeping any albums already chosen. Dropping the user
     * library is what makes albums enumerable; until at least one album is selected
     * this backs up nothing, which the picker says out loud.
     */
    suspend fun setAlbumScope() {
        val prefs = preferences ?: return
        prefs.setSelectedBuckets(
            BackupScopeSelection.albumsOnly(prefs.readSelectedBuckets())
        )
    }

    /**
     * Every album on the device with its current selection state — the picker's model.
     * Read unconditionally (not through the engine's guarded [IosMediaSourceProvider.
     * listBuckets]) so albums are visible while All Photos is still selected.
     */
    suspend fun listAlbums(): List<BackupAlbumOption> {
        val prefs = preferences ?: return emptyList()
        val selected = prefs.readSelectedBuckets().toSet()
        return provider?.listAlbums()?.map { album ->
            BackupAlbumOption(
                id = album.id,
                name = album.displayName,
                itemCount = album.itemCount,
                selected = album.id in selected,
            )
        } ?: emptyList()
    }

    /**
     * Add/remove one album. Implies per-album mode — selecting an album while All Photos
     * is still on would be the double-upload case, so the library is dropped.
     *
     * The result is observed through [observeSelectedAlbumIds], not returned — see the
     * note there on why reading a write back immediately is not a valid check.
     */
    suspend fun setAlbumSelected(albumId: String, selected: Boolean) {
        val prefs = preferences ?: return
        prefs.setSelectedBuckets(
            BackupScopeSelection.withAlbum(
                current = prefs.readSelectedBuckets(),
                albumId = albumId,
                selected = selected,
            )
        )
        if (selected) controller?.ingestNow()
    }

    /**
     * Whether this device's sync identity has resolved. Every backup preference is
     * DEVICE-scoped, so until this is true no selection can be stored at all — the
     * picker says so rather than silently discarding taps.
     */
    suspend fun isDeviceIdentityResolved(): Boolean = preferences?.deviceLabel() != null

    /** The user-library bucket id, shared with [IosMediaSourceProvider]. */
    internal const val USER_LIBRARY_BUCKET_ID = "phasset.userLibrary"
}

/** One row in the album picker. */
data class BackupAlbumOption(
    val id: String,
    val name: String,
    val itemCount: Int,
    val selected: Boolean,
)
