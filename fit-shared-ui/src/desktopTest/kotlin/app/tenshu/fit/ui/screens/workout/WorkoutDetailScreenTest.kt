package app.tenshu.fit.ui.screens.workout

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_workout_route_open_a11y
import app.tenshu.ui.theme.TenshuTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import app.tenshu.fit.composeVmTest

/**
 * Compose-render coverage for Workout detail: the one-shot `workout(id)` load flows through
 * [WorkoutDetailViewModel] into the Loaded state, so the title + stats render (not the
 * Loading/NotFound branch). Mirrors Drive's FileDetailsScreenTest.
 */
@OptIn(ExperimentalTestApi::class)
class WorkoutDetailScreenTest {

    @Test
    fun loadedWorkoutRendersTitle() = composeVmTest { vms ->
        val vm = vms.own(WorkoutDetailViewModel(MockFitRepository(), regions = null, workoutId = "w-sun-run"))
        setContent {
            TenshuTheme { WorkoutDetailScreen(viewModel = vm, onBack = {}) }
        }

        waitUntil {
            onAllNodesWithText("Morning run", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Rendered in both the top bar and the content header — assert the first is visible.
        onAllNodesWithText("Morning run", useUnmergedTree = true).onFirst().assertIsDisplayed()
    }

    /**
     * The inline route map is a **tap target**, not a map you pan: the detail page scrolls
     * vertically, so a pannable map there would fight the page scroll. Tapping it pushes the
     * full-screen, interactive map (`Destination.WorkoutRoute`). Mirrors Timeline's
     * `MomentRouteMapUiTest` — the two apps must behave identically.
     */
    @Test
    fun aRealGpsRouteOpensTheFullScreenMapOnTap() = composeVmTest { vms ->
        var opened = 0
        val vm = vms.own(WorkoutDetailViewModel(MockFitRepository(), regions = null, workoutId = "w-sun-run"))
        var label = ""
        setContent {
            TenshuTheme {
                label = stringResource(Res.string.fit_workout_route_open_a11y)
                WorkoutDetailScreen(viewModel = vm, onBack = {}, onOpenRouteMap = { opened++ })
            }
        }

        waitUntil { onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodesWithContentDescription(label).fetchSemanticsNodes().size)
        onAllNodesWithContentDescription(label).onFirst().performClick()
        waitUntil { opened == 1 }
        assertEquals(1, opened)
    }

    /**
     * Only the real-GPS branch has a map. A workout with a route but **no** `geoRoute` (the common
     * case — Connect / Google Fit rarely expose per-fix tracks) falls back to the schematic
     * `RoutePath`, which has no map to open and must therefore not be tappable.
     */
    @Test
    fun aWorkoutWithoutGpsHasNothingToTap() = composeVmTest { vms ->
        val vm = vms.own(WorkoutDetailViewModel(MockFitRepository(), regions = null, workoutId = "w-sat-ride"))
        var label = ""
        setContent {
            TenshuTheme {
                label = stringResource(Res.string.fit_workout_route_open_a11y)
                WorkoutDetailScreen(
                    viewModel = vm,
                    onBack = {},
                    onOpenRouteMap = { error("a GPS-less workout must not offer a map") },
                )
            }
        }

        waitUntil {
            onAllNodesWithText("Coast loop", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, onAllNodesWithContentDescription(label).fetchSemanticsNodes().size)
    }
}
