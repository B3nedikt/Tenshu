package app.tenshu.timeline.ui.screens

import app.tenshu.timeline.data.MockTimelineRepository
import app.tenshu.timeline.data.RegionBundleResolver
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.ui.screens.home.HomeTab
import app.tenshu.timeline.ui.screens.home.TimelineHomeViewModel
import app.tenshu.timeline.ui.screens.moment.MomentDetailUiState
import app.tenshu.timeline.ui.screens.moment.MomentDetailViewModel
import app.tenshu.timeline.ui.screens.place.PlaceDetailUiState
import app.tenshu.timeline.ui.screens.place.PlaceDetailViewModel
import app.tenshu.timeline.ui.screens.trip.TripDetailUiState
import app.tenshu.timeline.ui.screens.trip.TripDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineViewModelsTest {

    private val repo = MockTimelineRepository()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun placeDetail_renamePersistsAndReloads() = vmTest { vms ->
        val placeId = repo.places().first().first().id
        val vm = vms.own(PlaceDetailViewModel(repo, placeId))

        vm.rename("Our café")

        val loaded = vm.uiState.value as PlaceDetailUiState.Loaded
        assertEquals("Our café", loaded.place.name)
        assertEquals("Our café", repo.place(placeId)?.name)

        // Blank input is ignored — the name stays.
        vm.rename("   ")
        assertEquals("Our café", (vm.uiState.value as PlaceDetailUiState.Loaded).place.name)
    }

    @Test
    fun home_seedsAllTabs() = vmTest { vms ->
        val vm = vms.own(TimelineHomeViewModel(repo))
        val s = vm.uiState.value
        assertEquals(HomeTab.Home, s.tab)
        assertEquals(3, s.days.size)
        assertEquals("Hamburg", s.suggestion?.title)
        assertEquals(listOf("Lisbon"), s.boundTrips.map { it.title })
        assertEquals(8, s.places.size)
        assertEquals(6, s.activities.size)
        assertEquals(0.7f, s.personalisationStrength)
    }

    @Test
    fun home_selectTab_andClampStrength() = vmTest { vms ->
        val vm = vms.own(TimelineHomeViewModel(repo))
        vm.selectTab(HomeTab.Places)
        assertEquals(HomeTab.Places, vm.uiState.value.tab)
        vm.setPersonalisationStrength(2f)
        assertEquals(1f, vm.uiState.value.personalisationStrength)
        vm.setPersonalisationStrength(-1f)
        assertEquals(0f, vm.uiState.value.personalisationStrength)
    }

    @Test
    fun home_dismissSuggestion_clearsInterlude() = vmTest { vms ->
        val vm = vms.own(TimelineHomeViewModel(repo))
        vm.dismissSuggestion()
        assertEquals(null, vm.uiState.value.suggestion)
    }

    @Test
    fun tripDetail_loadsNestedTrip_andNotFound() = vmTest { vms ->
        val lisbon = vms.own(TripDetailViewModel(repo, regions = null, "lisbon-2026-04")).uiState.value
        assertIs<TripDetailUiState.Loaded>(lisbon)
        assertEquals("Lisbon", lisbon.trip.title)

        val sintra = vms.own(TripDetailViewModel(repo, regions = null, "sintra-2026-04-14")).uiState.value
        assertIs<TripDetailUiState.Loaded>(sintra)
        assertEquals("Sintra", sintra.trip.title)

        assertIs<TripDetailUiState.NotFound>(vms.own(TripDetailViewModel(repo, regions = null, "nope")).uiState.value)
    }

    @Test
    fun tripDetail_threadsResolvedRegionBundlePath_forARoutedTrip() = vmTest { vms ->
        // A trip with a route (Lisbon has 5 points) upgrades from the demo basemap to its
        // resolved region bundle; the resolver receives the real route.
        var seenRoute: List<GeoPoint>? = null
        val resolver = RegionBundleResolver { route -> seenRoute = route; "/staged/pt-lisboa.pmtiles" }

        val loaded = vms.own(TripDetailViewModel(repo, resolver, "lisbon-2026-04")).uiState.value
        assertIs<TripDetailUiState.Loaded>(loaded)
        assertEquals("/staged/pt-lisboa.pmtiles", loaded.regionBundlePath)
        assertEquals(5, seenRoute?.size)
    }

    @Test
    fun momentDetail_loadsNestedMoment_andNotFound() = vmTest { vms ->
        val pena = vms.own(MomentDetailViewModel(repo, "sintra-pena")).uiState.value
        assertIs<MomentDetailUiState.Loaded>(pena)
        assertEquals("Palácio da Pena", pena.moment.title)

        assertIs<MomentDetailUiState.NotFound>(vms.own(MomentDetailViewModel(repo, "nope")).uiState.value)
    }

    @Test
    fun momentDetail_threadsMovementRouteAndRegion() = vmTest { vms ->
        // A movement moment upgrades its hero in place: first the decoded route, then the
        // resolver's region for that route — mirroring the trip/place detail pattern (M5).
        val route = listOf(GeoPoint(52.52, 13.40), GeoPoint(52.53, 13.41), GeoPoint(52.54, 13.42))
        val movementRepo = object : TimelineRepository by repo {
            override suspend fun moment(id: String, fresh: Boolean) = Moment(
                id = id, time = "10:00", title = "Walk · 1.2 km", place = "",
                kind = MomentKind.Movement, movementMode = "walk", distanceM = 1_200,
                traceId = "trace-1", traceIds = listOf("trace-1"),
            )
            // ⚠ The LIST overload is the one the VM calls, and `by repo` generates its own forwarding
            // override — so overriding only the single-id sibling would silently never be consulted.
            override suspend fun movementRoute(traceIds: List<String>) =
                if (traceIds == listOf("trace-1")) route else emptyList()
        }
        var seenRoute: List<GeoPoint>? = null
        val resolver = RegionBundleResolver { r -> seenRoute = r; "/staged/de-berlin.pmtiles" }

        val loaded = vms.own(MomentDetailViewModel(movementRepo, "m1", resolver)).uiState.value
        assertIs<MomentDetailUiState.Loaded>(loaded)
        assertEquals(route, loaded.route)
        assertEquals("/staged/de-berlin.pmtiles", loaded.regionBundlePath)
        assertEquals(route, seenRoute)
    }

    @Test
    fun momentDetail_nonMovementNeverFetchesARoute() = vmTest { vms ->
        var routeFetched = false
        val guarded = object : TimelineRepository by repo {
            override suspend fun movementRoute(traceId: String): List<GeoPoint> {
                routeFetched = true
                return emptyList()
            }
            override suspend fun movementRoute(traceIds: List<String>): List<GeoPoint> {
                routeFetched = true
                return emptyList()
            }
        }
        val pena = vms.own(MomentDetailViewModel(guarded, "sintra-pena")).uiState.value
        assertIs<MomentDetailUiState.Loaded>(pena)
        assertEquals(emptyList(), pena.route)
        assertEquals(false, routeFetched)
    }

    @Test
    fun placeDetail_loadsHistory_andNotFound() = vmTest { vms ->
        val mogg = vms.own(PlaceDetailViewModel(repo, "mogg")).uiState.value
        assertIs<PlaceDetailUiState.Loaded>(mogg)
        assertEquals(6, mogg.place.history.size)

        assertIs<PlaceDetailUiState.NotFound>(vms.own(PlaceDetailViewModel(repo, "nope")).uiState.value)
    }
}
