package app.tenshu.fit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.AutoDetectPreferences
import app.tenshu.fit.data.DetectedWorkoutPromoter
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.NoopAutoDetectPreferences
import app.tenshu.fit.data.WorkoutPromotion
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.DetectedMovement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * The one-time introduction to automatic logging (`fit-automatic-activity-detection.md` D1).
 *
 * ⚠⚠ **Fires at the first would-be detection, not at onboarding**, and the difference is the
 * whole design. Its force is concreteness — *"We noticed a 32-minute walk this morning"* — and at
 * onboarding there is no trace to point at, Timeline may not even be installed, and the user is
 * already deciding about imports. An abstract "log activities automatically?" at setup time is
 * exactly the prompt people click past.
 *
 * ⚠ Shown **once per device, ever**. Not once per detection — a prompt seen three times a week
 * becomes a prompt dismissed unread, which is more friction than Google Fit, not less.
 */
class FirstDetectionViewModel(
    private val repository: FitRepository,
    private val preferences: AutoDetectPreferences = NoopAutoDetectPreferences,
    private val promoter: DetectedWorkoutPromoter = DetectedWorkoutPromoter(repository),
) : ViewModel() {

    /** The movement the prompt names, or `null` when there is nothing to ask about. */
    data class Candidate(
        val activityType: ActivityType,
        val durationSec: Int,
        /** `null` for a step-derived bout, which has no distance to report. */
        val distanceM: Int?,
        val startAtEpochMs: Long,
    )

    private val _candidate = MutableStateFlow<Candidate?>(null)
    val candidate: StateFlow<Candidate?> get() = _candidate

    private var pending: DetectedMovement? = null

    init {
        viewModelScope.launch {
            runCatchingCancellable { evaluate() }
                .onFailure { println("[fit] first-detection prompt check failed: $it") }
        }
    }

    private suspend fun evaluate() {
        // ⚠ `promptAnswered()` also reports true while this device has no sync id, which defers
        // the prompt rather than showing one whose answer cannot be recorded. See its KDoc.
        if (preferences.promptAnswered()) return log("already answered on this device (or no sync id yet, so deferred)")
        // Already on? Then the user has answered somewhere, and asking again would be noise.
        if (preferences.enabledOnce()) return log("auto-detection is already on")

        // ⚠ Only a *promotable* movement earns the prompt. Asking about a car journey, or a
        // 40-second corridor wander that would never be logged, would introduce the feature with
        // an example of it doing the wrong thing.
        val traces = repository.detectedMovements().first()
        val newest = traces
            .firstOrNull { WorkoutPromotion.decide(it) is WorkoutPromotion.Decision.Promote }
            // ⚠ Nothing to ask about — and deliberately NOT marked answered.
            ?: return log(
                if (traces.isEmpty()) "no movement traces are readable"
                else "0 of ${traces.size} trace(s) are workout material"
            )

        pending = newest
        _candidate.value = Candidate(
            activityType = (WorkoutPromotion.decide(newest) as WorkoutPromotion.Decision.Promote).activityType,
            durationSec = newest.durationSec,
            distanceM = newest.distanceM,
            startAtEpochMs = newest.startAtEpochMs,
        )
    }

    /** "Log these automatically" — turn it on, and promote the backlog now rather than next launch. */
    fun logAutomatically() {
        answer { preferences.setEnabled(true); promoteAll() }
    }

    /** "Just this one" — log the movement named, leave the setting off. */
    fun justThisOne() {
        answer { pending?.let { promoter.promote(listOf(it), emptyList(), beforeEachWrite = USER_ACT) } }
    }

    /** "No thanks" — record that we asked, change nothing else. */
    fun notNow() {
        answer { }
    }

    /**
     * Every answer marks the prompt answered and closes it — including "no thanks", which is the
     * point: the promise is *asked once*, not *asked until yes*.
     *
     * ⚠ The dismissal happens first so a failing write cannot leave the dialog stuck on screen.
     */
    private fun answer(action: suspend () -> Unit) {
        _candidate.value = null
        viewModelScope.launch {
            runCatchingCancellable {
                action()
                preferences.markPromptAnswered()
            }.onFailure { println("[fit] first-detection prompt answer failed: $it") }
        }
    }

    /**
     * ⚠⚠ Says why on **every** path that does not show a prompt. There are four of them, they
     * are indistinguishable from outside, and one — "nothing to ask about" — deliberately does
     * not mark the prompt answered, so a silent version leaves no way to tell a working launch
     * from a broken one. The first on-device pass hit exactly that wall.
     */
    private fun log(reason: String) = println("[fit] first-detection: no prompt — $reason")

    private suspend fun promoteAll() {
        val movements = repository.detectedMovements().first()
        val existing = runCatchingCancellable { repository.workouts().first() }.getOrDefault(emptyList())
        promoter.promote(movements, existing, beforeEachWrite = USER_ACT)
    }

    private companion object {
        /**
         * Both promotions here are the user's answer to the prompt — a user act, which the realm
         * gate does not hold (ADR 0025 §8.6: the shell, and with it this prompt, is replaced while
         * the realm is unknown or absent). Explicit, so the open gate is a decision, not a default.
         */
        val USER_ACT: suspend () -> Unit = app.tenshu.reachability.OPEN_REALM_GATE
    }
}
