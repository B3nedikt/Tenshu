package app.tenshu.fit.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.HealthPermissionRequester
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.FitFormat
import app.tenshu.fit.model.Goals
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportStatus
import app.tenshu.fit.model.Workout
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.liststate.ListLoadState
import app.tenshu.fit.data.waitingWillFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.bestEffort

data class HomeUiState(
    /**
     * Which of loading / loaded / failed the aggregates read is in.
     *
     * Replaces the `loaded: Boolean` this shipped with, which had the right idea — distinguish the
     * first frame from a genuinely empty store, so Home does not flash its empty state on launch —
     * but only two of the three states. The third is the one that mattered: a *failed* read folded
     * to `emptyList()` and rendered as `loaded = true, today = null`, i.e. as the confident claim
     * that the user did nothing today. See `ListLoadState`.
     */
    val load: ListLoadPhase = ListLoadPhase.LOADING,
    /** Today's aggregate — the goal ring + stat strip. Null until the repo emits. */
    val today: DailyAggregate? = null,
    val goals: Goals = Goals(activeMinutes = 30, steps = 8000),
    /** Trailing 7 days incl. today, oldest first — the trend bars. */
    val week: List<DailyAggregate> = emptyList(),
    /** Active-minutes delta vs the week before, percent; null without a previous week. */
    val weekDeltaPercent: Int? = null,
    val latestWorkout: Workout? = null,
    /**
     * What the last import concluded. Rendered as a banner when it needs attention — an empty
     * Home is only *self-explanatory* if the app also says why it is empty.
     */
    val importStatus: ImportStatus = ImportStatus.Idle,
    /**
     * Whether this device has a platform health source to import from (Health Connect / Apple
     * Health). False on a phone without Health Connect and on desktop, where the empty state must
     * say so rather than invite the user to "connect a health source" that cannot exist — and
     * where, before `fit-app.md` §9.15, the app quietly served sample data instead.
     */
    val healthSourceAvailable: Boolean = true,
    /** Whether the shell can pick a file to import (Takeout / GPX / TCX) — the empty state's second action. */
    val canImportFiles: Boolean = false,
)

/**
 * Home (Ph3, docs/proposals/fit-app.md §2 screen 4): Active Minutes ring, stat strip,
 * 7-day trend + delta, latest workout, trust cue. All week math happens here so the
 * screen renders plain values.
 */
class HomeViewModel(
    private val repository: FitRepository,
    /**
     * Bound only where the platform can prompt (Android → Health Connect). Absent elsewhere, in
     * which case a retry is just a re-import — see [HealthPermissionRequester].
     */
    private val permissionRequester: HealthPermissionRequester? = null,
    /** True where a `HealthFilePicker` is bound — see [HomeUiState.canImportFiles]. */
    private val canImportFiles: Boolean = false,
) : ViewModel() {

    /**
     * Read once: whether the platform has a health store does not change while the app runs (an
     * install of Health Connect takes the user out to the Play Store and back through a relaunch).
     */
    private val healthSourceAvailable: Boolean =
        repository.availableSources().any { it == ImportSource.HealthConnect || it == ImportSource.AppleHealth }

    private val _uiState = MutableStateFlow(
        HomeUiState(healthSourceAvailable = healthSourceAvailable, canImportFiles = canImportFiles),
    )
    val uiState: StateFlow<HomeUiState> get() = _uiState

    /**
     * Whether waiting will fix a failed read (`core-schema-skew-surface.md` §8.6). Drives the
     * unavailable state's wording: "Still trying…" is true of a transport outage and false of a
     * schema refusal, which no amount of waiting resolves.
     */
    val waitingWillFix: StateFlow<Boolean> = repository.waitingWillFix()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            combine(
                repository.aggregates(),
                repository.goals(),
                repository.workouts(),
                repository.importStatus(),
            ) { aggregatesLoad, goals, workouts, importStatus ->
                // ⚠ A failed read keeps the previous figures and only changes the phase. Rebuilding
                // the state from `emptyList()` here would paint 0 steps / 0 active minutes over
                // real data — the ring reading zero is a *claim about the user's day*, not a
                // neutral placeholder.
                val previous = _uiState.value
                when (aggregatesLoad) {
                    ListLoadState.Loading -> previous.copy(load = ListLoadPhase.LOADING)
                    ListLoadState.Failed -> previous.copy(
                        load = ListLoadPhase.FAILED,
                        // These two do not come from the aggregates read, so they stay live.
                        latestWorkout = workouts.firstOrNull(),
                        importStatus = importStatus,
                    )
                    is ListLoadState.Loaded -> {
                        val aggregates = aggregatesLoad.items
                        val week = aggregates.takeLast(7)
                        val previousWeek = aggregates.dropLast(7).takeLast(7)
                        HomeUiState(
                            load = ListLoadPhase.LOADED,
                            today = aggregates.lastOrNull(),
                            goals = goals,
                            week = week,
                            weekDeltaPercent = FitFormat.weekDeltaPercent(
                                current = week.sumOf { it.activeMinutes },
                                previous = previousWeek.sumOf { it.activeMinutes },
                            ),
                            latestWorkout = workouts.firstOrNull(),
                            importStatus = importStatus,
                            healthSourceAvailable = healthSourceAvailable,
                            canImportFiles = canImportFiles,
                        )
                    }
                }
            }.collect { _uiState.value = it }
        }
    }

    /**
     * The banner's "Try again". When the blocker is a missing grant, re-importing would fail for
     * exactly the same reason — so ask for the permission instead, and let the shell import once the
     * user has answered. Every other state is genuinely retryable (the source may have gained data;
     * a failure may have been transient). Outcomes land back in [HomeUiState.importStatus].
     */
    fun retryImport() {
        val requester = permissionRequester
        val status = _uiState.value.importStatus
        if (status is ImportStatus.PermissionsMissing && requester != null) {
            requester.request()
            return
        }
        // ⚠ A manual import the realm gate refused carries its own request (ADR 0025 §8.6): retry
        // exactly that, not the all-category default below.
        if (status is ImportStatus.WaitingForControl) {
            viewModelScope.launch {
                bestEffort("FitHome -> the retried held import") {
                    repository.startImport(status.selection, status.window)
                }
            }
            return
        }
        viewModelScope.launch {
            // startImport records its own failure as ImportStatus.Failed and rethrows; the throw has
            // nowhere useful to go from a button press, so it stops here rather than felling the VM.
            bestEffort("FitHome -> the retried import") { repository.startImport(CategoryId.entries.toSet()) }
        }
    }
}
