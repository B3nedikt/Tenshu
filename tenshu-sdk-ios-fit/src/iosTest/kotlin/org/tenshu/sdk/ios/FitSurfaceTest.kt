package org.tenshu.sdk.ios

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.ui.screens.category.CategoryMetric
import app.tenshu.fit.ui.screens.workout.WorkoutDetailUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * Reactive coverage of [FitSurface] over the default [app.tenshu.fit.data.MockFitRepository] — the
 * coverage the in-app init-time smokes can't reach. Proves the fit-ios VM factories build and drive
 * real mock data on the `iosSimulatorArm64Test` gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FitSurfaceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun home_loads_and_a_workout_resolves() = vmTest(dispatcher) { vms ->
        FitSurface.installMock()

        val home = vms.own(FitSurface.homeViewModel())
        advanceUntilIdle()
        val today = home.uiState.value.today
        assertTrue(today != null, "Home should surface today's aggregate")
        assertTrue(home.uiState.value.week.isNotEmpty(), "Home should surface the trend week")

        // The latest workout from Home resolves via the detail factory.
        val workoutId = home.uiState.value.latestWorkout?.workoutId
        assertTrue(workoutId != null, "Home should surface a latest workout")
        val detail = vms.own(FitSurface.workoutViewModel(workoutId))
        advanceUntilIdle()
        assertTrue(detail.uiState.value is WorkoutDetailUiState.Loaded, "workout $workoutId should load")
    }

    @Test
    fun browse_categories_load() = vmTest(dispatcher) { vms ->
        FitSurface.installMock()
        val browse = vms.own(FitSurface.browseViewModel())
        advanceUntilIdle()
        assertTrue(browse.uiState.value.categories.isNotEmpty(), "Browse should surface category cards")
    }

    /** Home's week cards open Activity's other series through the two-argument factory (§18.18). */
    @Test
    fun category_detail_opens_the_requested_activity_series() = vmTest(dispatcher) { vms ->
        FitSurface.installMock()
        for (metric in listOf(CategoryMetric.ActiveEnergy, CategoryMetric.Steps, CategoryMetric.ActiveMinutes, CategoryMetric.Distance)) {
            val vm = vms.own(FitSurface.categoryViewModel(CategoryId.Activity, metric))
            advanceUntilIdle()
            assertEquals(metric, vm.uiState.value.metric, "Activity should open on $metric")
        }
        // The one-argument form Browse calls still opens on steps.
        val browse = vms.own(FitSurface.categoryViewModel(CategoryId.Activity))
        advanceUntilIdle()
        assertEquals(CategoryMetric.Steps, browse.uiState.value.metric)
    }
}
