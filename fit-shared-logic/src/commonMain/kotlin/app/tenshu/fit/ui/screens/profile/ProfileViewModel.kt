package app.tenshu.fit.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthFilePicker
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.FitProfile
import app.tenshu.fit.model.Goals
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.BatchImportSummary
import app.tenshu.fit.model.DataSourceRow
import kotlinx.coroutines.flow.update
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.LastUpdated
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Units
import kotlinx.datetime.LocalDate
import org.tenshu.preference.BiologicalSex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.bestEffort

data class ProfileUiState(
    val profile: FitProfile? = null,
    val goals: Goals = Goals(activeMinutes = 30, steps = 8000),
    /**
     * The *Data sources* section, already projected (DS-3 / D10). Every rule about which state a
     * row shows lives in `DataSourceProjection`; this carries the answer.
     *
     * ⚠⚠ Empty means **"nothing to show yet"**, never "you have no sources" — the screen must not
     * turn an unread registry into a claim about the user's realm.
     */
    val dataSources: List<DataSourceRow> = emptyList(),
    /**
     * The *Batch import* section, or null where this shell can pick no file — then the section is
     * **absent** rather than empty, because a batch-import row on a device that cannot import
     * files is a dead affordance (D11).
     */
    val batchImport: BatchImportSummary? = null,
    /**
     * The live import's own state. ⚠ Retained only for the Google Fit **notice** — the source
     * rows read their state from [dataSources], which already folds live and stored (§4.1).
     */
    val importStatus: ImportStatus = ImportStatus.Idle,
    /** Whether this platform can open a file picker at all — gates the notice's remedy. */
    val canImportFiles: Boolean = false,
)

/**
 * Profile (Ph3, docs/proposals/fit-app.md §2 screen 11): goals, personal data, units,
 * data sources. v1 derives the connected-sources list from the provenance actually
 * present in the workout set (the mock has Health Connect + file imports).
 */
/**
 * Guard rails on a hand-typed measurement. Deliberately wide — the job is to reject a slipped
 * decimal point or an empty-ish parse, not to police anybody's body; both ends are far outside
 * any human value. A rejected entry is simply not recorded (the dialog keeps the old reading).
 */
private val PLAUSIBLE_HEIGHT_CM = 30..280
private val PLAUSIBLE_WEIGHT_KG = 2.0..640.0

class ProfileViewModel(
    private val repository: FitRepository,
    /** Bound only by a shell that owns a window / Activity — see [HealthFilePicker]. */
    private val filePicker: HealthFilePicker? = null,
    /** Likewise shell-bound — the deep-history consent sheet needs an Activity. */
    private val permissionRequester: HealthPermissionRequester? = null,
    // ⚠ DS-6 / Q1: the auto-detection toggle moved into Tenshu Health's detail screen, so
    // `AutoDetectPreferences` moved with it to `DataSourceDetailViewModel`. It is that source's
    // own setting, and it is now also that row's *status* — Profile neither reads nor writes it.
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> get() = _uiState

    init {
        viewModelScope.launch {
            combine(
                repository.profile(),
                repository.goals(),
                repository.importStatus(),
            ) { profile, goals, importStatus ->
                Triple(profile, goals, importStatus)
            }.collect { (profile, goals, importStatus) ->
                // ⚠⚠ A field-wise `copy`, never a wholesale assignment. Two collectors write this
                // state, and the shape this replaced (`_uiState.value = next.copy(autoDetect = …)`)
                // only worked because someone remembered to carry the one other field forward by
                // hand. Each collector owning exactly its own fields makes that bug class
                // unrepresentable rather than merely uncommitted.
                _uiState.update {
                    it.copy(
                        profile = profile,
                        goals = goals,
                        importStatus = importStatus,
                        canImportFiles = filePicker != null,
                    )
                }
            }
        }
        // ⭐ `workouts()` is no longer read here at all. Profile used to derive its source rows by
        // scanning workout provenance — the heaviest read in the app, for a handful of badges —
        // and DS-1..DS-3 replaced that with a registry the store answers directly.
        viewModelScope.launch {
            combine(repository.dataSources(), repository.batchImport()) { sources, batch ->
                sources to batch
            }.collect { (sources, batch) ->
                _uiState.update { it.copy(dataSources = sources, batchImport = batch) }
            }
        }
    }

    /**
     * "Import from Google Takeout" — the only route by which real Google Fit *history* reaches the
     * app, since Fit never feeds Health Connect. The shell reads the bytes and calls
     * `repository.importFiles`, whose outcome flows back through [ProfileUiState.importStatus].
     */
    fun importFromFile() {
        filePicker?.pick()
    }

    /**
     * "Import from Apple Health" — the explicit tap that is the **only** first route to the
     * platform health source on iOS: `HealthKitImporter.autoRefreshReady` keeps every unattended
     * pass off until the read request has been answered once, so the consent sheet follows a user
     * action (App Review 5.1.3). A manual import (`automatic = false`) of every category, exactly
     * what Home's empty-state button runs; the outcome lands in [ProfileUiState.importStatus] and
     * the source row it registers.
     */
    fun importFromHealthSource() {
        viewModelScope.launch {
            bestEffort("FitProfile -> import from the health source") {
                repository.startImport(CategoryId.entries.toSet(), ImportWindow.Recent, automatic = false)
            }
        }
    }

    // ⚠ `importFullHistory()` and `openHealthSettings()` moved to `DataSourceDetailViewModel`
    // (DS-4). Both are Health Connect's business, and Profile no longer renders a row for either:
    // §4 puts them inside that source's detail screen, which is where the user is looking when
    // they want them.

    fun saveGoals(activeMinutes: Int, steps: Int) {
        viewModelScope.launch {
            repository.updateGoals(Goals(activeMinutes = activeMinutes.coerceAtLeast(1), steps = steps.coerceAtLeast(1)))
        }
    }

    // ── FP-3 profile edits (fit-app.md §18.6) ───────────────────────────────────────────
    //
    // Two destinations, deliberately: the declared fields are *preferences* while height and
    // weight are *measurements* written as `HealthSample` rows. Same screen, same dialogs, but a
    // weight entry joins the series Health Connect writes rather than shadowing it (§18.2).

    fun saveName(name: String) {
        viewModelScope.launch { repository.updateOwnerName(name.trim()) }
    }

    /** `null` clears back to "not set" — a real answer the user must be able to give again. */
    fun saveBirthDate(date: LocalDate?) {
        viewModelScope.launch { repository.updateBirthDate(date) }
    }

    fun saveSex(sex: BiologicalSex?) {
        viewModelScope.launch { repository.updateSex(sex) }
    }

    /** Records a measurement; ignores a nonsensical entry rather than storing it. */
    fun saveHeight(centimetres: Int) {
        if (centimetres !in PLAUSIBLE_HEIGHT_CM) return
        viewModelScope.launch { repository.recordHeight(centimetres) }
    }

    fun saveWeight(kilograms: Double) {
        if (kilograms !in PLAUSIBLE_WEIGHT_KG) return
        viewModelScope.launch { repository.recordWeight(kilograms) }
    }

    fun setUnits(units: Units) {
        viewModelScope.launch { repository.updateUnits(units) }
    }
}
