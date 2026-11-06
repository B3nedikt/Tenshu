package app.tenshu.fit.data

import app.tenshu.fit.model.Goals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.set

/**
 * The proxy-backed [FitGoalPreferences] — Fit's daily targets as REALM preference rows
 * (`docs/proposals/fit-app.md` §18.5), so a goal set on the phone is the goal the desktop shows.
 */
class TenshuFitGoalPreferences(private val client: TenshuClient) : FitGoalPreferences {

    override fun observe(): Flow<Goals> {
        val prefs = client.preferenceService
        // ⚠ `read()` stays silent for a realm that has never held the row, so seed the declared
        // defaults or a fresh install renders no goals at all (core-preference-service §6.5).
        return combine(
            prefs.read(FitPreferences.GoalActiveMinutes)
                .onStart { emit(FitPreferences.GoalActiveMinutes.default) },
            prefs.read(FitPreferences.GoalSteps)
                .onStart { emit(FitPreferences.GoalSteps.default) },
        ) { activeMinutes, steps -> Goals(activeMinutes = activeMinutes, steps = steps) }
    }

    override suspend fun set(goals: Goals) {
        client.preferenceService.set(FitPreferences.GoalActiveMinutes, goals.activeMinutes)
        client.preferenceService.set(FitPreferences.GoalSteps, goals.steps)
    }
}
