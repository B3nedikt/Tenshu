package app.tenshu.drive.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.SyncRulesPreferences
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.core.file.sync.SyncPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SyncPreferencesViewModel(
    private val offlinePreferences: OfflinePreferences,
    private val syncRulesPreferences: SyncRulesPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncPreferencesUiState())
    val uiState: StateFlow<SyncPreferencesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            offlinePreferences.observe().collect { paths ->
                _uiState.update { it.copy(offlineFiles = paths) }
            }
        }
        viewModelScope.launch {
            syncRulesPreferences.observeDefaultPolicy().collect { policy ->
                _uiState.update { it.copy(keepOfflineByDefault = policy == SyncPolicy.KEEP_OFFLINE) }
            }
        }
        viewModelScope.launch {
            syncRulesPreferences.observeMaxLocalBytes().collect { bytes ->
                _uiState.update { it.copy(maxLocalBytes = bytes) }
            }
        }
    }

    /**
     * Flip this device's default sync policy (`docs/proposals/drive-offline-online.md`
     * §4.2.1 / §4.10 Phase 2). On ⇒ [SyncPolicy.KEEP_OFFLINE] (download everything
     * by default); off ⇒ [SyncPolicy.ONLINE_ONLY]. Optimistic — the
     * [SyncRulesPreferences.observeDefaultPolicy] flow re-confirms once the write
     * lands. No file is downloaded/evicted yet (the keeper is Phase 3); the new
     * default only changes the listing glyph for un-overridden files today.
     */
    fun setKeepOfflineByDefault(enabled: Boolean) {
        _uiState.update { it.copy(keepOfflineByDefault = enabled) }
        val policy = if (enabled) SyncPolicy.KEEP_OFFLINE else SyncPolicy.ONLINE_ONLY
        viewModelScope.launch { syncRulesPreferences.setDefaultPolicy(policy) }
    }

    /**
     * Set this device's **cache** ceiling (`retention-quota-model.md` Face B); `0`
     * clears it. Optimistic, like [setKeepOfflineByDefault] — the observe flow
     * re-confirms once the write lands.
     *
     * ⚠ Nothing is evicted at the moment of the write: enforcement is the offline
     * keeper's periodic sweep, so a lowered ceiling takes effect on its next pass
     * rather than immediately. Copy on the screen must not promise otherwise.
     */
    fun setMaxLocalBytes(bytes: Long) {
        _uiState.update { it.copy(maxLocalBytes = bytes) }
        viewModelScope.launch { syncRulesPreferences.setMaxLocalBytes(bytes) }
    }

    fun removeOfflineFile(path: String) {
        val next = _uiState.value.offlineFiles - path
        _uiState.update { it.copy(offlineFiles = next) }
        viewModelScope.launch { offlinePreferences.setOffline(next) }
    }
}

data class SyncPreferencesUiState(
    /** This device's default policy as a toggle: `true` ⇒ keep files offline by
     *  default ([SyncPolicy.KEEP_OFFLINE]), `false` ⇒ online-only. Seeded `false`
     *  to match the [SyncPolicy.ONLINE_ONLY] migration default (§4.9). */
    val keepOfflineByDefault: Boolean = false,
    val offlineFiles: List<String> = emptyList(),
    /** This device's cache ceiling in bytes; `0` = no limit (the shipped default). */
    val maxLocalBytes: Long = FileSyncPreferences.MaxLocalBytes.default,
)

/**
 * The offerable cache ceilings. A **fixed short list**, not a slider or a free-text
 * field: the value only has to be roughly right, and a list is the one shape that
 * cannot produce a nonsense number (0 bytes, 3 KB, more than the disk).
 *
 * ⚠ `0` is first and means **no limit** — the shipped default. It is a real choice
 * the user can return to, not an "unset" state.
 */
object CacheCeilingChoices {
    const val NO_LIMIT: Long = 0L
    private const val GB: Long = 1_000_000_000L

    val values: List<Long> = listOf(NO_LIMIT, 1 * GB, 5 * GB, 10 * GB, 25 * GB, 50 * GB)

    /**
     * The choice a stored value maps onto. ⚠ Snaps to the nearest offered value at or
     * *below* the stored one (never above) so a ceiling written by another build — or
     * by a future slider — is never silently *raised* by opening this screen.
     */
    fun nearest(bytes: Long): Long =
        values.filter { it <= bytes }.maxOrNull() ?: NO_LIMIT
}
