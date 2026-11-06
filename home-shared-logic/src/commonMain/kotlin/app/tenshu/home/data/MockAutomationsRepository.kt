package app.tenshu.home.data

import app.tenshu.home.model.Action
import app.tenshu.home.model.Automation
import app.tenshu.home.model.Condition
import app.tenshu.home.model.DeviceEventKind
import app.tenshu.home.model.GeofenceDirection
import app.tenshu.home.model.Trigger
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * In-memory mock (docs/proposals/home-and-mia-apps.md §5): three seeded automations
 * spanning the trigger taxonomy — geofence, schedule, device event — against the seeded
 * household's device ids. Names double as test anchors (AutomationsScreenTest).
 */
class MockAutomationsRepository(
    seed: List<Automation> = defaultSeed(Clock.System.now()),
) : AutomationsRepository {

    private val automationsFlow = MutableStateFlow(seed)
    private var nextId = 1

    override fun automations(): Flow<List<Automation>> = automationsFlow

    override suspend fun addAutomation(automation: Automation): String {
        val id = "automation-added-${nextId++}"
        automationsFlow.update { it + automation.copy(id = id) }
        return id
    }

    override suspend fun updateAutomation(automation: Automation) {
        automationsFlow.update { list ->
            list.map { if (it.id == automation.id) automation else it }
        }
    }

    override suspend fun removeAutomation(automationId: String) {
        automationsFlow.update { list -> list.filterNot { it.id == automationId } }
    }

    override suspend fun setEnabled(automationId: String, enabled: Boolean) {
        automationsFlow.update { list ->
            list.map { if (it.id == automationId) it.copy(enabled = enabled) else it }
        }
    }

    companion object {
        fun defaultSeed(now: Instant): List<Automation> = listOf(
            Automation(
                id = "automation-arrive",
                name = "Welcome home",
                enabled = true,
                trigger = Trigger.Geofence(GeofenceDirection.Arrive),
                conditions = listOf(Condition.TimeWindow(afterMinutes = 16 * 60, beforeMinutes = 23 * 60)),
                actions = listOf(
                    Action.DeviceCommand(deviceId = "device-lamp-sofa", turnOn = true),
                ),
                lastRunAt = now - 3.hours,
            ),
            Automation(
                id = "automation-night",
                name = "Lights out",
                enabled = true,
                trigger = Trigger.Schedule(minutesOfDay = 22 * 60 + 30),
                conditions = emptyList(),
                actions = listOf(
                    Action.DeviceCommand(deviceId = "device-lamp-sofa", turnOn = false),
                    Action.DeviceCommand(deviceId = "device-lamp-desk", turnOn = false),
                ),
                lastRunAt = now - 14.hours,
            ),
            Automation(
                id = "automation-camera",
                name = "Someone at the door",
                enabled = false,
                trigger = Trigger.DeviceEvent(
                    deviceId = "device-camera",
                    event = DeviceEventKind.PersonDetected,
                ),
                conditions = listOf(Condition.Presence(nobodyHome = true)),
                actions = listOf(Action.Notify(message = "Person at the front door")),
                lastRunAt = null,
            ),
        )
    }
}
