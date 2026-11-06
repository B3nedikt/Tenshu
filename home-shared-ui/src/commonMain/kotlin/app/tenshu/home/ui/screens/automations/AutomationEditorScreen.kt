package app.tenshu.home.ui.screens.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tenshu.home.model.Action
import app.tenshu.home.model.Automation
import app.tenshu.home.model.Condition
import app.tenshu.home.model.Device
import app.tenshu.home.model.DeviceEventKind
import app.tenshu.home.model.GeofenceDirection
import app.tenshu.home.model.SunEvent
import app.tenshu.home.model.Trigger
import app.tenshu.home.model.formatMinutesOfDay
import app.tenshu.home.model.parseMinutesOfDay
import app.tenshu.home.shared.resources.Res
import app.tenshu.home.shared.resources.home_add_cancel
import app.tenshu.home.shared.resources.home_add_confirm
import app.tenshu.home.shared.resources.home_editor_action_device
import app.tenshu.home.shared.resources.home_editor_action_notify
import app.tenshu.home.shared.resources.home_editor_action_run
import app.tenshu.home.shared.resources.home_editor_add_action
import app.tenshu.home.shared.resources.home_editor_add_condition
import app.tenshu.home.shared.resources.home_editor_after_label
import app.tenshu.home.shared.resources.home_editor_back
import app.tenshu.home.shared.resources.home_editor_before_label
import app.tenshu.home.shared.resources.home_editor_condition_device
import app.tenshu.home.shared.resources.home_editor_condition_presence
import app.tenshu.home.shared.resources.home_editor_condition_window
import app.tenshu.home.shared.resources.home_editor_device_label
import app.tenshu.home.shared.resources.home_editor_direction_arrive
import app.tenshu.home.shared.resources.home_editor_direction_leave
import app.tenshu.home.shared.resources.home_editor_edit_title
import app.tenshu.home.shared.resources.home_editor_event_label
import app.tenshu.home.shared.resources.home_editor_message_label
import app.tenshu.home.shared.resources.home_editor_name_label
import app.tenshu.home.shared.resources.home_editor_new_title
import app.tenshu.home.shared.resources.home_editor_nobody
import app.tenshu.home.shared.resources.home_editor_off
import app.tenshu.home.shared.resources.home_editor_offset_label
import app.tenshu.home.shared.resources.home_editor_on
import app.tenshu.home.shared.resources.home_editor_only_if
import app.tenshu.home.shared.resources.home_editor_phrase_label
import app.tenshu.home.shared.resources.home_editor_remove_item
import app.tenshu.home.shared.resources.home_editor_save
import app.tenshu.home.shared.resources.home_editor_somebody
import app.tenshu.home.shared.resources.home_editor_then
import app.tenshu.home.shared.resources.home_editor_time_label
import app.tenshu.home.shared.resources.home_editor_trigger_device
import app.tenshu.home.shared.resources.home_editor_trigger_geofence
import app.tenshu.home.shared.resources.home_editor_trigger_schedule
import app.tenshu.home.shared.resources.home_editor_trigger_sun
import app.tenshu.home.shared.resources.home_editor_trigger_voice
import app.tenshu.home.shared.resources.home_editor_when
import app.tenshu.home.shared.resources.home_sun_sunrise
import app.tenshu.home.shared.resources.home_sun_sunset
import org.jetbrains.compose.resources.stringResource

