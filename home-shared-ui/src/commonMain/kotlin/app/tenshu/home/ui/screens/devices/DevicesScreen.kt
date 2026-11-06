package app.tenshu.home.ui.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tenshu.home.model.Device
import app.tenshu.home.model.DeviceKind
import app.tenshu.home.model.DeviceState
import app.tenshu.home.model.Room
import app.tenshu.home.model.formatAsOfTime
import app.tenshu.home.model.formatByteSize
import app.tenshu.home.model.formatDeciC
import app.tenshu.home.shared.resources.Res
import app.tenshu.home.shared.resources.home_add_cancel
import app.tenshu.home.shared.resources.home_add_confirm
import app.tenshu.home.shared.resources.home_add_kind_label
import app.tenshu.home.shared.resources.home_add_name_label
import app.tenshu.home.shared.resources.home_add_room_label
import app.tenshu.home.shared.resources.home_add_title
import app.tenshu.home.shared.resources.home_ask_mia_subtitle
import app.tenshu.home.shared.resources.home_ask_mia_title
import app.tenshu.home.shared.resources.home_device_brightness
import app.tenshu.home.shared.resources.home_device_command_dismiss
import app.tenshu.home.shared.resources.home_device_command_failed
import app.tenshu.home.shared.resources.home_device_command_unconfirmed
import app.tenshu.home.shared.resources.home_device_menu
import app.tenshu.home.shared.resources.home_device_power
import app.tenshu.home.shared.resources.home_device_move_down
import app.tenshu.home.shared.resources.home_device_move_up
import app.tenshu.home.shared.resources.home_device_remove
import app.tenshu.home.shared.resources.home_device_state_connected
import app.tenshu.home.shared.resources.home_device_state_disconnected
import app.tenshu.home.shared.resources.home_device_state_off
import app.tenshu.home.shared.resources.home_device_state_on
import app.tenshu.home.shared.resources.home_device_state_storage
import app.tenshu.home.shared.resources.home_device_state_thermostat
import app.tenshu.home.shared.resources.home_storage_as_of
import app.tenshu.home.shared.resources.home_storage_offline_as_of
import app.tenshu.home.shared.resources.home_devices_add
import app.tenshu.home.shared.resources.home_devices_empty_body
import app.tenshu.home.shared.resources.home_devices_empty_title
import app.tenshu.home.shared.resources.home_devices_title
import app.tenshu.home.shared.resources.home_kind_camera
import app.tenshu.home.shared.resources.home_pair_body
import app.tenshu.home.shared.resources.home_pair_close
import app.tenshu.home.shared.resources.home_pair_countdown
import app.tenshu.home.shared.resources.home_pair_done
import app.tenshu.home.shared.resources.home_pair_failed
import app.tenshu.home.shared.resources.home_pair_joined
import app.tenshu.home.shared.resources.home_pair_start
import app.tenshu.home.shared.resources.home_pair_title
import app.tenshu.home.shared.resources.home_pair_via
import app.tenshu.home.shared.resources.home_pair_waiting
import app.tenshu.home.shared.resources.home_kind_hub
import app.tenshu.home.shared.resources.home_kind_light
import app.tenshu.home.shared.resources.home_kind_sensor
import app.tenshu.home.shared.resources.home_kind_speaker
import app.tenshu.home.shared.resources.home_kind_storage
import app.tenshu.home.shared.resources.home_kind_thermostat
import app.tenshu.home.shared.resources.home_room_unassigned
import app.tenshu.ui.chrome.AppExtendedFab
import app.tenshu.ui.chrome.appFabInset
import org.jetbrains.compose.resources.stringResource

/**
 * The Home tab (docs/proposals/home-and-mia-apps.md §3): the "Ask Mia" entry (pushes the
 * embedded chat surface, D1), then the device list — per-kind state rows (connectivity,
 * light, storage usage, thermostat, sensor), add via FAB, remove/reorder via the row menu.
 * Rooms render as row subtitles; the grouped dashboard is a later slice (D5 keeps them in
 * the model from day one).
 */
