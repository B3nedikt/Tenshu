package app.tenshu.fit.ui.screens.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.DraftEdits
import app.tenshu.fit.data.FinishedRecording
import app.tenshu.fit.data.RecordingDraft
import app.tenshu.fit.data.RecordingEngine
import app.tenshu.fit.data.RecordingState
import app.tenshu.fit.data.TrimSuggestion
import app.tenshu.fit.data.trimmed
import app.tenshu.fit.data.WorkoutSaveOutcome
import app.tenshu.fit.data.SensorStatus
import app.tenshu.fit.data.detectIdleTrim
import app.tenshu.fit.model.ActivityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** The two live numeral treatments (fit-agent.md §1.4): one dominant metric vs 2×2 grid. */
enum class LiveLayout { Hero, Grid }

data class RecordUiState(
    val selectedActivity: ActivityType = ActivityType.Running,
    val sensors: List<SensorStatus> = emptyList(),
    val recording: RecordingState = RecordingState.Idle,
    val liveLayout: LiveLayout = LiveLayout.Hero,
    /** Set once Finish persists — the UI navigates to the workout detail and clears it. */
    val finishedWorkoutId: String? = null,
    /**
     * Finish journalled the workout on this device but Control was unreachable
     * ([WorkoutSaveOutcome.SavedLocally]) — the session has ended safely; the UI shows the
     * saved-locally notice and leaves the live screen.
     */
    val savedLocally: Boolean = false,
    /**
     * The save failed outright (no durable journal bound) — the engine kept the session (Active
     * pre-RC-2 / Reviewing after a failed commit); the UI shows the retry affordance and stays.
     */
    val saveFailed: Boolean = false,
    /** The review screen's title edit (RC-2); blank = keep the fallback naming. */
    val draftTitle: String = "",
    /** The review screen's activity-type correction (RC-2); null = keep the draft's. */
    val draftActivity: ActivityType? = null,
    /** The RC-3 idle-trim suggestion for the current draft; null = nothing to suggest. */
    val trimSuggestion: TrimSuggestion? = null,
    /** Whether the user applied the leading-idle trim (RC-3; suggestion-only, never automatic). */
    val trimStartApplied: Boolean = false,
    /** Whether the user applied the trailing-idle trim. */
    val trimEndApplied: Boolean = false,
) {
    /** The applied window start in track time, or null while un-applied/un-suggested. */
    val appliedTrimStartSec: Int? get() = trimSuggestion?.trimStartSec?.takeIf { trimStartApplied }

    /** The applied window end in track time, or null while un-applied/un-suggested. */
    val appliedTrimEndSec: Int? get() = trimSuggestion?.trimEndSec?.takeIf { trimEndApplied }

    /**
     * The Reviewing draft with the applied trim previewed (RC-3) — what the review surface
     * renders and exactly what Save persists; null outside review. Exposed on the state (rather
     * than screens calling `trimmed` themselves) so the SwiftUI review surface gets the preview
     * without reaching for a non-exported top-level extension across the framework boundary.
     */
    val reviewPreview: RecordingDraft?
        get() = (recording as? RecordingState.Reviewing)?.draft?.trimmed(appliedTrimStartSec, appliedTrimEndSec)
}

/**
 * Record (Ph3, docs/proposals/fit-app.md §2 screens 7+8): the pre-start picker + sensor
 * rows, and the always-dark live surface. One VM spans both — the live screen is pushed
 * on Start and observes the same engine state.
 */