/**
 * The automation editor (docs/proposals/home-and-mia-apps.md §5, M2): one full-screen
 * surface shaped as the taxonomy reads — *When* (one trigger, type chips + inline config),
 * *Only if* (conditions), *Then* (actions). Values are built here; the VM owns the draft
 * and the save. Pushed by the Automations tab for both create and edit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationEditorScreen(
    viewModel: AutomationEditorViewModel,
    isNew: Boolean,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    val deviceName: (String) -> String? = { id -> state.devices.firstOrNull { it.id == id }?.name }
    val automationName: (String) -> String? = { id -> state.otherAutomations.firstOrNull { it.id == id }?.name }

    var conditionDialog by remember { mutableStateOf<ConditionKind?>(null) }
    var actionDialog by remember { mutableStateOf<ActionKind?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.home_editor_back))
            }
            Text(
                stringResource(if (isNew) Res.string.home_editor_new_title else Res.string.home_editor_edit_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::save, enabled = state.canSave) {
                Text(stringResource(Res.string.home_editor_save))
            }
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(Res.string.home_editor_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle(stringResource(Res.string.home_editor_when))
            TriggerTypeChips(state.trigger, state.devices, viewModel::setTrigger)
            TriggerConfig(state.trigger, state.devices, viewModel::setTrigger)

            SectionTitle(stringResource(Res.string.home_editor_only_if))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.conditions.forEachIndexed { index, condition ->
                    RemovableChip(conditionSummary(condition, deviceName)) { viewModel.removeCondition(index) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddChip(stringResource(Res.string.home_editor_condition_window)) { conditionDialog = ConditionKind.Window }
                AddChip(stringResource(Res.string.home_editor_condition_presence)) { conditionDialog = ConditionKind.Presence }
                AddChip(stringResource(Res.string.home_editor_condition_device)) { conditionDialog = ConditionKind.DeviceIs }
            }

            SectionTitle(stringResource(Res.string.home_editor_then))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.actions.forEachIndexed { index, action ->
                    RemovableChip(actionSummary(action, deviceName, automationName)) { viewModel.removeAction(index) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddChip(stringResource(Res.string.home_editor_action_device)) { actionDialog = ActionKind.Device }
                AddChip(stringResource(Res.string.home_editor_action_notify)) { actionDialog = ActionKind.Notify }
                if (state.otherAutomations.isNotEmpty()) {
                    AddChip(stringResource(Res.string.home_editor_action_run)) { actionDialog = ActionKind.Run }
                }
            }
        }
    }

    conditionDialog?.let { kind ->
        ConditionDialog(
            kind = kind,
            devices = state.devices,
            onAdd = { viewModel.addCondition(it); conditionDialog = null },
            onDismiss = { conditionDialog = null },
        )
    }
    actionDialog?.let { kind ->
        ActionDialog(
            kind = kind,
            devices = state.devices,
            otherAutomations = state.otherAutomations,
            onAdd = { viewModel.addAction(it); actionDialog = null },
            onDismiss = { actionDialog = null },
        )
    }
}

private enum class ConditionKind { Window, Presence, DeviceIs }
private enum class ActionKind { Device, Notify, Run }

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(label) },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(Res.string.home_editor_remove_item),
            )
        },
    )
}

@Composable
private fun AddChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
    )
}

/** The five §5 trigger types; selecting one swaps in a sensible default of that type. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerTypeChips(
    trigger: Trigger?,
    devices: List<Device>,
    onSet: (Trigger) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = trigger is Trigger.Schedule,
            onClick = { if (trigger !is Trigger.Schedule) onSet(Trigger.Schedule(minutesOfDay = 20 * 60)) },
            label = { Text(stringResource(Res.string.home_editor_trigger_schedule)) },
        )
        FilterChip(
            selected = trigger is Trigger.Sun,
            onClick = { if (trigger !is Trigger.Sun) onSet(Trigger.Sun(SunEvent.Sunset, offsetMinutes = 0)) },
            label = { Text(stringResource(Res.string.home_editor_trigger_sun)) },
        )
        FilterChip(
            selected = trigger is Trigger.Geofence,
            onClick = { if (trigger !is Trigger.Geofence) onSet(Trigger.Geofence(GeofenceDirection.Arrive)) },
            label = { Text(stringResource(Res.string.home_editor_trigger_geofence)) },
        )
        if (devices.isNotEmpty()) {
            FilterChip(
                selected = trigger is Trigger.DeviceEvent,
                onClick = {
                    if (trigger !is Trigger.DeviceEvent) {
                        onSet(Trigger.DeviceEvent(devices.first().id, DeviceEventKind.MotionDetected))
                    }
                },
                label = { Text(stringResource(Res.string.home_editor_trigger_device)) },
            )
        }
        FilterChip(
            selected = trigger is Trigger.VoicePhrase,
            onClick = { if (trigger !is Trigger.VoicePhrase) onSet(Trigger.VoicePhrase(phrase = "")) },
            label = { Text(stringResource(Res.string.home_editor_trigger_voice)) },
        )
    }
}

/** Inline per-type configuration under the type chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerConfig(
    trigger: Trigger?,
    devices: List<Device>,
    onSet: (Trigger) -> Unit,
) {
    when (trigger) {
        null -> Unit
        is Trigger.Schedule -> TimeField(
            label = stringResource(Res.string.home_editor_time_label),
            initialMinutes = trigger.minutesOfDay,
            onValid = { onSet(trigger.copy(minutesOfDay = it)) },
        )
        is Trigger.Sun -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = trigger.event == SunEvent.Sunrise,
                    onClick = { onSet(trigger.copy(event = SunEvent.Sunrise)) },
                    label = { Text(stringResource(Res.string.home_sun_sunrise)) },
                )
                FilterChip(
                    selected = trigger.event == SunEvent.Sunset,
                    onClick = { onSet(trigger.copy(event = SunEvent.Sunset)) },
                    label = { Text(stringResource(Res.string.home_sun_sunset)) },
                )
            }
            var offsetText by remember { mutableStateOf(trigger.offsetMinutes.toString()) }
            OutlinedTextField(
                value = offsetText,
                onValueChange = { text ->
                    offsetText = text
                    text.trim().toIntOrNull()?.let { onSet(trigger.copy(offsetMinutes = it)) }
                },
                label = { Text(stringResource(Res.string.home_editor_offset_label)) },
                singleLine = true,
            )
        }
        is Trigger.Geofence -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = trigger.direction == GeofenceDirection.Arrive,
                onClick = { onSet(trigger.copy(direction = GeofenceDirection.Arrive)) },
                label = { Text(stringResource(Res.string.home_editor_direction_arrive)) },
            )
            FilterChip(
                selected = trigger.direction == GeofenceDirection.Leave,
                onClick = { onSet(trigger.copy(direction = GeofenceDirection.Leave)) },
                label = { Text(stringResource(Res.string.home_editor_direction_leave)) },
            )
        }
        is Trigger.DeviceEvent -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DevicePicker(
                devices = devices,
                selectedId = trigger.deviceId,
                onSelect = { onSet(trigger.copy(deviceId = it)) },
            )
            Text(stringResource(Res.string.home_editor_event_label), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceEventKind.entries.forEach { event ->
                    FilterChip(
                        selected = trigger.event == event,
                        onClick = { onSet(trigger.copy(event = event)) },
                        label = { Text(eventLabel(event)) },
                    )
                }
            }
        }
        is Trigger.VoicePhrase -> OutlinedTextField(
            value = trigger.phrase,
            onValueChange = { onSet(trigger.copy(phrase = it)) },
            label = { Text(stringResource(Res.string.home_editor_phrase_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** HH:MM entry that only commits while the text parses; invalid input keeps the last value. */
