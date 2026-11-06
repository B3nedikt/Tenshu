package app.tenshu.control.ui.screens.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.ui.screens.offlinemaps.SectionHeader
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.tenshu.inference.InferenceCapability

/**
 * Control → Settings → Models → **one feature** (`inference-service.md` §38): what it does, and
 * which of the user's devices do the work.
 *
 * ⚠⚠ IN-D3's two answers are still two rows in the store (§9.2). This screen shows them as one list
 * of devices: a device's switch is its own answer, and the realm answer is written with the first
 * device switched on and withdrawn by *Turn off everywhere*.
 */
@Composable
fun ModelFeatureScreen(
    viewModel: ModelsViewModel,
    capability: InferenceCapability,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ModelFeatureScreenContent(
        state = state,
        capability = capability,
        onSetRealmConsent = viewModel::setRealmConsent,
        onSetDeviceConsent = viewModel::setDeviceConsent,
        onTurnOffOtherDevice = viewModel::turnOffOtherDevice,
        onTurnOffEverywhere = viewModel::turnOffEverywhere,
        onMessageShown = viewModel::consumeMessage,
        onBack = onBack,
    )
}

/** What a confirmation on this screen is about to do. */
private sealed interface PendingOff {
    data object ThisDevice : PendingOff
    data class OtherDevice(val device: OtherDeviceConsent) : PendingOff
    data object Everywhere : PendingOff
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelFeatureScreenContent(
    state: ModelsUiState,
    capability: InferenceCapability,
    onSetRealmConsent: (InferenceCapability, Boolean) -> Unit = { _, _ -> },
    onSetDeviceConsent: (InferenceCapability, Boolean) -> Unit = { _, _ -> },
    onTurnOffOtherDevice: (InferenceCapability, String) -> Unit = { _, _ -> },
    onTurnOffEverywhere: (InferenceCapability) -> Unit = {},
    onMessageShown: () -> Unit = {},
    onBack: () -> Unit,
) {
    val consent = state.consents.firstOrNull { it.capability == capability } ?: CapabilityConsent(capability)
    // ⚠ Where the roster is not this platform's to read, no other device is listed and nothing
    // is offered for one: this device's switch is the whole screen.
    val others = if (state.capabilities.otherDevices) state.otherDevices[capability].orEmpty() else emptyList()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        // ⚠ Only this screen's two; the rest are the Models screen's to say.
        val text = when (state.message) {
            ModelsMessage.ConsentNotSaved ->
                "Couldn't save that on this device yet. Try again once it has finished setting up."
            ModelsMessage.OtherDeviceConsentNotSaved -> OTHER_DEVICE_NOT_SAVED
            else -> null
        }
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            onMessageShown()
        }
    }

    var pending by remember { mutableStateOf<PendingOff?>(null) }
    val turnOff: (PendingOff) -> Unit = { what ->
        when (what) {
            PendingOff.ThisDevice -> onSetDeviceConsent(capability, false)
            is PendingOff.OtherDevice -> onTurnOffOtherDevice(capability, what.device.deviceId)
            PendingOff.Everywhere -> onTurnOffEverywhere(capability)
        }
    }
    pending?.let { what ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(turnOffTitle(capability, what.deviceName())) },
            text = { Text(turnOffText(capability, everywhere = what is PendingOff.Everywhere)) },
            confirmButton = {
                TextButton(onClick = { pending = null; turnOff(what) }) { Text("Turn off") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Keep on") } },
        )
    }
    // ⚠⚠ Asked first wherever switching off deletes (§38.2), and always for every device at once.
    val askOrTurnOff: (PendingOff) -> Unit = { what ->
        if (what is PendingOff.Everywhere || turnOffDeletes(capability)) pending = what else turnOff(what)
    }

    Scaffold(
        topBar = { AppTopAppBar(title = consentHeadline(capability), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.appContentWidth().fillMaxHeight()) {
                item(key = FeatureScreenItem.Description) {
                    Text(
                        featureDescription(capability),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                item(key = FeatureScreenItem.DevicesHeader) { SectionHeader("Devices") }
                item(key = FeatureScreenItem.ThisDevice) {
                    // ⚠ `device`, not `realm && device`: a yes still stored under a realm no shows
                    // as on, so it can be seen and switched off (`thisDeviceSupportingText`).
                    val toggle = {
                        // ⚠ Nothing acts on the seed before the answers were read.
                        if (!state.consentsRead) Unit
                        else if (consent.device) askOrTurnOff(PendingOff.ThisDevice) else onSetRealmConsent(capability, true)
                    }
                    AppListItem(
                        headline = "This device",
                        supportingText = thisDeviceSupportingText(consent),
                        leadingContent = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
                        // ⚠ Not pressable before the answers were read: the seed is no answer.
                        trailingContent = { Switch(checked = consent.device, onCheckedChange = { toggle() }, enabled = state.consentsRead) },
                        onClick = toggle,
                    )
                }
                items(others, key = { it.deviceId }) { other ->
                    AppListItem(
                        headline = other.name.ifBlank { "Unnamed device" },
                        supportingText = otherDeviceSupportingText(consent.realm, other),
                        leadingContent = { Icon(Icons.Default.Devices, contentDescription = null) },
                        trailingContent = {
                            // ⚠⚠ Off only from here, so an off switch is disabled rather than a
                            // switch that springs back (§38.2).
                            // ⚠⚠ And not over a roster that is not known now: nothing is written
                            // after a read that failed.
                            Switch(
                                checked = other.allowed,
                                enabled = state.consentsRead && other.allowed && state.otherDevicesKnown,
                                onCheckedChange = { askOrTurnOff(PendingOff.OtherDevice(other)) },
                            )
                        },
                        onClick = if (other.allowed && state.otherDevicesKnown) {
                            { askOrTurnOff(PendingOff.OtherDevice(other)) }
                        } else {
                            null
                        },
                    )
                }
                if (state.capabilities.otherDevices && canTurnOffEverywhere(consent, others)) {
                    item(key = FeatureScreenItem.Divider) { HorizontalDivider() }
                    item(key = FeatureScreenItem.Everywhere) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            TextButton(
                                onClick = { askOrTurnOff(PendingOff.Everywhere) },
                                // ⚠⚠ Not before the device list has loaded: it would reach none of them.
                                enabled = state.consentsRead && state.otherDevicesKnown,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(turnOffEverywhereLabel(capability))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ⚠ An enum, for `ModelsScreenItem`'s reason: device rows are keyed by ids this screen does not control. */
private enum class FeatureScreenItem { Description, DevicesHeader, ThisDevice, Divider, Everywhere }

private fun PendingOff.deviceName(): String? = when (this) {
    PendingOff.ThisDevice -> "this device"
    is PendingOff.OtherDevice -> device.name.ifBlank { "that device" }
    PendingOff.Everywhere -> null
}

/**
 * Whether there is anything for *Turn off everywhere* to turn off. ⚠ Any stored yes counts, including
 * one under a realm no — clearing those is the only way to stop them resuming.
 */
internal fun canTurnOffEverywhere(consent: CapabilityConsent, others: List<OtherDeviceConsent>): Boolean =
    consent.realm || consent.device || others.any { it.allowed }

/** ⚠⚠ Faces only: an explicit no deletes that device's face data, and that cannot be taken back. */
internal fun turnOffDeletes(capability: InferenceCapability): Boolean =
    capability == InferenceCapability.FACE_CLUSTERING

internal fun turnOffEverywhereLabel(capability: InferenceCapability): String =
    if (turnOffDeletes(capability)) "Turn off everywhere and delete face data" else "Turn off everywhere"

/** [deviceName] is `null` for every device at once. */
internal fun turnOffTitle(capability: InferenceCapability, deviceName: String?): String =
    "Turn off \"${consentHeadline(capability)}\" " + (deviceName?.let { "on $it?" } ?: "everywhere?")

/**
 * What the confirmation says is lost.
 *
 * ⚠⚠ Search promises no deletion: withdrawing it stops the pass and the query, and the vectors
 * already made stay on the device (§32.8).
 */
internal fun turnOffText(capability: InferenceCapability, everywhere: Boolean): String = when {
    turnOffDeletes(capability) && everywhere ->
        "Face data is deleted on every device. The names you typed are kept."
    turnOffDeletes(capability) ->
        "Its face data is deleted. The names you typed are kept, and other devices are not changed."
    everywhere -> "Every device stops indexing, and searching by description stops working."
    else -> "It stops indexing. Other devices are not changed."
}