@Composable
fun DevicesScreen(viewModel: DevicesViewModel, onAskMia: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "ask-mia") {
                AskMiaCard(onClick = onAskMia)
            }
            item(key = "devices-header") {
                Text(
                    stringResource(Res.string.home_devices_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            if (state.loaded && state.devices.isEmpty()) {
                item(key = "empty") { EmptyDevices() }
            }
            items(state.devices, key = { it.id }) { device ->
                DeviceRow(
                    device = device,
                    roomName = viewModel.roomName(device),
                    // Absent for every mock row and every read-only device —
                    // the row then renders exactly as it did before.
                    controls = state.controls[device.id],
                    onSetPower = { on -> viewModel.setDevicePower(device.id, on) },
                    onSetLevel = { percent -> viewModel.setDeviceLevel(device.id, percent) },
                    onDismissNote = { viewModel.dismissDeviceCommandNote(device.id) },
                )
            }
        }

        // ⚠⚠ **Offered only where a real bridge can honour it** (2026-08-26). The FAB used to be
        // unconditional: with a bridge it opened HD-2's pairing window, and without one it opened
        // the mock household's add-a-device dialog — a form that wrote to an in-memory object and
        // survived nothing. Home never offers a flow it cannot honour, so with no bridge there is
        // now no button.
        if (state.pairing.available) AppExtendedFab(
            onClick = { showAddDialog = true },
            icon = Icons.Default.Add,
            label = stringResource(Res.string.home_devices_add),
            modifier = Modifier.align(Alignment.BottomEnd).appFabInset(),
        )
    }

    if (showAddDialog) {
        // ⚠ One dialog now: "Add device" means opening a real bridge's pairing window (HD-2). The
        // FAB above is gated on the same fact, so this cannot be reached without one.
        PairDeviceDialog(
            pairing = state.pairing,
            onStart = { viewModel.startPairing() },
            onStop = { viewModel.stopPairing() },
            onDismiss = {
                viewModel.stopPairing()
                viewModel.dismissPairingFailure()
                showAddDialog = false
            },
        )
    }
}

/**
 * HD-2's Add-device flow: ask the bridge to admit devices for a bounded
 * window, count it down honestly, and list what actually joined. Deliberately
 * modest — this is the plumbing's face, not a redesign of the tab.
 */
