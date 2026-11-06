package app.tenshu.timeline.ui.screens.moment

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripSuggestion
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_route_map_open_a11y
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import app.tenshu.timeline.composeVmTest

/**
 * The inline route hero on a movement's detail is a **tap target**, not a map you pan: the detail
 * page scrolls vertically, so a pannable map there would fight the page scroll. Tapping it pushes
 * the full-screen, interactive map (`Destination.MomentRoute`).
 *
 * The behavioural claims worth pinning — and the ones a composable-tree assertion would miss:
 *
 * 1. a movement **with** a decodable route exposes exactly one tap target, and tapping it asks the
 *    shell to open the map;
 * 2. a movement whose trace decoded to fewer than two points draws no map at all, so there is
 *    **nothing tappable** — the same rule Fit applies to a workout with no GPS.
 */
@OptIn(ExperimentalTestApi::class)
class MomentRouteMapUiTest {

    private val movement = Moment(
        id = "m-move",
        time = "09:12",
        title = "Movement",
        place = "",
        kind = MomentKind.Movement,
        durationMin = 24,
        distanceM = 3200,
        movementMode = "walk",
        traceId = "trace-1",
    )

    /** A repository serving one movement moment whose trace decodes to [route]. */
    private class Repo(
        private val moment: Moment,
        private val route: List<GeoPoint>,
    ) : TimelineRepository {
        override fun homeDays(): Flow<List<Day>> = flowOf(emptyList())
        override fun tripSuggestion(): Flow<TripSuggestion?> = flowOf(null)
        override fun boundTrips(): Flow<List<Trip>> = flowOf(emptyList())
        override fun places(): Flow<List<Place>> = flowOf(emptyList())
        override fun activities(): Flow<List<ActivityRollup>> = flowOf(emptyList())
        override suspend fun acceptTripSuggestion() = Unit
        override suspend fun dismissTripSuggestion() = Unit
        override suspend fun trip(id: String): Trip? = null
        override suspend fun moment(id: String, fresh: Boolean): Moment? = moment
        override suspend fun place(id: String): Place? = null
        override suspend fun movementRoute(traceId: String): List<GeoPoint> = route
    }

    private val realRoute = listOf(
        GeoPoint(43.7690, 11.2470),
        GeoPoint(43.7685, 11.2490),
        GeoPoint(43.7679, 11.2531),
    )

    @Test
    fun aMovementWithARouteOpensTheFullScreenMapOnTap() = composeVmTest { vms ->
        var opened = 0
        val vm = vms.own(MomentDetailViewModel(Repo(movement, realRoute), momentId = movement.id))
        var label = ""
        setContent {
            TenshuTheme {
                label = stringResource(Res.string.timeline_route_map_open_a11y)
                MomentDetailScreen(viewModel = vm, onBack = {}, onOpenRouteMap = { opened++ })
            }
        }

        waitUntil { onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, onAllNodesWithContentDescription(label).fetchSemanticsNodes().size)
        onAllNodesWithContentDescription(label).onFirst().performClick()
        waitUntil { opened == 1 }
        assertEquals(1, opened)
    }

    @Test
    fun aMovementWithoutADecodableRouteHasNothingToTap() = composeVmTest { vms ->
        val vm = vms.own(MomentDetailViewModel(Repo(movement, route = emptyList()), momentId = movement.id))
        var label = ""
        setContent {
            TenshuTheme {
                label = stringResource(Res.string.timeline_route_map_open_a11y)
                MomentDetailScreen(
                    viewModel = vm,
                    onBack = {},
                    onOpenRouteMap = { error("a route-less movement must not offer a map") },
                )
            }
        }

        waitForIdle()
        assertEquals(0, onAllNodesWithContentDescription(label).fetchSemanticsNodes().size)
    }
}
