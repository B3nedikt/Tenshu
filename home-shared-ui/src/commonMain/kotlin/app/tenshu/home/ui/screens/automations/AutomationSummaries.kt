package app.tenshu.home.ui.screens.automations

import androidx.compose.runtime.Composable
import app.tenshu.home.model.Action
import app.tenshu.home.model.Automation
import app.tenshu.home.model.Condition
import app.tenshu.home.model.DeviceEventKind
import app.tenshu.home.model.GeofenceDirection
import app.tenshu.home.model.SunEvent
import app.tenshu.home.model.Trigger
import app.tenshu.home.model.formatMinutesOfDay
import app.tenshu.home.shared.resources.Res
import app.tenshu.home.shared.resources.home_action_notify
import app.tenshu.home.shared.resources.home_action_run
import app.tenshu.home.shared.resources.home_action_turn_off
import app.tenshu.home.shared.resources.home_action_turn_on
import app.tenshu.home.shared.resources.home_condition_device_off
import app.tenshu.home.shared.resources.home_condition_device_on
import app.tenshu.home.shared.resources.home_condition_nobody
import app.tenshu.home.shared.resources.home_condition_somebody
import app.tenshu.home.shared.resources.home_condition_window
import app.tenshu.home.shared.resources.home_event_closed
import app.tenshu.home.shared.resources.home_event_motion
import app.tenshu.home.shared.resources.home_event_opened
import app.tenshu.home.shared.resources.home_event_person
import app.tenshu.home.shared.resources.home_sun_sunrise
import app.tenshu.home.shared.resources.home_sun_sunset
import app.tenshu.home.shared.resources.home_trigger_arrive
import app.tenshu.home.shared.resources.home_trigger_device
import app.tenshu.home.shared.resources.home_trigger_leave
import app.tenshu.home.shared.resources.home_trigger_schedule
import app.tenshu.home.shared.resources.home_trigger_sun_after
import app.tenshu.home.shared.resources.home_trigger_sun_at
import app.tenshu.home.shared.resources.home_trigger_sun_before
import app.tenshu.home.shared.resources.home_trigger_voice
import app.tenshu.home.shared.resources.home_unknown_device
import org.jetbrains.compose.resources.stringResource

/**
 * Human-readable one-liners for the §5 taxonomy — shared by the Automations list rows and
 * the editor's chips so an automation always reads the same everywhere. [deviceName]
 * resolves device ids against the household ("Sofa lamp"); unknown ids degrade to a
 * placeholder rather than leaking raw ids.
 */
@Composable
fun triggerSummary(trigger: Trigger, deviceName: (String) -> String?): String = when (trigger) {
    is Trigger.Schedule -> stringResource(Res.string.home_trigger_schedule, formatMinutesOfDay(trigger.minutesOfDay))
    is Trigger.Sun -> {
        val event = stringResource(
            if (trigger.event == SunEvent.Sunrise) Res.string.home_sun_sunrise else Res.string.home_sun_sunset,
        )
        when {
            trigger.offsetMinutes < 0 -> stringResource(Res.string.home_trigger_sun_before, -trigger.offsetMinutes, event)
            trigger.offsetMinutes > 0 -> stringResource(Res.string.home_trigger_sun_after, trigger.offsetMinutes, event)
            else -> stringResource(Res.string.home_trigger_sun_at, event)
        }
    }
    is Trigger.Geofence -> stringResource(
        if (trigger.direction == GeofenceDirection.Arrive) Res.string.home_trigger_arrive
        else Res.string.home_trigger_leave,
    )
    is Trigger.DeviceEvent -> stringResource(
        Res.string.home_trigger_device,
        deviceName(trigger.deviceId) ?: stringResource(Res.string.home_unknown_device),
        eventLabel(trigger.event),
    )
    is Trigger.VoicePhrase -> stringResource(Res.string.home_trigger_voice, trigger.phrase)
}

@Composable
fun eventLabel(event: DeviceEventKind): String = stringResource(
    when (event) {
        DeviceEventKind.MotionDetected -> Res.string.home_event_motion
        DeviceEventKind.PersonDetected -> Res.string.home_event_person
        DeviceEventKind.Opened -> Res.string.home_event_opened
        DeviceEventKind.Closed -> Res.string.home_event_closed
    },
)

@Composable
fun conditionSummary(condition: Condition, deviceName: (String) -> String?): String = when (condition) {
    is Condition.TimeWindow -> stringResource(
        Res.string.home_condition_window,
        formatMinutesOfDay(condition.afterMinutes),
        formatMinutesOfDay(condition.beforeMinutes),
    )
    is Condition.Presence -> stringResource(
        if (condition.nobodyHome) Res.string.home_condition_nobody else Res.string.home_condition_somebody,
    )
    is Condition.DeviceIs -> stringResource(
        if (condition.on) Res.string.home_condition_device_on else Res.string.home_condition_device_off,
        deviceName(condition.deviceId) ?: stringResource(Res.string.home_unknown_device),
    )
}

@Composable
fun actionSummary(
    action: Action,
    deviceName: (String) -> String?,
    automationName: (String) -> String?,
): String = when (action) {
    is Action.DeviceCommand -> stringResource(
        if (action.turnOn) Res.string.home_action_turn_on else Res.string.home_action_turn_off,
        deviceName(action.deviceId) ?: stringResource(Res.string.home_unknown_device),
    )
    is Action.Notify -> stringResource(Res.string.home_action_notify, action.message)
    is Action.RunAutomation -> stringResource(
        Res.string.home_action_run,
        automationName(action.automationId) ?: stringResource(Res.string.home_unknown_device),
    )
}

/** The list row's second line: "When you arrive home · Turn on Sofa lamp (+1 more)". */
@Composable
fun automationRowSummary(
    automation: Automation,
    deviceName: (String) -> String?,
    automationName: (String) -> String?,
): String {
    val trigger = triggerSummary(automation.trigger, deviceName)
    val firstAction = automation.actions.firstOrNull()
        ?.let { actionSummary(it, deviceName, automationName) }
    return listOfNotNull(trigger, firstAction).joinToString(" · ")
}
