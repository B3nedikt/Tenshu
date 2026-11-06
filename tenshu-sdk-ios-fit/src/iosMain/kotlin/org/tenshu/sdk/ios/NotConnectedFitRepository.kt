package org.tenshu.sdk.ios

import app.tenshu.fit.data.FitPreferences
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.WorkoutRead
import app.tenshu.fit.data.WorkoutSaveOutcome
import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.CategorySummary
import app.tenshu.fit.model.DailyAggregate
import app.tenshu.fit.model.FitProfile
import app.tenshu.fit.model.Goals
import app.tenshu.fit.model.ImportCategory
import app.tenshu.fit.model.ImportProgress
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.MedicalRecord
import app.tenshu.fit.model.RecordedTrack
import app.tenshu.fit.model.SampleLatest
import app.tenshu.fit.model.SleepSession
import app.tenshu.fit.model.Units
import app.tenshu.fit.model.Workout
import app.tenshu.liststate.ListLoadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.LocalDate

/**
 * The iOS **"not connected to Control"** repository — the honest face of a Fit that hasn't got a
 * live EPC client (`docs/proposals/fit-app.md` §11.7).
 *
 * Fit has no store of its own; every read goes over IPC to Control. So when the live client isn't
 * installed (cold start before route 1 is provisioned, a failed handshake, or after teardown) there is genuinely **nothing to show**. Defaulting to
 * [app.tenshu.fit.data.MockFitRepository] there filled the screens with *fabricated* workouts and
 * steps the user would believe — the exact `ImportStatus.Sample` anti-pattern ("convincing invented
 * numbers are worse than empty"). This repository is empty instead, and reports
 * [controlReachable] `false` so the shell shows the "Can't reach Tenshu Control" banner.
 *
 * The mock stays — for tests and previews — but is no longer a *runtime* surface. [FitSurface]
 * installs this as its default and on [FitLiveClientSession] teardown;
 * [FitSurface.installRealClient] swaps in the live projector.
 */
class NotConnectedFitRepository : FitRepository {

    /** Not connected: no writes can land anywhere, and iOS wires no realm probe (ADR 0025 §8.6). */
    override fun refuseIfRealmWritesHeld() = Unit

    // Never the system clock (the FitRepository contract); with every read empty it only backs
    // date headers, which render nothing here anyway.
    override val today: LocalDate = LocalDate(2026, 1, 1)

    // ⚠ `Failed`, not `Loaded(emptyList())`, and the distinction is this class's whole reason for
    // existing. There is no live client, so these reads did not *answer* — they could not be made.
    // `Loaded(emptyList())` would make Home render "No activity yet — record a workout or connect
    // a health source" and Browse render seven "Not set up" cards plus a connect-a-source prompt:
    // fabricated conclusions about the user's data, which is the same anti-pattern as the
    // fabricated *numbers* this class replaced. `Failed` composes with the `controlReachable`
    // false below — the shell says why, and the screens say they could not load.
    override fun aggregates(): Flow<ListLoadState<DailyAggregate>> = flowOf(ListLoadState.Failed)
    override fun workouts(): Flow<List<Workout>> = flowOf(emptyList())
    override fun categories(): Flow<ListLoadState<CategorySummary>> = flowOf(ListLoadState.Failed)
    override fun samplesLatest(): Flow<List<SampleLatest>> = flowOf(emptyList())
    override fun sleepSessions(): Flow<List<SleepSession>> = flowOf(emptyList())
    override fun medicalRecords(): Flow<List<MedicalRecord>> = flowOf(emptyList())
    override fun goals(): Flow<Goals> = flowOf(FitPreferences.defaultGoals)

    // Every declared field is null, which is now *expressible*: with no Control there is nothing
    // to read, and "unknown" is the honest answer. This used to reach for sentinels — `""`, `0`,
    // `0.0`, today's date as a birth date — each of which rendered as a confident wrong value
    // (fit-app.md §18.5).
    override fun profile(): Flow<FitProfile> = flowOf(
        FitProfile(name = null, heightCm = null, weightKg = null, birthDate = null, sex = null, units = Units.Metric),
    )

    // Route straight to the (empty) shell — onboarding is about health-import setup, not the
    // Control connection; the banner is what explains the emptiness.
    override fun onboarded(): Flow<Boolean> = flowOf(true)

    // The load-bearing signal: `false` drives the shell's "Can't reach Tenshu Control" banner.
    override fun controlReachable(): StateFlow<Boolean?> = MutableStateFlow(false)

    /**
     * ⚠⚠ [WorkoutRead.Failed], never `NotFound`. This returned `null`, which the detail screen
     * rendered as **"Workout not found"** — telling the user their workout was gone when the only
     * true statement is that this device cannot reach Control. That is the same
     * not-connected-is-a-failure-never-an-empty-success rule this class exists to enforce, and the
     * one read that still broke it; [addWorkout] below has always thrown for the same reason.
     */
    override suspend fun workout(id: String): WorkoutRead = WorkoutRead.Failed

    override suspend fun addWorkout(workout: Workout, track: RecordedTrack?): WorkoutSaveOutcome =
        throw IllegalStateException("Not connected to Tenshu Control")

    override fun availableSources(): List<ImportSource> = emptyList()
    override fun importCategories(): List<ImportCategory> = emptyList()
    override fun importProgress(): Flow<List<ImportProgress>?> = flowOf(null)
    override suspend fun startImport(
        selection: Set<CategoryId>,
        window: ImportWindow,
        automatic: Boolean,
    ) = Unit
    override suspend fun completeOnboarding() = Unit
    override suspend fun updateGoals(goals: Goals) = Unit
    override suspend fun updateUnits(units: Units) = Unit
}
