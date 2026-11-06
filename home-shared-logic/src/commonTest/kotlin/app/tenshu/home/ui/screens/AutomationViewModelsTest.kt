package app.tenshu.home.ui.screens

import app.tenshu.home.data.MockAutomationsRepository
import app.tenshu.home.data.MockHomeActivityRepository
import app.tenshu.home.data.MockHomeDevicesRepository
import app.tenshu.home.model.Action
import app.tenshu.home.model.ActivityKind
import app.tenshu.home.model.SunEvent
import app.tenshu.home.model.Trigger
import app.tenshu.home.ui.screens.activity.ActivityViewModel
import app.tenshu.home.ui.screens.automations.AutomationEditorViewModel
import app.tenshu.home.ui.screens.automations.AutomationsViewModel
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationViewModelsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun activity_filterNarrowsAndTogglesBackToAll() = vmTest { vms ->
        val vm = vms.own(ActivityViewModel(MockHomeActivityRepository()))
        val all = vm.uiState.value.filteredEvents
        assertTrue(all.isNotEmpty())

        vm.toggleFilter(ActivityKind.Voice)
        val voiceOnly = vm.uiState.value.filteredEvents
        assertTrue(voiceOnly.isNotEmpty())
        assertTrue(voiceOnly.all { it.kind == ActivityKind.Voice })

        vm.toggleFilter(ActivityKind.Voice)
        assertEquals(all, vm.uiState.value.filteredEvents)
    }

    @Test
    fun activity_eventsArriveNewestFirst() = vmTest { vms ->
        val vm = vms.own(ActivityViewModel(MockHomeActivityRepository()))
        val events = vm.uiState.value.events
        assertEquals(events.sortedByDescending { it.at }, events)
    }

    @Test
    fun automations_toggleAndRemoveRoundTrip() = vmTest { vms ->
        val vm = vms.own(AutomationsViewModel(MockAutomationsRepository(), MockHomeDevicesRepository()))
        val target = vm.uiState.value.automations.first { it.enabled }

        vm.setEnabled(target.id, false)
        assertFalse(vm.uiState.value.automations.first { it.id == target.id }.enabled)

        vm.removeAutomation(target.id)
        assertTrue(vm.uiState.value.automations.none { it.id == target.id })

        // Device names resolve for row summaries.
        assertEquals("Sofa lamp", vm.deviceName("device-lamp-sofa"))
    }

    @Test
    fun editor_createRequiresNameTriggerAndAction() = vmTest { vms ->
        val repository = MockAutomationsRepository()
        val vm = vms.own(AutomationEditorViewModel(repository, MockHomeDevicesRepository(), automationId = null))
        assertFalse(vm.uiState.value.canSave)

        vm.setName("Evening wind-down")
        vm.setTrigger(Trigger.Sun(SunEvent.Sunset, offsetMinutes = -30))
        assertFalse(vm.uiState.value.canSave)

        vm.addAction(Action.DeviceCommand(deviceId = "device-lamp-sofa", turnOn = true))
        assertTrue(vm.uiState.value.canSave)

        val before = repository.automations().first().size
        vm.save()
        assertTrue(vm.uiState.value.saved)
        val after = repository.automations().first()
        assertEquals(before + 1, after.size)
        assertEquals("Evening wind-down", after.last().name)
        assertTrue(after.last().enabled)
    }

    @Test
    fun editor_editPrefillsAndUpdatesInPlace() = vmTest { vms ->
        val repository = MockAutomationsRepository()
        val existing = repository.automations().first().first { it.id == "automation-night" }
        val vm = vms.own(AutomationEditorViewModel(repository, MockHomeDevicesRepository(), automationId = existing.id))

        assertEquals(existing.name, vm.uiState.value.name)
        assertEquals(existing.trigger, vm.uiState.value.trigger)
        // The RunAutomation picker excludes the automation being edited.
        assertTrue(vm.uiState.value.otherAutomations.none { it.id == existing.id })

        vm.setName("Lights out early")
        vm.save()

        val after = repository.automations().first()
        val updated = after.first { it.id == existing.id }
        assertEquals("Lights out early", updated.name)
        // Enabled + lastRunAt survive an edit.
        assertEquals(existing.enabled, updated.enabled)
        assertEquals(existing.lastRunAt, updated.lastRunAt)
        assertEquals(after.map { it.id }, repository.automations().first().map { it.id })
    }
}
