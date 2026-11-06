package app.tenshu.fit.ui.screens.datasource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.AutoDetectPreferences
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.data.NoopAutoDetectPreferences
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.DataSourceRow
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.ImportRunEntry
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.SharingReadiness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DataSourceDetailUiState(
    val kind: DataSourceKind,
    /**
     * The row this screen is about, or null while the registry is still being read.
     *
     * ⚠⚠ Null is **"loading"**, not "gone". A screen that treated it as absence would tell a user
     * their source had disappeared during the first frame after a cold start.
     */
    val row: DataSourceRow? = null,
    val history: List<ImportRunEntry> = emptyList(),
    /** The live import, for the in-flight case only — the row's own state is already folded. */
    val importStatus: ImportStatus = ImportStatus.Idle,
    val readiness: SharingReadiness = SharingReadiness(),
    val historyAccess: HistoryAccess = HistoryAccess.Unsupported,
    /**
     * Whether this shell can reach the platform's health settings. Mirrors the old Profile rule:
     * a remedy must never name a destination the shell cannot reach.
     */
    val canOpenHealthSettings: Boolean = false,
) {
    /** ⚠ Only this device can be told to import; another device's row offers the toggle only. */
    val canImportNow: Boolean get() = row?.canImportNow == true

    /**
     * Whether the deep-history grant is worth offering. Hidden outright where the platform has no
     * such gate — a disabled row would raise a question nothing in the app can answer.
     */
    val canAskForFullHistory: Boolean
        get() = canImportNow && historyAccess != HistoryAccess.Unsupported

    /** Whether the sharing check has anything to say. */
    val hasSharingWork: Boolean get() = canOpenHealthSettings && readiness.hasAnythingToFix
}

/**
 * One source's detail screen (`fit-data-sources-restructure.md` §4) — what Profile's Health
 * Connect row now opens into.
 *
 * It is where three top-level rows went: *Import your full history*, *Check sharing setup* and the
 * Google Fit notice are all Health Connect's business, and they sat beside the source only because
 * the source row had no destination to hold them.
 *
 * ⚠ [deviceId] is null for [DataSourceKind.TenshuHealth], which is a projection over this device's
 * own recordings rather than a registry row (D9/D11).
 */
class DataSourceDetailViewModel(
    private val kind: DataSourceKind,
    private val deviceId: String?,
    private val repository: FitRepository,
    private val permissionRequester: HealthPermissionRequester? = null,
    /**
     * Fit's own detection setting (FA-2 D1) — realm-scoped, so it reflects the answer given on
     * any device.
     *
     * ⚠ Q1: this control used to be its own *Automatic* section on Profile. It lives here now
     * because it is Tenshu Health's own setting, and because it is also that row's status —
     * there is no import for that source to report on.
     */
    private val autoDetect: AutoDetectPreferences = NoopAutoDetectPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DataSourceDetailUiState(
            kind = kind,
            canOpenHealthSettings = permissionRequester != null,
        )
    )
    val uiState: StateFlow<DataSourceDetailUiState> get() = _uiState

    init {
        // Platform state that changes while the app is backgrounded — the user can grant or revoke
        // inside Health Connect's own settings — so re-probe on construction rather than trusting
        // whatever the last import left behind.
        viewModelScope.launch { repository.refreshHistoryAccess() }
        viewModelScope.launch { repository.refreshSharingReadiness() }

        viewModelScope.launch {
            combine(
                repository.dataSources(),
                repository.importHistory(kind),
                repository.importStatus(),
            ) { rows, history, status -> Triple(rows, history, status) }
                .collect { (rows, history, status) ->
                    _uiState.update {
                        it.copy(
                            // Matched on (kind, device) rather than kind alone: with two phones in
                            // the realm there are two Health Connect rows, and picking the first
                            // would open one device's screen showing another's figures.
                            row = rows.firstOrNull { r ->
                                r.kind == kind && (deviceId == null || r.deviceId == deviceId)
                            },
                            history = history,
                            importStatus = status,
                        )
                    }
                }
        }
        viewModelScope.launch {
            combine(repository.sharingReadiness(), repository.historyAccess()) { r, h -> r to h }
                .collect { (readiness, access) ->
                    _uiState.update { it.copy(readiness = readiness, historyAccess = access) }
                }
        }
    }

    /** Re-probes what only a look can tell us. The screen calls this on every resume. */
    fun refreshOnResume() {
        viewModelScope.launch { repository.refreshSharingReadiness() }
        viewModelScope.launch { repository.refreshHistoryAccess() }
    }

    /**
     * "Import now" — a **manual** import, so it runs even where automatic passes are switched off
     * (D4). That is the whole distinction the toggle draws: off means "stop doing this by
     * yourself", not "stop importing".
     */
    fun importNow() {
        if (!_uiState.value.canImportNow) return
        viewModelScope.launch { repository.startImport(CategoryId.entries.toSet()) }
    }

    /**
     * "Import your full history" — asks for the deep-history grant (§15).
     *
     * Already-[HistoryAccess.Granted] is not a no-op: the grant is in place and the user is asking
     * for the deep read itself, so run it.
     */
    fun importFullHistory() {
        when (_uiState.value.historyAccess) {
            HistoryAccess.Granted -> viewModelScope.launch {
                repository.startImport(CategoryId.entries.toSet(), ImportWindow.FullHistory)
            }
            HistoryAccess.Grantable -> permissionRequester?.requestFullHistory()
            HistoryAccess.Unsupported -> Unit
        }
    }

    /**
     * Flips the automatic-import switch (D4).
     *
     * ⚠ Works for a source on **another** device: the flag is ours and it syncs, and that device
     * honours it on its next foreground. There is no push, so nothing here waits for it.
     */
    fun setAutoImport(enabled: Boolean) {
        // ⚠ Tenshu Health has no registry row and nothing to import — its switch is the
        // detection preference (Q1). Same control on screen, different thing underneath, which
        // is why this branches here rather than in the view.
        if (kind == DataSourceKind.TenshuHealth) {
            viewModelScope.launch { autoDetect.setEnabled(enabled) }
            return
        }
        val id = _uiState.value.row?.deviceId ?: return
        viewModelScope.launch { repository.setSourceAutoImport(kind, id, enabled) }
    }

    /** Navigation, not a request — the missing grant belongs to another app. */
    fun openHealthSettings() {
        permissionRequester?.openHealthSettings()
    }
}
