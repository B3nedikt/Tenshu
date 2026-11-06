package app.tenshu.control.ui.screens.settings.ingest

import app.tenshu.control.data.BackgroundIngestManager
import app.tenshu.control.data.IngestSourceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.core.ingest.IngestSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundIngestViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun model(enabled: Boolean = true) = IngestSourceModel(
        key = "drive.gallery@dev1",
        sourceId = "drive.gallery",
        appId = "app.tenshu.drive",
        deviceId = "dev1",
        displayName = "Photos & Videos",
        sourceKind = IngestSource.KIND_GALLERY,
        enabled = enabled,
        status = IngestSource.STATUS_UP_TO_DATE,
        pendingCount = 0,
        watchedPaths = "",
    )

    private class FakeManager(private val flow: MutableStateFlow<List<IngestSourceModel>>) : BackgroundIngestManager {
        val setCalls = mutableListOf<Triple<String, String, Boolean>>()
        override fun sources(): Flow<List<IngestSourceModel>> = flow
        override suspend fun setEnabled(sourceId: String, deviceId: String, enabled: Boolean) {
            setCalls += Triple(sourceId, deviceId, enabled)
        }
    }

    @Test
    fun loadsSourcesFromManager() = vmTest(dispatcher) { vms ->
        val vm = vms.own(BackgroundIngestViewModel(FakeManager(MutableStateFlow(listOf(model())))))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.loading)
        assertEquals(listOf("drive.gallery"), vm.uiState.value.sources.map { it.sourceId })
    }

    @Test
    fun onEnabledChange_optimisticallyFlips_andCallsSetEnabled() = vmTest(dispatcher) { vms ->
        val manager = FakeManager(MutableStateFlow(listOf(model(enabled = true))))
        val vm = vms.own(BackgroundIngestViewModel(manager))
        advanceUntilIdle()

        vm.onEnabledChange(vm.uiState.value.sources.single(), enabled = false)

        // Optimistic flip is immediate.
        assertFalse(vm.uiState.value.sources.single().enabled)

        advanceUntilIdle()
        assertEquals(
            listOf(Triple("drive.gallery", "dev1", false)),
            manager.setCalls,
        )
    }
}