@Composable
private fun TimeField(label: String, initialMinutes: Int, onValid: (Int) -> Unit) {
    var text by remember { mutableStateOf(formatMinutesOfDay(initialMinutes)) }
    val valid = parseMinutesOfDay(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            parseMinutesOfDay(value)?.let(onValid)
        },
        label = { Text(label) },
        singleLine = true,
        isError = !valid,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePicker(devices: List<Device>, selectedId: String, onSelect: (String) -> Unit) {
    Text(stringResource(Res.string.home_editor_device_label), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        devices.forEach { device ->
            FilterChip(
                selected = device.id == selectedId,
                onClick = { onSelect(device.id) },
                label = { Text(device.name) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionDialog(
    kind: ConditionKind,
    devices: List<Device>,
    onAdd: (Condition) -> Unit,
    onDismiss: () -> Unit,
) {
    var afterMinutes by remember { mutableStateOf(18 * 60) }
    var beforeMinutes by remember { mutableStateOf(23 * 60) }
    var nobodyHome by remember { mutableStateOf(true) }
    var deviceId by remember { mutableStateOf(devices.firstOrNull()?.id ?: "") }
    var deviceOn by remember { mutableStateOf(true) }

    val title = stringResource(
        when (kind) {
            ConditionKind.Window -> Res.string.home_editor_condition_window
            ConditionKind.Presence -> Res.string.home_editor_condition_presence
            ConditionKind.DeviceIs -> Res.string.home_editor_condition_device
        },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (kind) {
                    ConditionKind.Window -> {
                        TimeField(stringResource(Res.string.home_editor_after_label), afterMinutes) { afterMinutes = it }
                        TimeField(stringResource(Res.string.home_editor_before_label), beforeMinutes) { beforeMinutes = it }
                    }
                    ConditionKind.Presence -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = nobodyHome,
                            onClick = { nobodyHome = true },
                            label = { Text(stringResource(Res.string.home_editor_nobody)) },
                        )
                        FilterChip(
                            selected = !nobodyHome,
                            onClick = { nobodyHome = false },
                            label = { Text(stringResource(Res.string.home_editor_somebody)) },
                        )
                    }
                    ConditionKind.DeviceIs -> {
                        DevicePicker(devices, deviceId) { deviceId = it }
                        OnOffChips(deviceOn) { deviceOn = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = kind != ConditionKind.DeviceIs || deviceId.isNotEmpty(),
                onClick = {
                    onAdd(
                        when (kind) {
                            ConditionKind.Window -> Condition.TimeWindow(afterMinutes, beforeMinutes)
                            ConditionKind.Presence -> Condition.Presence(nobodyHome)
                            ConditionKind.DeviceIs -> Condition.DeviceIs(deviceId, deviceOn)
                        },
                    )
                },
            ) { Text(stringResource(Res.string.home_add_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.home_add_cancel)) }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionDialog(
    kind: ActionKind,
    devices: List<Device>,
    otherAutomations: List<Automation>,
    onAdd: (Action) -> Unit,
    onDismiss: () -> Unit,
) {
    var deviceId by remember { mutableStateOf(devices.firstOrNull()?.id ?: "") }
    var turnOn by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var automationId by remember { mutableStateOf(otherAutomations.firstOrNull()?.id ?: "") }

    val title = stringResource(
        when (kind) {
            ActionKind.Device -> Res.string.home_editor_action_device
            ActionKind.Notify -> Res.string.home_editor_action_notify
            ActionKind.Run -> Res.string.home_editor_action_run
        },
    )
    val canAdd = when (kind) {
        ActionKind.Device -> deviceId.isNotEmpty()
        ActionKind.Notify -> message.isNotBlank()
        ActionKind.Run -> automationId.isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (kind) {
                    ActionKind.Device -> {
                        DevicePicker(devices, deviceId) { deviceId = it }
                        OnOffChips(turnOn) { turnOn = it }
                    }
                    ActionKind.Notify -> OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text(stringResource(Res.string.home_editor_message_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ActionKind.Run -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        otherAutomations.forEach { automation ->
                            FilterChip(
                                selected = automation.id == automationId,
                                onClick = { automationId = automation.id },
                                label = { Text(automation.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canAdd,
                onClick = {
                    onAdd(
                        when (kind) {
                            ActionKind.Device -> Action.DeviceCommand(deviceId, turnOn)
                            ActionKind.Notify -> Action.Notify(message.trim())
                            ActionKind.Run -> Action.RunAutomation(automationId)
                        },
                    )
                },
            ) { Text(stringResource(Res.string.home_add_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.home_add_cancel)) }
        },
    )
}

@Composable
private fun OnOffChips(on: Boolean, onSet: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = on,
            onClick = { onSet(true) },
            label = { Text(stringResource(Res.string.home_editor_on)) },
        )
        FilterChip(
            selected = !on,
            onClick = { onSet(false) },
            label = { Text(stringResource(Res.string.home_editor_off)) },
        )
    }
}
