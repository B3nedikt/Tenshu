package app.tenshu.fit.ui.screens.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.WorkoutRead
import app.tenshu.fit.data.RegionBundleResolver
import app.tenshu.fit.data.RouteRegionStream
import app.tenshu.fit.model.Workout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

sealed interface WorkoutDetailUiState {
    data object Loading : WorkoutDetailUiState
    data class Loaded(
        val workout: Workout,
        /** The staged whole-region `.pmtiles` for this workout's GPS route, or null → demo. */
        val regionBundlePath: String? = null,
        /** The D10 streamed source for this workout's route (preferred over [regionBundlePath]). */
        val regionStream: RouteRegionStream? = null,
    ) : WorkoutDetailUiState
    /**
     * The store answered and has no such row. ⚠ Only a genuine absence may land here — a read
     * that did not complete is [Failed]. Conflated, a `WorkoutStreamsCodec` throw presents as
     * *every workout is missing*.
     */
    data object NotFound : WorkoutDetailUiState

    /** The read did not complete. Says nothing about whether the row exists — so neither may the screen. */
    data object Failed : WorkoutDetailUiState

    /** The row is gone — the screen's cue to pop back to where it was opened from. */
    data object Deleted : WorkoutDetailUiState
}

/**
 * Workout detail (docs/proposals/fit-app.md §2 screen 6).
 *
 * [regions] resolves the workout's GPS route to an offline-basemap region path (render-hop
 * terminus, `maps-region-delivery.md` §7); it is **optional/best-effort** — absent in the
 * standalone graph, a resolution failure never blocks the workout loading, the route just renders
 * on the bundled demo basemap (or the schematic when there's no GPS).
 */
class WorkoutDetailViewModel(
    private val repository: FitRepository,
    private val regions: RegionBundleResolver?,
    private val workoutId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow<WorkoutDetailUiState>(WorkoutDetailUiState.Loading)
    val uiState: StateFlow<WorkoutDetailUiState> get() = _uiState

    private val _deleteFailed = MutableStateFlow(false)

    /** Set when a delete was attempted and did not happen — the screen says so and stays put. */
    val deleteFailed: StateFlow<Boolean> get() = _deleteFailed

    /**
     * Deletes this workout. On success the state becomes [WorkoutDetailUiState.Deleted] and the
     * screen pops; on failure it stays [WorkoutDetailUiState.Loaded] and [deleteFailed] is raised.
     *
     * ⚠ The failure branch is the point. A repository with no proxy behind it returns `false`
     * (`FitRepository.deleteWorkout`), and popping anyway would show the user a workout vanishing
     * from a screen while the row is still in the store — the affordance would be lying.
     */
    fun delete() {
        viewModelScope.launch {
            if (repository.deleteWorkout(workoutId)) {
                _uiState.value = WorkoutDetailUiState.Deleted
            } else {
                _deleteFailed.value = true
            }
        }
    }

    /** Dismisses the failure notice (the user acknowledged it). */
    fun clearDeleteFailure() {
        _deleteFailed.value = false
    }

    init {
        load()
    }

    /** Re-runs the detail read. The [WorkoutDetailUiState.Failed] screen's retry affordance. */
    fun retry() {
        _uiState.value = WorkoutDetailUiState.Loading
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val workout = when (val read = repository.workout(workoutId)) {
                is WorkoutRead.Found -> read.workout
                // ⚠ The two failure branches are deliberately NOT merged: `Failed` must not say
                // the row is missing, which is exactly what a single `null` would make it say.
                WorkoutRead.NotFound -> {
                    _uiState.value = WorkoutDetailUiState.NotFound
                    return@launch
                }
                WorkoutRead.Failed -> {
                    _uiState.value = WorkoutDetailUiState.Failed
                    return@launch
                }
            }
            // Show the workout immediately (schematic / demo basemap), then upgrade to its
            // downloaded region once the resolver has staged it (drain dispatching lives in the
            // resolver impl, so this VM stays test-synchronous).
            _uiState.value = WorkoutDetailUiState.Loaded(workout)
            val geoRoute = workout.geoRoute
            if (geoRoute.size >= 2 && regions != null) {
                // Prefer the D10 streamed source (fetch only the route's covering tiles); fall back
                // to a fully-downloaded region file only when no stream is available.
                val stream = runCatchingCancellable { regions.streamHandle(geoRoute) }.getOrNull()
                if (stream != null) {
                    _uiState.value = WorkoutDetailUiState.Loaded(workout, regionStream = stream)
                } else {
                    val path = runCatchingCancellable { regions.bundlePath(geoRoute) }.getOrNull()
                    if (path != null) {
                        _uiState.value = WorkoutDetailUiState.Loaded(workout, path)
                    }
                }
            }
        }
    }
}
