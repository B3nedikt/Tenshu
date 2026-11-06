package app.tenshu.control.ui.screens.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Control's "Update Control to keep syncing" banner shows iff the fence row says this
 * device is fenced — never for "not yet known", and it goes away by itself once the fence lifts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchemaFenceBannerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun visible_whenFenced() = vmTest { vms ->
        val vm = vms.own(SchemaFenceBannerViewModel(MutableStateFlow(listOf("tenshu/file/service"))))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.visible)
    }

    @Test
    fun hidden_whenUnknown() = vmTest { vms ->
        // No row until the proxy's first tick after unlock (skew-surface §7 Q3): unknown is
        // never "fenced", or every cold start would flash an update demand.
        val vm = vms.own(SchemaFenceBannerViewModel(MutableStateFlow(null)))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.visible)
    }

    @Test
    fun hidden_whenClear() = vmTest { vms ->
        val vm = vms.own(SchemaFenceBannerViewModel(MutableStateFlow(emptyList())))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.visible)
    }

    @Test
    fun followsTheFence_bothWays() = vmTest { vms ->
        val fence = MutableStateFlow<List<String>?>(emptyList())
        val vm = vms.own(SchemaFenceBannerViewModel(fence))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.visible)

        fence.value = listOf("tenshu/sync/service")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.visible, "a fence engaging mid-session shows the banner")

        fence.value = emptyList()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.visible, "updating Control lifts the fence and the banner")
    }
}