@Composable
private fun PairDeviceDialog(
    pairing: PairingUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.home_pair_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(Res.string.home_pair_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (pairing.bridgeName.isNotBlank()) {
                    Text(
                        stringResource(Res.string.home_pair_via, pairing.bridgeName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pairing.open) {
                    Text(
                        stringResource(
                            Res.string.home_pair_countdown,
                            pairing.secondsRemaining,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (pairing.failed) {
                    Text(
                        stringResource(Res.string.home_pair_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                when {
                    pairing.joinedDeviceNames.isNotEmpty() -> {
                        Text(
                            stringResource(Res.string.home_pair_joined),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        pairing.joinedDeviceNames.forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Lightbulb,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    pairing.open -> Text(
                        stringResource(Res.string.home_pair_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (pairing.open) {
                TextButton(onClick = onStop) {
                    Text(stringResource(Res.string.home_pair_close))
                }
            } else {
                TextButton(onClick = onStart) {
                    Text(stringResource(Res.string.home_pair_start))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.home_pair_done))
            }
        },
    )
}

/** The embedded assistant entry (proposal D1): present from M1, live with M3. */
@Composable
private fun AskMiaCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    stringResource(Res.string.home_ask_mia_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(Res.string.home_ask_mia_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun EmptyDevices() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.home_devices_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.home_devices_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceRow(
    device: Device,
    roomName: String?,
    controls: DeviceControls?,
    onSetPower: (Boolean) -> Unit,
    onSetLevel: (Int) -> Unit,
    onDismissNote: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                kindIcon(device.kind),
                contentDescription = kindLabel(device.kind),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    listOfNotNull(
                        roomName ?: stringResource(Res.string.home_room_unassigned),
                        stateLine(device.state),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = stateColor(device.state),
                )
                (device.state as? DeviceState.Storage)?.let { storage ->
                    LinearProgressIndicator(
                        progress = { storage.usedFraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    // Real hub figures carry an honesty line: when they came in
                    // ("as of"), or that the hub has stopped reporting. A mock
                    // tile (live == null) shows neither — mock numbers are never
                    // dressed up as live ones.
                    storage.live?.let { live ->
                        Text(
                            if (live.stale) {
                                stringResource(
                                    Res.string.home_storage_offline_as_of,
                                    formatAsOfTime(live.freshAsOfEpochMs),
                                )
                            } else {
                                stringResource(
                                    Res.string.home_storage_as_of,
                                    formatAsOfTime(live.freshAsOfEpochMs),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (live.stale) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                controls?.let { c ->
                    if (c.canSetLevel && c.on == true) {
                        BrightnessSlider(
                            levelPercent = c.levelPercent ?: 0,
                            enabled = !c.busy,
                            onSetLevel = onSetLevel,
                        )
                    }
                    if (c.hasNote) {
                        CommandNote(status = c.status, onDismiss = onDismissNote)
                    }
                }
            }
            controls?.let { c ->
                if (c.busy) {
                    // A command is out; the hub polls, acts, then publishes —
                    // seconds, not milliseconds. Say so rather than let a switch
                    // look inert.
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (c.canSetPower) {
                    val powerLabel = stringResource(Res.string.home_device_power)
                    Switch(
                        checked = c.on == true,
                        onCheckedChange = onSetPower,
                        enabled = !c.busy,
                        modifier = Modifier.semantics { contentDescription = powerLabel },
                    )
                }
            }
        }
    }
}

/**
 * The dim control. Issues on **release**, not per drag frame: every change is a
 * synced command row, and a slider that wrote one per pixel would flood the
 * realm (the hub's publish gate brakes state, not commands).
 */
@Composable
private fun BrightnessSlider(
    levelPercent: Int,
    enabled: Boolean,
    onSetLevel: (Int) -> Unit,
) {
    // Local drag position, re-seeded whenever the authoritative level changes —
    // so a level published by the hub (or another device) shows up here, while a
    // drag in progress is never yanked out from under the thumb.
    var position by remember(levelPercent) { mutableStateOf(levelPercent.toFloat()) }
    val brightnessLabel = stringResource(Res.string.home_device_brightness)
    Slider(
        value = position,
        onValueChange = { position = it },
        onValueChangeFinished = { onSetLevel(position.toInt()) },
        valueRange = 0f..100f,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .semantics { contentDescription = brightnessLabel },
    )
}

/** "Didn't work" vs "no answer yet" — kept distinct, and dismissible. */
@Composable
private fun CommandNote(status: DeviceCommandStatus, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (status) {
                DeviceCommandStatus.Failed ->
                    stringResource(Res.string.home_device_command_failed)
                else -> stringResource(Res.string.home_device_command_unconfirmed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (status == DeviceCommandStatus.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(Res.string.home_device_command_dismiss))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddDeviceDialog(
    rooms: List<Room>,
    onConfirm: (name: String, kind: DeviceKind, roomId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(DeviceKind.Light) }
    var roomId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.home_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.home_add_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(Res.string.home_add_kind_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeviceKind.entries.forEach { entry ->
                        FilterChip(
                            selected = kind == entry,
                            onClick = { kind = entry },
                            label = { Text(kindLabel(entry)) },
                        )
                    }
                }
                Text(
                    stringResource(Res.string.home_add_room_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = roomId == null,
                        onClick = { roomId = null },
                        label = { Text(stringResource(Res.string.home_room_unassigned)) },
                    )
                    rooms.forEach { room ->
                        FilterChip(
                            selected = roomId == room.id,
                            onClick = { roomId = room.id },
                            label = { Text(room.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, kind, roomId) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(Res.string.home_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.home_add_cancel))
            }
        },
    )
}

private fun kindIcon(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.Hub -> Icons.Default.Hub
    DeviceKind.Storage -> Icons.Default.Storage
    DeviceKind.Speaker -> Icons.Default.Speaker
    DeviceKind.Light -> Icons.Default.Lightbulb
    DeviceKind.Camera -> Icons.Default.Videocam
    DeviceKind.Thermostat -> Icons.Default.Thermostat
    DeviceKind.Sensor -> Icons.Default.Sensors
}

@Composable
private fun kindLabel(kind: DeviceKind): String = when (kind) {
    DeviceKind.Hub -> stringResource(Res.string.home_kind_hub)
    DeviceKind.Storage -> stringResource(Res.string.home_kind_storage)
    DeviceKind.Speaker -> stringResource(Res.string.home_kind_speaker)
    DeviceKind.Light -> stringResource(Res.string.home_kind_light)
    DeviceKind.Camera -> stringResource(Res.string.home_kind_camera)
    DeviceKind.Thermostat -> stringResource(Res.string.home_kind_thermostat)
    DeviceKind.Sensor -> stringResource(Res.string.home_kind_sensor)
}

/** The per-kind one-line state, ready for the row subtitle. */
@Composable
private fun stateLine(state: DeviceState): String = when (state) {
    is DeviceState.Connectivity ->
        if (state.online) stringResource(Res.string.home_device_state_connected)
        else stringResource(Res.string.home_device_state_disconnected)
    is DeviceState.Light ->
        if (state.on) stringResource(Res.string.home_device_state_on, state.brightnessPercent)
        else stringResource(Res.string.home_device_state_off)
    is DeviceState.Storage -> stringResource(
        Res.string.home_device_state_storage,
        formatByteSize(state.freeBytes),
        formatByteSize(state.totalBytes),
    )
    is DeviceState.Thermostat -> stringResource(
        Res.string.home_device_state_thermostat,
        formatDeciC(state.currentDeciC),
        formatDeciC(state.targetDeciC),
    )
    is DeviceState.SensorReading -> state.summary
}

/** Disconnected devices call attention to themselves; everything else stays quiet. */
@Composable
private fun stateColor(state: DeviceState) =
    if (state is DeviceState.Connectivity && !state.online) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant
