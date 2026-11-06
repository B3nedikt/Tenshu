package app.tenshu.drive.ui.selection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.data.SyncRulesPreferences
import app.tenshu.drive.domain.StorageGate
import app.tenshu.drive.domain.StorageGateDecision
import org.tenshu.core.file.sync.SyncRuleSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SelectionViewModel(
    private val filesRepository: FilesRepository,
    private val offlinePreferences: OfflinePreferences,
    private val syncRulesPreferences: SyncRulesPreferences,
) : ViewModel() {
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    // The subset of [selectedPaths] that are folders — Mode-B sharing is
    // folder-only (drive-shared-realm-file-model.md §6 Phase 3d.2b), so the
    // *Share* action gates on "exactly one selected, and it's a folder".
    private val _selectedFolderPaths = MutableStateFlow<Set<String>>(emptySet())

    private val _offlinePaths = MutableStateFlow<Set<String>>(emptySet())
    val offlinePaths: StateFlow<Set<String>> = _offlinePaths.asStateFlow()

    /**
     * The effective v3 sync-rule set for this device — the per-device default +
     * sparse overrides, with the legacy pin set folded in as winning exact-path
     * `KEEP_OFFLINE` overrides (`docs/proposals/drive-offline-online.md` §4.2/§4.9).
     * Each listing row resolves its glyph against this via
     * [SyncRuleSet.effectivePolicy]. Seeded with [SyncRuleSet.Default] so rows
     * resolve to "online-only unless pinned" until the device-rule reads arrive.
     */
    val syncRules: StateFlow<SyncRuleSet> =
        combine(syncRulesPreferences.observe(), _offlinePaths) { base, pins ->
            base.withPinnedPaths(pins)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SyncRuleSet.Default)

    /** True iff the selection is a single folder — the only shareable shape (Mode-B). */
    val canShareSelection: StateFlow<Boolean> =
        combine(_selectedPaths, _selectedFolderPaths) { selected, folders ->
            selected.size == 1 && selected.single() in folders
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Tier-1 storage signal (storage-capacity-gating.md §2). Cached so the share
    // entry points decide synchronously with no per-action round trip.
    private val storageStatus: StateFlow<StorageStatusModel?> =
        filesRepository.storageStatus()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _storageGateBlock = MutableStateFlow<StorageGateDecision?>(null)

    /** Non-null when a share was blocked by the gate — the host shows the dialog. */
    val storageGateBlock: StateFlow<StorageGateDecision?> = _storageGateBlock.asStateFlow()

    fun onStorageGateDismissed() {
        _storageGateBlock.value = null
    }

    init {
        viewModelScope.launch {
            offlinePreferences.observe().collect { paths ->
                _offlinePaths.value = paths.toSet()
            }
        }
    }

    fun toggleSelection(path: String, isFolder: Boolean = false) {
        val nowSelected = path !in _selectedPaths.value
        _selectedPaths.update { current ->
            if (current.contains(path)) current - path else current + path
        }
        _selectedFolderPaths.update { current ->
            if (nowSelected && isFolder) current + path else current - path
        }
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
        _selectedFolderPaths.value = emptySet()
    }

    /**
     * Mode-B share (`docs/proposals/drive-shared-realm-file-model.md` §6 Phase 3d):
     * share the selected folder into a fresh shared realm. **Folder-only** (3d.2b):
     * gates on a single folder selection — sharing a file would bootstrap an empty
     * realm. [FilesRepository.shareFolder] bootstraps the realm, populates it, and
     * self-mounts it, so the result surfaces in the Shared tab's *Shared folders*
     * group. A multi-selection or a file selection is a no-op (the *Share* card is
     * also disabled in those states — see [canShareSelection]).
     */
    fun onShareClicked() {
        val path = _selectedPaths.value.singleOrNull() ?: return
        if (path !in _selectedFolderPaths.value) return
        when (val decision = StorageGate.evaluate(storageStatus.value)) {
            StorageGateDecision.Allow -> viewModelScope.launch {
                filesRepository.shareFolder(path)
                clearSelection()
            }
            else -> _storageGateBlock.value = decision
        }
    }

    /**
     * Mode-A *Share link* gate for the multi-select sheet. Returns the path to
     * open the create-link sheet for when storage allows it; otherwise raises
     * the gate dialog and returns `null`. Synchronous over the cached status —
     * the host wires the returned path into its create-link surface.
     */
    fun onShareLinkRequested(path: String): String? =
        when (val decision = StorageGate.evaluate(storageStatus.value)) {
            StorageGateDecision.Allow -> path
            else -> {
                _storageGateBlock.value = decision
                null
            }
        }

    fun onDeleteClicked() {
        viewModelScope.launch {
            _selectedPaths.value.forEach { path ->
                filesRepository.deleteFile(path)
            }
            clearSelection()
        }
    }

    fun onOfflineToggled(offline: Boolean) {
        val targets = _selectedPaths.value
        if (targets.isEmpty()) return
        val next = if (offline) _offlinePaths.value + targets else _offlinePaths.value - targets
        // Optimistic: reflect the change immediately; the observe() flow
        // re-confirms with the persisted value once the write lands.
        _offlinePaths.value = next
        viewModelScope.launch { offlinePreferences.setOffline(next.toList()) }
    }
}
