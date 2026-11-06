package app.tenshu.fit.data

import app.tenshu.fit.model.Goals
import app.tenshu.fit.model.Units
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.IntAdapter
import org.tenshu.preference.MeasurementSystem
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope

/**
 * Fit's own preference declarations (`docs/proposals/fit-app.md` §18.5).
 *
 * **Goals, and only goals.** The rest of what Profile shows is not Fit's: name, date of birth, sex
 * and units describe *the user* and live in `OwnerPreferences` (`:preference-service`), while
 * height and weight are **measurements** read from `HealthSample` rows rather than settings — a
 * preference row beside a synced measurement diverges from it on the first import (§18.2). A daily
 * step target, by contrast, means nothing outside Fit.
 *
 * REALM-scoped: a goal is the user's intent, not a property of the device they set it on.
 */
object FitPreferences {

    val GoalActiveMinutes = Preference(
        id = "fit.goals.activeMinutes",
        scope = PreferenceScope.REALM,
        default = 30,
        adapter = IntAdapter,
    )

    val GoalSteps = Preference(
        id = "fit.goals.steps",
        scope = PreferenceScope.REALM,
        default = 8000,
        adapter = IntAdapter,
    )

    /** The declared defaults as a [Goals], so no caller re-states 30/8000. */
    val defaultGoals = Goals(activeMinutes = GoalActiveMinutes.default, steps = GoalSteps.default)

    /**
     * Log walks, runs and rides Timeline captured, without being asked each time
     * (`fit-automatic-activity-detection.md` D1).
     *
     * REALM — a want, not a device capability. Turn it on once and a newly paired phone inherits
     * the answer. ⚠ Default **off**: this writes health rows the user did not ask for, so it
     * begins where the prompt can still introduce it rather than where it has already acted.
     */
    val AutoDetectActivities = Preference(
        id = "fit.autoDetect.enabled",
        scope = PreferenceScope.REALM,
        default = false,
        adapter = BooleanAdapter,
    )

    /**
     * Whether the one-time prompt has been answered **on this device**.
     *
     * ⚠⚠ DEVICE, deliberately, and this is the one place the two scopes diverge. Detection is a
     * per-device capability — this phone's pedometer, this phone's Timeline install — so a newly
     * paired device that can newly detect things deserves to be told once, even though
     * [AutoDetectActivities] already carries the answer. Realm-scoping this would mean a device
     * that gained the capability is never introduced to it.
     *
     * ⚠ Its writer must **refuse rather than substitute** when no device id is obtainable (the
     * `CalendarDeviceIdentity` incident): a row written under the wrong key silently changes some
     * *other* device's answer. The prompt therefore waits for an id instead of asking and
     * forgetting — see `AutoDetectPreferences`.
     */
    val AutoDetectPromptAnswered = Preference(
        id = "fit.autoDetect.promptAnswered",
        scope = PreferenceScope.DEVICE,
        default = false,
        adapter = BooleanAdapter,
    )
}

/**
 * The auto-detection preference pair, persisted (`fit-automatic-activity-detection.md` D1).
 *
 * Two scopes on purpose: [enabled] is realm-wide (the want), [promptAnswered] is per-device
 * (whether *this* device has introduced the feature). See the declarations above.
 */
interface AutoDetectPreferences {
    fun enabled(): Flow<Boolean>

    /**
     * A one-shot read of [enabled].
     *
     * ⚠⚠ **Not `enabled().first()`.** The reactive flow is seeded with the preference default so
     * a fresh realm renders a toggle at all, and `first()` therefore returns that **seed** rather
     * than the stored value — auto-detection read as off forever, on a device where the user had
     * switched it on. Found by logging on the first on-device pass, not by any test. A one-shot
     * caller needs a one-shot read (`readOnce`), never the head of a reactive stream.
     */
    suspend fun enabledOnce(): Boolean

    suspend fun setEnabled(value: Boolean)

    /**
     * `true` once this device has shown the prompt and got an answer.
     *
     * ⚠⚠ Also `true` when the answer **cannot be recorded** — when this device has no obtainable
     * sync id yet. That is not a lie by accident: it is what stops the prompt appearing during
     * the identity race, being answered, and reappearing next launch because the write no-op'd.
     * Asking is deferred until the answer can be remembered, which for a once-per-lifetime prompt
     * costs nothing.
     */
    suspend fun promptAnswered(): Boolean
    suspend fun markPromptAnswered()
}

/** For previews, tests, and any surface with no proxy to reach. */
object NoopAutoDetectPreferences : AutoDetectPreferences {
    override fun enabled(): Flow<Boolean> = flowOf(FitPreferences.AutoDetectActivities.default)
    override suspend fun enabledOnce(): Boolean = FitPreferences.AutoDetectActivities.default
    override suspend fun setEnabled(value: Boolean) = Unit
    // ⚠ `true` — a surface with no proxy cannot remember an answer, so it must not ask.
    override suspend fun promptAnswered(): Boolean = true
    override suspend fun markPromptAnswered() = Unit
}

/**
 * Fit's daily targets, persisted per realm.
 *
 * The interface lives here beside the declarations; the proxy-backed implementation lives in
 * `:fit-shared-data` with the rest of the client-facing plumbing, so this module keeps its
 * Compose-free, client-free shape.
 */
interface FitGoalPreferences {
    fun observe(): Flow<Goals>
    suspend fun set(goals: Goals)
}

/** For previews, tests, and any surface with no proxy to reach. */
object NoopFitGoalPreferences : FitGoalPreferences {
    override fun observe(): Flow<Goals> = flowOf(FitPreferences.defaultGoals)
    override suspend fun set(goals: Goals) = Unit
}

// ── Units: the platform value ↔ Fit's presentation enum ───────────────────────────────
//
// Two enums for one concept, deliberately. [MeasurementSystem] is the stored, platform-level
// answer — Timeline will read the same row without importing anything of Fit's (§18.2 / FP-6) —
// while [Units] stays Fit's presentation type, which every screen, formatter and the iOS
// `ProfileView` already speak. Mapping at this one boundary keeps the platform row authoritative
// without a rename sweep through the UI.

fun MeasurementSystem.toFitUnits(): Units = when (this) {
    MeasurementSystem.METRIC -> Units.Metric
    MeasurementSystem.IMPERIAL -> Units.Imperial
}

fun Units.toMeasurementSystem(): MeasurementSystem = when (this) {
    Units.Metric -> MeasurementSystem.METRIC
    Units.Imperial -> MeasurementSystem.IMPERIAL
}
