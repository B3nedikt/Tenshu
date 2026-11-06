package app.tenshu.drive.ui.common.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.data.SyncRulesPreferences
import app.tenshu.drive.domain.StorageGate
import app.tenshu.drive.domain.StorageGateDecision
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import org.tenshu.core.file.sync.withFolderPolicy
import app.tenshu.ingest.backup.BackupDestination
import app.tenshu.drive.ui.model.FileUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FileActionsViewModel(
    private val filesRepository: FilesRepository,
    private val offlinePreferences: OfflinePreferences,
    private val syncRulesPreferences: SyncRulesPreferences,
    private val filePath: String,
    private val isFolder: Boolean,
) : ViewModel() {

    val uiState: StateFlow<FileActionsUiState> get() = _uiState
    private var _uiState: MutableStateFlow<FileActionsUiState> = MutableStateFlow(
        FileActionsUiState(
            file = FileUiModel(
                path = filePath,
                name = filePath.substringAfterLast("/"),
                subLine = "",
                previewIcon = if (isFolder) FileIcon.Folder else FileIcon.GenericFile,
                isFolder = isFolder,
            ),
            isFolder = isFolder,
        )
    )

    // Latest rule set, cached so the folder-override toggle can compute the next
    // override list (sparse upsert/clear) without clobbering other overrides.
    private var ruleSet: SyncRuleSet = SyncRuleSet.Default

    // Latest persisted offline list, cached so the toggle can compute the next
    // list without clobbering other pinned paths (the pref stores the whole
    // device list, not a per-file flag).
    private var offlinePaths: Set<String> = emptySet()

    // Tier-1 storage signal (storage-capacity-gating.md §2), cached so onLinkClicked
    // decides synchronously.
    private val storageStatus: StateFlow<StorageStatusModel?> =
        filesRepository.storageStatus()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Reflect the persisted offline *intent* into the Download row's
        // label/icon. Mirrors SelectionViewModel's observe of the same pref.
        viewModelScope.launch {
            offlinePreferences.observe()
                // Best-effort UI state: a transient preference-read failure must
                // not crash the VM (or leak an uncaught exception into a test
                // scheduler) — the row just stays at its last-known label.
                .catch { /* keep last-known offline state */ }
                .collect { paths ->
                    offlinePaths = paths.toSet()
                    _uiState.update { it.copy(isAvailableOffline = filePath in offlinePaths) }
                }
        }
        // Folders show a folder-level sync-rule override instead of the file pin
        // (drive-offline-online.md §4.10). Track the rule set so the
        // toggle reflects (and edits) this folder's effective policy.
        if (isFolder) {
            viewModelScope.launch {
                syncRulesPreferences.observe()
                    .catch { /* keep last-known rule set */ }
                    .collect { rules ->
                        ruleSet = rules
                        _uiState.update {
                            it.copy(folderKeptOffline = rules.effectivePolicy(filePath) == SyncPolicy.KEEP_OFFLINE)
                        }
                    }
            }
        }
    }

    /**
     * Toggle a **folder-level** sync-rule override for this folder
     * (`docs/proposals/drive-offline-online.md` §4.2 / §4.10). Flips the
     * folder's effective policy between [SyncPolicy.KEEP_OFFLINE] and
     * [SyncPolicy.ONLINE_ONLY], writing a sparse override (or clearing it when the
     * choice equals the device default). Covers files created under the folder
     * later — the whole point of folder rules. Optimistic; the `observe()`
     * collector re-confirms. Then [reconcileFolderBytes] eagerly moves the
     * bytes of the folder's *current* contents to match.
     */
    fun onFolderOfflineToggled() {
        val keepOffline = !_uiState.value.folderKeptOffline
        _uiState.update { it.copy(folderKeptOffline = keepOffline) }
        val target = if (keepOffline) SyncPolicy.KEEP_OFFLINE else SyncPolicy.ONLINE_ONLY
        val next = ruleSet.overrides.withFolderPolicy(filePath, target, ruleSet.deviceDefault)
        ruleSet = ruleSet.copy(overrides = next)
        viewModelScope.launch {
            syncRulesPreferences.setOverrides(next)
            reconcileFolderBytes(target)
        }
    }

    /**
     * Eagerly apply [target] to the folder's *current* contents: evict (or
     * download) every file whose **effective** policy — the just-written folder
     * override folded with any finer-grained per-file pins and nested folder
     * overrides — resolves to [target]. This is the user-triggered, foreground
     * half of enforcement; the ambient background keeper (proactively fetching
     * files that arrive *later* via sync + storage-pressure LRU eviction) is
     * separate (`drive-offline-online.md` §3.2). A
     * file under a nested `KEEP_OFFLINE` subfolder or explicitly pinned offline
     * is left downloaded when the folder goes online-only, and vice-versa.
     *
     * Best-effort + sequential: a per-file failure (e.g. offline during a
     * download) is skipped, not fatal. Bounded by [viewModelScope], so leaving
     * the screen mid-run cancels the remaining work — the persisted override
     * stands, but a re-run (or the ambient keeper) is what finishes a large
     * download.
     */
    private suspend fun reconcileFolderBytes(target: SyncPolicy) {
        val effective = ruleSet.withPinnedPaths(offlinePaths)
        for (path in filesRepository.listFilesRecursively(filePath)) {
            if (effective.effectivePolicy(path) != target) continue
            when (target) {
                SyncPolicy.ONLINE_ONLY -> filesRepository.evictFile(path)
                SyncPolicy.KEEP_OFFLINE -> filesRepository.downloadFile(path)
            }
        }
    }

    /**
     * Toggle this file's offline state (`docs/proposals/drive-offline-online.md`
     * §2). Pinning adds the path to [DrivePreferences.OfflineFiles] and forces the
     * bytes onto local disk; unpinning removes it and evicts the bytes. We flip
     * the state optimistically — the `observe()` collector above re-confirms with
     * the persisted value once the write lands.
     */
    fun onDownloadClicked() {
        val makeOffline = !_uiState.value.isAvailableOffline
        _uiState.update { it.copy(isAvailableOffline = makeOffline) }
        val next = if (makeOffline) offlinePaths + filePath else offlinePaths - filePath
        offlinePaths = next
        viewModelScope.launch {
            offlinePreferences.setOffline(next.toList())
            val result = if (makeOffline) {
                filesRepository.downloadFile(filePath)
            } else {
                filesRepository.evictFile(filePath)
            }
            // ⚠⚠ **Revert the optimistic flip when the operation REFUSED** — eviction can refuse
            // (`EvictFileBytes`, `ContentPinnedError`). Keeping the flip would report the file as
            // online-only while its bytes are still on disk: the UI asserting the opposite of what
            // happened, which is worse than the refusal it hides.
            //
            // ⚠ The preference is rewritten too, not just the flag — it is the durable record the
            // next screen reads, so reverting one without the other trades a wrong label for a wrong
            // device list.
            if (result is RepositoryResult.Error) {
                // ⚠⚠ Roll back **only this path**, reconstructed from the CURRENT list — never by
                // rewriting the stale snapshot taken before the request. The preference stores the
                // whole device-wide list, so replaying an old copy would delete any path another
                // screen added meanwhile and resurrect any it removed: one refused eviction
                // silently editing unrelated files' offline state.
                val reverted =
                    if (makeOffline) offlinePaths - filePath else offlinePaths + filePath
                offlinePaths = reverted
                offlinePreferences.setOffline(reverted.toList())
                _uiState.update { it.copy(isAvailableOffline = !makeOffline) }
            }
        }
    }

    /**
     * Mode-B producer entry (`docs/proposals/drive-shared-realm-file-model.md` §6;
     * `drive-manage-access-ux.md` §2.1): share this folder into a fresh shared
     * realm. **Folder-only** — the row is hidden for files (a file shares via the
     * Mode-A *Link* action; sharing a file would bootstrap an empty realm), and we
     * guard here too. Mirrors [SelectionViewModel.onShareClicked]: gated on storage
     * (`storage-capacity-gating.md` §2) since a full / account-less owner can't
     * create new synced content; a block raises [FileActionsUiState.storageGateBlock]
     * for the host-rendered dialog.
     *
     * Returns `true` when the share was started (the host may collapse the sheet)
     * and `false` when the storage gate blocked it (the host keeps the sheet
     * composed so [StorageGateDialog] can render in place — same rule as
     * [onLinkClicked]).
     */
    fun onShareClicked(): Boolean {
        if (!isFolder) return true
        return when (val decision = StorageGate.evaluate(storageStatus.value)) {
            StorageGateDecision.Allow -> {
                viewModelScope.launch { filesRepository.shareFolder(filePath) }
                true
            }
            else -> {
                _uiState.value = _uiState.value.copy(storageGateBlock = decision)
                false
            }
        }
    }

    /**
     * Mode-A: surface [app.tenshu.drive.ui.share.CreateShareLinkSheet] for
     * this file (`docs/file-sharing.md`). The actual
     * `ShareLink` op call lives on `CreateShareLinkViewModel`; this VM only
     * flips the host-rendered state flag.
     */
    fun onLinkClicked() {
        when (val decision = StorageGate.evaluate(storageStatus.value)) {
            StorageGateDecision.Allow -> _uiState.value = _uiState.value.copy(
                showCreateShareLinkForPath = filePath,
            )
            else -> _uiState.value = _uiState.value.copy(storageGateBlock = decision)
        }
    }

    /** Host dismisses the sheet → clear the flag. */
    fun onCreateShareLinkDismissed() {
        _uiState.value = _uiState.value.copy(
            showCreateShareLinkForPath = null
        )
    }

    /** Host dismisses the storage-gate dialog → clear it. */
    fun onStorageGateDismissed() {
        _uiState.value = _uiState.value.copy(storageGateBlock = null)
    }

    /**
     * Delete — except over a Synced-Folders **destination**, which explains itself first.
     *
     * ⚠ The warning is not a generic "are you sure": deleting a backup destination is the
     * one delete in Drive whose consequences users reliably guess wrong in both directions.
     * They fear it will delete the photos off the phone (it will not — backup is one-way,
     * `drive-synced-folders.md` §3.3), and they do not expect the folder to keep receiving
     * new photos afterwards (it will, because the selection lives on the source device and
     * a delete here cannot un-tick it). Both halves are in the copy.
     *
     * ⚠ Already-uploaded files do **not** come back: the per-bucket cursor is a monotonic
     * device-local watermark, never re-derived from realm contents (§3.5). So this delete
     * is permanent even though backup continues — which is exactly why it is worth a
     * sentence before it happens rather than an undo after.
     */
    fun onDeleteClicked() {
        val destination = BackupDestination.of(filePath)
        if (destination == null) {
            performDelete()
            return
        }
        _uiState.value = _uiState.value.copy(backupDeleteWarning = destination)
    }

    /** The warning's primary action — the user has read the consequence and still wants it. */
    fun onBackupDeleteConfirmed() {
        _uiState.value = _uiState.value.copy(backupDeleteWarning = null)
        performDelete()
    }

    /** Host dismisses the warning → clear it, delete nothing. */
    fun onBackupDeleteDismissed() {
        _uiState.value = _uiState.value.copy(backupDeleteWarning = null)
    }

    private fun performDelete() {
        viewModelScope.launch {
            filesRepository.deleteFile(filePath)
        }
    }

}