class RecordViewModel(
    private val engine: RecordingEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> get() = _uiState

    init {
        engine.armSensors()
        viewModelScope.launch {
            engine.sensors().collect { v -> _uiState.update { it.copy(sensors = v) } }
        }
        viewModelScope.launch {
            engine.state().collect { v ->
                _uiState.update {
                    // A fresh draft entering review resets the edit fields — the draft may have
                    // been produced outside this VM (the notification's Stop action, RC-1) —
                    // and computes the RC-3 idle-trim suggestion once, from the captured fixes.
                    val enteringReview = v is RecordingState.Reviewing && it.recording !is RecordingState.Reviewing
                    it.copy(
                        recording = v,
                        draftTitle = if (enteringReview) "" else it.draftTitle,
                        draftActivity = if (enteringReview) null else it.draftActivity,
                        trimSuggestion = if (enteringReview) {
                            detectIdleTrim(v.reviewingRoute(), v.reviewingDurationSec())
                        } else {
                            it.trimSuggestion
                        },
                        trimStartApplied = if (enteringReview) false else it.trimStartApplied,
                        trimEndApplied = if (enteringReview) false else it.trimEndApplied,
                    )
                }
            }
        }
    }

    /**
     * Re-arm the sensors after a runtime-permission grant (the Android JIT flow, §9.8) so the
     * pre-start rows flip GPS / steps / HR to Ready without re-entering the screen. Idempotent.
     */
    fun rearmSensors() = engine.armSensors()

    /**
     * Opt into a BLE heart-rate strap — the just-in-time "Connect" action on the HR sensor row
     * (§11.2). Delegates to the engine; a no-op on engines that scan for HR in [armSensors].
     */
    fun connectHeartRate() = engine.connectHeartRate()

    fun selectActivity(type: ActivityType) = _uiState.update { it.copy(selectedActivity = type) }

    fun toggleLiveLayout() = _uiState.update {
        it.copy(liveLayout = if (it.liveLayout == LiveLayout.Hero) LiveLayout.Grid else LiveLayout.Hero)
    }

    fun start() = engine.start(_uiState.value.selectedActivity)

    fun pause() = engine.pause()

    fun resume() = engine.resume()

    /**
     * Ends capture and parks the session as a Reviewing draft (RC-2) — the shell navigates to the
     * review screen off the state flip. Nothing is saved until [save].
     */
    fun stop() {
        engine.stop()
    }

    /** Updates the review screen's title edit. */
    fun setDraftTitle(value: String) = _uiState.update { it.copy(draftTitle = value) }

    /** Updates the review screen's activity-type correction. */
    fun setDraftActivity(type: ActivityType) = _uiState.update { it.copy(draftActivity = type) }

    /** Applies/undoes the leading-idle trim (RC-3's per-end toggle on the suggestion card). */
    fun setTrimStartApplied(applied: Boolean) = _uiState.update { it.copy(trimStartApplied = applied) }

    /** Applies/undoes the trailing-idle trim. */
    fun setTrimEndApplied(applied: Boolean) = _uiState.update { it.copy(trimEndApplied = applied) }

    /** Commits the Reviewing draft with the review edits applied (RC-2's Save). */
    fun save() {
        val s = _uiState.value
        val edits = DraftEdits(
            title = s.draftTitle.trim().takeIf { it.isNotEmpty() },
            activityType = s.draftActivity,
            trimStartSec = s.appliedTrimStartSec,
            trimEndSec = s.appliedTrimEndSec,
        )
        viewModelScope.launch { persist { engine.commit(edits) } }
    }

    /**
     * One-shot stop-and-save with no edits — kept for the iOS RecordView, which has no review
     * surface yet (fit-app.md §13.6). Compose flows go [stop] → review → [save].
     */
    fun finish() {
        viewModelScope.launch { persist { engine.finish() } }
    }

    private suspend fun persist(commit: suspend () -> FinishedRecording?) {
        // With the durable outbox bound (Android/desktop) a save against an unreachable
        // Control returns SavedLocally instead of throwing — the session ends, the journal
        // replays later. The throw path remains for repositories without a journal: the
        // engine keeps the draft (Idle only after a successful save), so Save retries.
        // Without the catch the VM scope would crash instead.
        val finished = try {
            commit() ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("RecordViewModel: saving the recorded workout failed — session retained for retry (${e.message})")
            _uiState.update { it.copy(saveFailed = true) }
            return
        }
        when (finished.outcome) {
            WorkoutSaveOutcome.Synced ->
                _uiState.update { it.copy(finishedWorkoutId = finished.workout.workoutId, saveFailed = false) }
            WorkoutSaveOutcome.SavedLocally ->
                _uiState.update { it.copy(savedLocally = true, saveFailed = false) }
        }
    }

    fun discard() = engine.discard()

    /** The UI acknowledges the finish navigation so re-entering Record starts clean. */
    fun consumeFinished() = _uiState.update { it.copy(finishedWorkoutId = null) }

    /** The UI acknowledges the saved-locally notice (and leaves the live screen). */
    fun consumeSavedLocally() = _uiState.update { it.copy(savedLocally = false) }

    /** Dismisses the save-failed notice; the session stays live for another Finish. */
    fun dismissSaveFailed() = _uiState.update { it.copy(saveFailed = false) }
}

private fun RecordingState.reviewingRoute() =
    (this as? RecordingState.Reviewing)?.draft?.track?.route.orEmpty()

private fun RecordingState.reviewingDurationSec() =
    (this as? RecordingState.Reviewing)?.draft?.workout?.durationSec ?: 0
