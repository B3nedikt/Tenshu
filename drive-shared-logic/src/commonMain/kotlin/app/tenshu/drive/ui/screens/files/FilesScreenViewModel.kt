package app.tenshu.drive.ui.screens.files

import app.tenshu.drive.ui.screens.RowPrefetchBounds
import app.tenshu.drive.ui.screens.RowPrefetchLimiter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.tenshu.drive.data.ChangeLostDismissals
import app.tenshu.drive.data.ChangeLostModel
import app.tenshu.drive.data.DisplayPreferences
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileViewMode
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.NoopDisplayPreferences
import app.tenshu.drive.domain.BackupProgress
import app.tenshu.drive.domain.BackupWaiting
import app.tenshu.drive.domain.SyncBanner
import app.tenshu.drive.data.ContentTransferModel
import app.tenshu.drive.data.SchemaFenceModel
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.data.SyncHealthStatusModel
import app.tenshu.drive.domain.SyncBannerState
import app.tenshu.reachability.UnreachableReason
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.model.toUiModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.paging.map as pagingMap

class FilesScreenViewModel(
    private val filesRepository: FilesRepository,
    private val selectionViewModel: SelectionViewModel,
    folderPath: String,
    // Trailing + defaulted so the iOS surface and test Koin (which construct the
    // VM positionally without persistence) are unaffected; the DI graph passes
    // the real, preference-backed instance.
    private val displayPreferences: DisplayPreferences = NoopDisplayPreferences,
    /**
     * Whether Drive's own folder-upload bar is on screen (`drive-upload-progress.md` P6). That bar
     * already narrates the operation with the real batch denominator, so the banner steps aside
     * rather than reporting the same upload beside it in different numbers — see
     * [SyncBanner.evaluate]'s `folderUploadActive`.
     *
     * Trailing + defaulted, like [displayPreferences], so the iOS surface and test Koin (which
     * construct this positionally) are unaffected; the DI graph passes the real controller state.
     */
    folderUploadActive: Flow<Boolean> = flowOf(false),
    /**
     * The Synced-Folders auto-backup drain in flight, if any (`null` between drains). Its figures
     * replace the proxy's pass figures in the banner — see [BackupProgress] for why the
     * queue-derived denominator jumps during a backup and this one does not.
     *
     * Trailing + defaulted for the same reason as [folderUploadActive]: the iOS surface and test
     * Koin construct this ViewModel positionally.
     */
    backupProgress: Flow<BackupProgress?> = flowOf(null),
    /**
     * Stop the running auto-backup (ST-2b) — the banner's Stop. Passed as a lambda for the same
     * reason [backupProgress] is a flow: the controller is app-scoped and outlives this ViewModel,
     * and the screen needs one verb from it, not a dependency on it.
     */
    private val stopBackup: suspend () -> Unit = {},
    /**
     * The announce refusal, when there is one (`core-schema-skew-surface.md` SK-1b, ADR 0025) —
     * the case Drive's banner never had.
     *
     * Trailing + defaulted for the same reason as [backupProgress]: the iOS surface and test Koin
     * construct this ViewModel positionally. ⚠ Absent, the banner behaves exactly as before —
     * which is also why forgetting to wire it is silent, and why `DriveReachability`'s KDoc names
     * the bind site.
     */
    schemaSkew: Flow<UnreachableReason.Skew?> = flowOf(null),
    /**
     * The auto-backup's policy pause while it holds files back (`null` otherwise) — see
     * [SyncBannerState.BackupWaiting]. Trailing + defaulted like [backupProgress].
     */
    backupWaiting: Flow<BackupWaiting?> = flowOf(null),
) : ViewModel() {

    val uiState: StateFlow<FilesScreenUiState> get() = _uiState
    private val _uiState: MutableStateFlow<FilesScreenUiState> = MutableStateFlow(
        FilesScreenUiState()
    )

    val selectedPaths = selectionViewModel.selectedPaths

    /** This device's effective sync-rule set — each row resolves its offline
     *  glyph against it (`docs/proposals/drive-offline-online.md` §4). */
    val syncRules = selectionViewModel.syncRules

    /**
     * Persistent sync-status banner above the list (local-first reframe, ADR 0019;
     * connectivity signal `drive-connectivity-service.md`). Combines the three
     * reactive signals Drive observes over IPC — the storage-provision status, the
     * proxy's sync-health status, and its schema pull-fence verdict
     * (`core-schema-evolution.md` §7.3) — into [SyncBannerState.LocalOnly] /
     * [SyncBannerState.NeedsUpdate] / [SyncBannerState.Offline] /
     * [SyncBannerState.Syncing]. `null` ⇒ no banner.
     */
    private data class BannerInputs(
        val storage: StorageStatusModel?,
        val health: SyncHealthStatusModel?,
        val fence: SchemaFenceModel?,
        val content: ContentTransferModel?,
        val folderUploadActive: Boolean,
    )

    /** Whether Control is pulling — the "Syncing…" capsule's input (`suite-sync-status.md` SS-2). */
    val catchingUp: Flow<Boolean> = filesRepository.catchingUp()

    val syncBanner: StateFlow<SyncBannerState?> =
        combine(
            // ⚠ Every source here must be one that always emits. `combine` withholds its first
            // value until *all* of them have produced one, so a single silent source blanks the
            // whole banner rather than just its own contribution.
            combine(
                filesRepository.storageStatus(),
                filesRepository.syncHealth(),
                filesRepository.schemaFence(),
                filesRepository.contentTransfer(),
                folderUploadActive,
                ::BannerInputs,
            ),
            // Folded in separately only because the typed `combine` overloads stop at five.
            filesRepository.proxyConnected(),
            backupProgress,
            schemaSkew,
            backupWaiting,
        ) { inputs, connected, backup, skew, waiting ->
            SyncBanner.evaluate(
                inputs.storage,
                inputs.health,
                inputs.fence,
                inputs.content,
                inputs.folderUploadActive,
                connected,
                backup,
                skew,
                waiting,
            )
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * ⭐ CR-2 B4 P3 (`content-plane-cr2-b4-spec.md` §2.4): the newest change-lost notice this run has
     * not dismissed, shown once as a dismissable message — "<file> was deleted on another device while
     * your change was waiting". `null` ⇒ nothing to say.
     */
    val changeLost: StateFlow<ChangeLostModel?> =
        combine(filesRepository.changesLost(), ChangeLostDismissals.ids) { notices, dismissed ->
            notices.firstOrNull { it.id !in dismissed }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismissChangeLost(id: String) = ChangeLostDismissals.dismiss(id)

    /**
     * "Sync now over cellular" — the escape hatch on the [SyncBannerState.Deferred] banner
     * (`sync-metered-deferred-status.md` §2.3). Fire-and-forget kick past the WifiOnly policy;
     * the banner clears as the backlog drains.
     */
    fun syncNowOverCellular() {
        viewModelScope.launch { filesRepository.syncNow() }
    }

    /**
     * **Stop** the auto-backup drive is narrating (ST-2b). Offered on the banner only while a
     * drain owns its figures — see [app.tenshu.drive.domain.SyncBannerState.Syncing.stoppableBackup].
     *
     * ⚠ A pause, not a cancel: the queue and cursors survive, and Resume lives in Synced Folders.
     */
    fun stopBackupNow() {
        viewModelScope.launch { stopBackup() }
    }

    /**
     * The listing's "Sort by" selection, **persisted per device** via
     * [DisplayPreferences] (so it survives navigation into a subfolder and app restart), and
     * pushed into the listing chain — folders first, the key, then the name (see the
     * repository). Drives [pagedFiles].
     *
     * ⚠ It used to be nullable, `null` meaning natural (store) order on a separate repository
     * path. SP-6 of `drive-sorted-folder-paging.md` retired that mode (D5), so there is one
     * listing and one seed: [FileSort.Default] (Name ascending, D6), which is also what the
     * persisted value decodes to when a device has never chosen.
     */
    val sort: StateFlow<FileSort> =
        displayPreferences.observeSort()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FileSort.Default)

    fun setSort(sort: FileSort) {
        viewModelScope.launch { displayPreferences.setSort(sort) }
    }

    /**
     * List-vs-grid display, **persisted per device** via [DisplayPreferences].
     * Pure rendering — it doesn't feed [pagedFiles], only the item composable the
     * list picks.
     */
    val viewMode: StateFlow<FileViewMode> =
        displayPreferences.observeViewMode()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FileViewMode.LIST)

    fun setViewMode(mode: FileViewMode) {
        viewModelScope.launch { displayPreferences.setViewMode(mode) }
    }

    /**
     * Paged folder listing, mapped to UI models.
     *
     * ### `cachedIn`, and the ban it used to carry
     *
     * ⚠⚠ This line said **"No `cachedIn`"** from 2026-07-10 until 2026-08-12, because `cachedIn`
     * was the root cause of the Android folder-listing-staleness bug
     * (`drive-folder-listing-consistency.md`): on-device instrumentation proved it **dropped the
     * refresh `load()` on new `PagingData` generations**, so a folder created while viewing never
     * appeared until re-entry. That finding was real. It has since **stopped being true** — see
     * `core-paging-scroll-restoration.md` §0, and `CachedInRefreshContractTest` +
     * `FilesPagingReactivityUiTest`, which are the tests the rule never had.
     *
     * ⚠ Do not remove this line on the strength of that old comment if you meet it elsewhere. Do
     * remove it if `FilesPagingReactivityUiTest` ever goes red: that test collects **this** flow
     * through the real differ and is the live guard on the reactivity half.
     *
     * ### What it buys
     *
     * The loaded pages outlive the composition, so a navigation round-trip (open a photo → back)
     * restores a deep-scrolled folder in place instead of refreshing from page 0 and letting
     * `LazyListState` clamp its restored index into a one-page list.
     *
     * ⚠ `cachedIn` sits **downstream of the sort `flatMapLatest`** deliberately: a sort change is a
     * genuinely different query, so it *should* build a new `Pager` and reset to the top. Only the
     * per-change reload inside `getFilesPaged` is cached over.
     *
     * ⚠ Q1, **measured and closed** (`PagedRowRetentionTest`): this retains every loaded page for
     * the view model's life, but a paged row is metadata only — **308 B/row, so 5 000 rows cost
     * 1.5 MB**. ⚠⚠ The figure holds *because* the projection carries no bytes; the per-row preview
     * loader below is bounded by composition (`RowPrefetchLimiter`), not by this cache. Put
     * `File.Preview` back into the paging projection and both facts change at once — which is what
     * that test fails on.
     *
     * ⚠ `PagingConfig.maxSize` was considered and rejected: with placeholders off it bounds
     * retention only by dropping pages, which shifts every index — the defect class this whole
     * change removed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedFiles: Flow<PagingData<FileUiModel>> =
        sort
            .flatMapLatest { sort -> filesRepository.getFilesPaged(folderPath, sort) }
            .map { paging -> paging.pagingMap(FileModel::toUiModel) }
            .cachedIn(viewModelScope)

    fun showFile(file: FileUiModel) {
        // No local-vs-remote branch here — the listing chain no longer eagerly
        // materialises file bytes (that cured the Binder-watchdog kill on
        // folders of multi-MB photos). The byte fetch moved into
        // [ImagePreviewViewModel] / [FilesRepository.getRemoteFile].
        _uiState.update {
            if (file.isFolder) it.copy(showFolderWithPath = file.path)
            else it.copy(showFileWithPath = file.path)
        }
    }

    fun hideFile() {
        _uiState.update {
            it.copy(
                showFolderWithPath = null,
                showFileWithPath = null
            )
        }
    }

    fun showFileActions(file: FileUiModel) {
        _uiState.update { it.copy(showFileActionsWithPath = file.path, showFileActionsForFolder = file.isFolder) }
    }

    fun hideFileActions() {
        _uiState.update { it.copy(showFileActionsWithPath = null) }
    }

    fun showRenameFile(path: String) {
        _uiState.update { it.copy(showRenameFileWithPath = path) }
    }

    fun hideRenameFile() {
        _uiState.update { it.copy(showRenameFileWithPath = null) }
    }

    fun toggleSelection(file: FileUiModel) {
        selectionViewModel.toggleSelection(file.path, file.isFolder)
    }

    fun clearSelection() {
        selectionViewModel.clearSelection()
    }

    /** Viewport-scoped prefetch bound — see [RowPrefetchBounds]. */
    private val previewConcurrency = RowPrefetchLimiter(RowPrefetchBounds.PREVIEW)

    /**
     * Per-row preview loader. Returns the raw preview/thumbnail bytes on
     * success, `null` for any failure (no preview available, cancellation). The
     * UI decodes them (`decodeToImageBitmap`, off the UI thread) — the VM stays
     * Compose-free so it compiles on iOS (§9.3).
     *
     * Cooperative cancellation: if the row scrolls off-screen before the
     * fetch returns, the calling Composable's `produceState` /
     * `LaunchedEffect` scope is cancelled, which cancels this coroutine
     * and releases its permit immediately.
     */
    suspend fun loadPreviewBytes(path: String, width: Int, height: Int): ByteArray? {
        return previewConcurrency.withSlot {
            filesRepository.getPreview(path, width, height)
        }
    }

    /** Viewport-scoped prefetch bound — see [RowPrefetchBounds]. */
    private val localDataConcurrency = RowPrefetchLimiter(RowPrefetchBounds.LOCAL_DATA)

    /**
     * Per-row byte-presence probe (the v2 presence signal,
     * `docs/proposals/drive-offline-online.md` §3): `true` when the file's
     * bytes are on local disk right now. Bounded + cancelled-on-scroll exactly
     * like [loadPreview].
     */
    suspend fun loadLocalData(path: String): Boolean =
        localDataConcurrency.withSlot { filesRepository.hasLocalData(path) }
}
