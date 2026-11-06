package app.tenshu.drive.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.BackupFolderGrants
import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.drive.data.FilesRepository
import app.tenshu.ingest.backup.FullMediaAccessProvider
import app.tenshu.ingest.backup.MediaAccess
import app.tenshu.ingest.backup.MediaAccessProvider
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.ingest.backup.BackupConstraint
import app.tenshu.ingest.backup.BackupState
import app.tenshu.ingest.backup.GALLERY_SOURCE_ID
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.ingest.backup.documentTreeDisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * Backs the Synced Folders settings screen (`docs/proposals/drive-synced-folders.md`
 * §5). Reads the per-device backup settings + the candidate buckets, mirrors the
 * controller's live [BackupState] for the footer, and writes user changes back
 * through [BackupPreferences] — the controller observes the **same** preferences
 * and reacts, so the screen never drives the loop directly.
 *
 * Default-on rule (§3.7): the first time the master switch is enabled with no
 * prior selection, every detected (image) bucket defaults selected.
 */
class SyncedFoldersViewModel(
    private val controller: SyncedFolderController,
    private val preferences: BackupPreferences,
    private val mediaSource: MediaSourceProvider,
    private val filesRepository: FilesRepository,
    private val backupFolderGrants: BackupFolderGrants,
    private val mediaAccess: MediaAccessProvider = FullMediaAccessProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SyncedFoldersUiState(canBackUpNow = controller.runsDrainInProcess),
    )
    val uiState: StateFlow<SyncedFoldersUiState> = _uiState.asStateFlow()

    private var selectedIds: Set<String> = emptySet()
    private var includeVideo = false

    init {
        viewModelScope.launch {
            preferences.observeEnabled().collect { enabled ->
                _uiState.update { it.copy(enabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.observeSelectedBuckets().collect { ids ->
                selectedIds = ids.toSet()
                // The desktop MAS picker adds a grant that was not present in the last
                // bucket snapshot. Reload the provider so the new row appears immediately.
                refreshBuckets()
                recomputeRows()
            }
        }
        viewModelScope.launch {
            preferences.observeGrantedBuckets().collect { ids ->
                _uiState.update { it.copy(grantedBucketIds = ids.toSet()) }
                // A grant can be removed while this screen is open; refresh the bucket
                // provider so the opaque row disappears without requiring navigation.
                refreshBuckets()
                recomputeRows()
            }
        }
        viewModelScope.launch {
            preferences.observeWifiOnly().collect { v -> _uiState.update { it.copy(wifiOnly = v) } }
        }
        viewModelScope.launch {
            preferences.observeChargingOnly().collect { v ->
                _uiState.update { it.copy(chargingOnly = v) }
            }
        }
        viewModelScope.launch {
            preferences.observeBackupNewFolders().collect { v ->
                _uiState.update { it.copy(backupNewFolders = v) }
            }
        }
        viewModelScope.launch {
            preferences.observeDocumentTreeUris().collect { uris ->
                _uiState.update {
                    it.copy(
                        documentFolders = uris.map { uri ->
                            DocumentFolderRow(uri = uri, name = documentTreeDisplayName(uri))
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            preferences.observeVideos().collect { v ->
                includeVideo = v
                _uiState.update { it.copy(videos = v) }
                refreshBuckets()
            }
        }
        viewModelScope.launch {
            filesRepository.storageStatus().collect { status ->
                _uiState.update { it.copy(hasStorage = status?.hasStorage ?: false) }
            }
        }
        viewModelScope.launch {
            controller.state.collect { state -> _uiState.update { it.copy(backupState = state) } }
        }
        refreshBuckets()
    }

    fun setEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enabled = enabled) }
        viewModelScope.launch {
            preferences.setEnabled(enabled)
            // §3.7: enabling with no prior selection defaults every bucket on.
            if (enabled && selectedIds.isEmpty()) {
                val all = mediaSource.listBuckets(includeVideo).map { it.id }
                if (all.isNotEmpty()) preferences.setSelectedBuckets(all)
            }
        }
    }

    /**
     * *Back up now*: run a pass immediately instead of waiting for the next media
     * change or the WorkManager job. Fire-and-forget — the controller conflates it with
     * any drain already in flight, and the footer reports what happened.
     *
     * ⚠ A tap is the one thing that makes a backup **user-initiated**, which is what lets
     * Android carry it in a foreground service (`drive-synced-folders-phase2.md` §4.1a).
     *
     * ⚠ It never silently ignores the user's own constraints. When Wi-Fi-only or
     * charging-only is the only thing in the way, it **asks** ([SyncedFoldersUiState.backUpNowConfirmation])
     * and lifts that constraint for this one run if the user agrees — the saved setting is
     * never touched. Anything else in the way (no connection, no storage) is not offered:
     * the pass is nudged and the footer explains, as before.
     */
    fun backUpNow() {
        viewModelScope.launch {
            val blockers = runCatchingCancellable { controller.overridableBlockersNow() }
                .getOrDefault(emptySet())
            if (blockers.isEmpty()) {
                controller.backUpNow()
            } else {
                _uiState.update { it.copy(backUpNowConfirmation = blockers) }
            }
        }
    }

    /** The user agreed: back up now, ignoring exactly what the dialog named — this run only. */
    fun confirmBackUpNowOverride() {
        val ignore = _uiState.value.backUpNowConfirmation ?: return
        _uiState.update { it.copy(backUpNowConfirmation = null) }
        controller.backUpNow(ignore = ignore)
    }

    /** "Not now" — nothing runs; the automatic backup keeps waiting for the user's conditions. */
    fun dismissBackUpNowOverride() {
        _uiState.update { it.copy(backUpNowConfirmation = null) }
    }

    /**
     * **Stop** the running backup (ST-2b). A pause: the queue and cursors are kept, and [resume]
     * picks up where this left off. ⚠ Not "turn backup off" — that is the master switch, and
     * "stop backing up this folder for good" is unticking it (D1).
     */
    fun stopBackup() {
        viewModelScope.launch { controller.stop() }
    }

    /** **Resume** a user-stopped backup (ST-2b). */
    fun resumeBackup() {
        viewModelScope.launch { controller.resume() }
    }

    fun setWifiOnly(enabled: Boolean) {
        _uiState.update { it.copy(wifiOnly = enabled) }
        viewModelScope.launch { preferences.setWifiOnly(enabled) }
    }

    fun setChargingOnly(enabled: Boolean) {
        _uiState.update { it.copy(chargingOnly = enabled) }
        viewModelScope.launch { preferences.setChargingOnly(enabled) }
    }

    fun setBackupNewFolders(enabled: Boolean) {
        _uiState.update { it.copy(backupNewFolders = enabled) }
        viewModelScope.launch { preferences.setBackupNewFolders(enabled) }
    }

    fun setVideos(enabled: Boolean) {
        _uiState.update { it.copy(videos = enabled) }
        viewModelScope.launch { preferences.setVideos(enabled) }
    }

    /**
     * Remove a SAF document-tree folder from backup (Phase 3b): drop it from the
     * selection *and* release the persistable grant Drive took when it was added, so
     * the grant doesn't outlive its use. Adding a folder happens at the Activity (it
     * needs the picker result + the read flag); only removal is reachable here.
     */
    fun removeBackupFolder(uri: String) {
        viewModelScope.launch {
            preferences.removeDocumentTreeUri(uri)
            backupFolderGrants.release(uri)
        }
    }

    /** Remove a Control-owned desktop grant and stop selecting its bucket. */
    fun removeGrantedBucket(bucketId: String) {
        if (bucketId !in _uiState.value.grantedBucketIds) return
        val next = selectedIds - bucketId
        selectedIds = next
        recomputeRows()
        viewModelScope.launch {
            preferences.setSelectedBuckets(next.toList())
            preferences.removeGrantedBucket(bucketId)
            backupFolderGrants.release(bucketId)
        }
    }

    /**
     * Tick or untick one folder. **Ticking also forgets that bucket's cursor**, so the folder
     * is re-scanned from the beginning (§3.9).
     *
     * ⚠⚠ Ticking a folder means "back this folder up", and a user who unticks and re-ticks is
     * asking for exactly that — but the cursor is a monotonic watermark, so without the reset
     * the drain finds zero new items, reports `UP_TO_DATE` and uploads nothing. A destination
     * deleted from another device could therefore never be rebuilt; that reads as a dead
     * feature and was reported as one. Re-scanning is cheap: `uploadForBackup` skips anything
     * already at the destination (§3.5), so this re-uploads only what is actually missing.
     *
     * ⚠⚠ **The reset belongs HERE, at the tap, and must not move into
     * `SyncedFolderController`'s `selectedBuckets` collector.** That flow re-emits the stored
     * selection on every process start, and the collector cannot tell that first emission from
     * a user's tick — it would wipe every cursor at each launch and re-enumerate every bucket
     * forever. A tap is the one unambiguous signal of intent.
     *
     * ⚠ Order is load-bearing: clear the cursor **before** writing the selection. Writing the
     * selection is itself a drain trigger, so the reverse order races a drain that reads the
     * old cursor, finds nothing, and parks the source at `UP_TO_DATE` with nothing left to
     * re-trigger it.
     */
    fun setBucketSelected(bucketId: String, selected: Boolean) {
        val next = if (selected) selectedIds + bucketId else selectedIds - bucketId
        selectedIds = next
        recomputeRows()
        viewModelScope.launch {
            if (selected) preferences.clearCursor(GALLERY_SOURCE_ID, bucketId)
            preferences.setSelectedBuckets(next.toList())
        }
    }

    /**
     * Re-read the media grant and the bucket list. The screen calls this on every
     * resume and after a permission dialog: the grant can change from system
     * settings while Drive is backgrounded, and on Android a partial selection can
     * expire on its own — so it is never cached across a lifecycle boundary.
     */
    fun refresh() {
        _uiState.update {
            it.copy(
                mediaAccess = mediaAccess.current(),
                photoLocationGranted = mediaAccess.photoLocationGranted(),
            )
        }
        refreshBuckets()
    }

    private fun refreshBuckets() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loadingBuckets = true,
                    mediaAccess = mediaAccess.current(),
                    photoLocationGranted = mediaAccess.photoLocationGranted(),
                )
            }
            val buckets = runCatchingCancellable { mediaSource.listBuckets(includeVideo) }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    loadingBuckets = false,
                    buckets = buckets.map { b ->
                        BucketRow(
                            id = b.id,
                            name = b.displayName,
                            itemCount = b.itemCount,
                            thumbnailUri = b.sampleThumbnailUri,
                            selected = b.id in selectedIds,
                        )
                    },
                )
            }
        }
    }

    private fun recomputeRows() {
        _uiState.update { state ->
            state.copy(buckets = state.buckets.map { it.copy(selected = it.id in selectedIds) })
        }
    }
}

