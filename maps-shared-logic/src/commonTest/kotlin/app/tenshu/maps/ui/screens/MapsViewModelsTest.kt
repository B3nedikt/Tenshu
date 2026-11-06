package app.tenshu.maps.ui.screens

import app.tenshu.maps.data.RegionsRead
import app.tenshu.maps.data.MockPlaceRepository
import org.tenshu.viewmodel.test.vmTest
import app.tenshu.maps.data.RegionRepository
import app.tenshu.maps.model.RegionItem
import app.tenshu.maps.ui.screens.detail.PlaceDetailUiState
import app.tenshu.maps.ui.screens.detail.PlaceDetailViewModel
import app.tenshu.maps.ui.screens.places.MapsTab
import app.tenshu.maps.ui.screens.places.PlacesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapsViewModelsTest {

    private val repo = MockPlaceRepository()

    /** No installed regions → the detail's regionBundlePath stays null (bundled demo basemap). */
    private val regions = object : RegionRepository {
        override suspend fun regions(): RegionsRead = RegionsRead.Regions(emptyList())
        override suspend fun download(regionId: String): Boolean = false
        override suspend fun delete(regionId: String) {}
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun places_seedsTabsAndSections() = vmTest { vms ->
        val vm = vms.own(PlacesViewModel(repo))
        val s = vm.uiState.value
        assertEquals(MapsTab.Yours, s.tab)
        assertEquals(3, s.yours.size)
        assertEquals(5, s.saved.size)
        assertEquals(4, s.recommended.size)
        assertEquals(3, s.savedLists.size)
    }

    @Test
    fun places_selectTab() = vmTest { vms ->
        val vm = vms.own(PlacesViewModel(repo))
        vm.selectTab(MapsTab.ForYou)
        assertEquals(MapsTab.ForYou, vm.uiState.value.tab)
    }

    @Test
    fun places_setSaved_updatesDerivedLists() = vmTest { vms ->
        val vm = vms.own(PlacesViewModel(repo))
        assertFalse(vm.uiState.value.recommended.first { it.id == "padaria" }.saved)
        vm.setSaved("padaria", true)
        assertTrue(vm.uiState.value.recommended.first { it.id == "padaria" }.saved)
    }

    @Test
    fun detail_loads_andNotFound() = vmTest { vms ->
        val aurora = vms.own(PlaceDetailViewModel(repo, regions, "aurora")).uiState.value
        assertIs<PlaceDetailUiState.Loaded>(aurora)
        assertEquals("Café Aurora", aurora.place.name)
        assertEquals(4, aurora.place.recentVisits.size)

        assertIs<PlaceDetailUiState.NotFound>(
            vms.own(PlaceDetailViewModel(repo, regions, "nope")).uiState.value,
        )
    }

    @Test
    fun detail_setSaved_reflectsLive() = vmTest { vms ->
        val vm = vms.own(PlaceDetailViewModel(repo, regions, "padaria"))
        assertFalse((vm.uiState.value as PlaceDetailUiState.Loaded).place.saved)
        vm.setSaved(true)
        assertTrue((vm.uiState.value as PlaceDetailUiState.Loaded).place.saved)
    }
}