data class SyncedFoldersUiState(
    val enabled: Boolean = false,
    val hasStorage: Boolean = true,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val videos: Boolean = false,
    val backupNewFolders: Boolean = true,
    val loadingBuckets: Boolean = false,
    /** Drives the §5 media-access notice. [MediaAccess.FULL] shows nothing. */
    val mediaAccess: MediaAccess = MediaAccess.FULL,
    /**
     * Whether photo locations are readable (`timeline-photos.md` T-P4). Its own flag, not a
     * [MediaAccess] state: backup works perfectly without it, so it must not read as a degraded
     * library — see [MediaAccessProvider.photoLocationGranted].
     */
    val photoLocationGranted: Boolean = true,
    val buckets: List<BucketRow> = emptyList(),
    /** Opaque Control-owned macOS grants, shown with a remove action. */
    val grantedBucketIds: Set<String> = emptySet(),
    val documentFolders: List<DocumentFolderRow> = emptyList(),
    val backupState: BackupState = BackupState.Disabled,
    /** Whether *Back up now* is offered — only where this process owns the drain
     *  (see [SyncedFolderController.runsDrainInProcess]). */
    val canBackUpNow: Boolean = false,
    /**
     * Non-null while *Back up now* is asking whether to ignore these of the user's own
     * constraints for one run ("Use mobile data this once?"). Never written to the settings.
     */
    val backUpNowConfirmation: Set<BackupConstraint>? = null,
)

/** A user-added SAF document-tree folder backed up via the doc-tree source (3b). */
data class DocumentFolderRow(
    val uri: String,
    val name: String,
)

data class BucketRow(
    val id: String,
    val name: String,
    val itemCount: Int,
    val thumbnailUri: String?,
    val selected: Boolean,
)
