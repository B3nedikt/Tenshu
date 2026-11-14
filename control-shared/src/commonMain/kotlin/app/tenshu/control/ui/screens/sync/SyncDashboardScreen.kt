package app.tenshu.control.ui.screens.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlin.time.Clock as KClock
import kotlin.time.ExperimentalTime
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.sync_dashboard_create_realm
import app.tenshu.control.shared.resources.sync_dashboard_no_realm_body
import app.tenshu.control.shared.resources.sync_dashboard_pairing_request
import app.tenshu.control.shared.resources.sync_dashboard_pairing_waiting
import app.tenshu.control.shared.resources.sync_dashboard_pairing_waiting_no_countdown
import app.tenshu.control.shared.resources.sync_dashboard_message_dismiss
import app.tenshu.control.shared.resources.sync_dashboard_refresh
import app.tenshu.control.shared.resources.sync_dashboard_scan_device
import app.tenshu.control.shared.resources.sync_dashboard_title
import app.tenshu.control.shared.resources.sync_dashboard_view_recovery_phrase
import app.tenshu.ui.chrome.AppFab
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import app.tenshu.ui.preview.PreviewBox
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tenshu.control.shared.resources.device_revoke_dialog_acknowledge
import app.tenshu.control.shared.resources.device_revoke_dialog_body
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import app.tenshu.control.shared.resources.device_revoke_dialog_unlisted
import app.tenshu.control.shared.resources.device_revoke_dialog_keep_admittee
import app.tenshu.control.shared.resources.device_revoke_dialog_admittees_intro
import app.tenshu.control.shared.resources.device_revoke_dialog_cancel
import app.tenshu.control.shared.resources.device_revoke_dialog_confirm
import app.tenshu.control.shared.resources.device_revoke_dialog_title
import app.tenshu.control.shared.resources.reauth_revoke_device_subtitle
import app.tenshu.control.shared.resources.reauth_revoke_device_system_reason
import app.tenshu.control.shared.resources.reauth_title
import app.tenshu.control.data.ReauthPromptCopy
import app.tenshu.control.data.Reauthenticator
import app.tenshu.control.data.confirm
import app.tenshu.control.data.rememberBiometricPromptHost
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.tenshu.identity.Device

@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun SyncDashboardScreen(
    onAddDevice: () -> Unit,
    onShowRecoveryPhrase: () -> Unit
) {
    val viewModel: SyncDashboardViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()

    // Ph-reauth (`control-keep-device-unlocked.md` §6.1, RA-3): prove presence before an
    // irreversible removal. ⚠ Gated at the **confirm**, not at the dialog: the dialog is where the
    // user learns removal is final for that identity (there is no re-admission under option F), so
    // prompting before they have read it would ask them to authorise something unexplained. The
    // recovery-phrase and forget-device gates sit *before* their dialogs for the opposite reason —
    // those dialogs disclose.
    //
    // ⚠ Wired here rather than inside [SyncDashboardContent] so the stateless composable keeps no
    // Koin dependency and its previews and tests stay constructible.
    val reauthenticator: Reauthenticator = koinInject()
    val promptHost = rememberBiometricPromptHost()
    val reauthCopy = ReauthPromptCopy(
        title = stringResource(Res.string.reauth_title),
        subtitle = stringResource(Res.string.reauth_revoke_device_subtitle),
        systemReason = stringResource(Res.string.reauth_revoke_device_system_reason),
    )
    val reauthScope = rememberCoroutineScope()

    SyncDashboardContent(
        uiState = uiState,
        currentDeviceId = uiState.currentDeviceId,
        // Sampled per roster snapshot, not per frame: the buckets are coarse (minutes at
        // the finest), so a recomposition-driven clock would buy nothing and churn.
        nowMs = remember(uiState.deviceLastSeen) { KClock.System.now().toEpochMilliseconds() },
        onRefresh = viewModel::refresh,
        onRevokeDevice = { deviceId, alsoRemove ->
            reauthScope.launch {
                if (reauthenticator.confirm(promptHost, reauthCopy)) {
                    viewModel.revokeDeviceAndAdmittees(deviceId, alsoRemove)
                }
            }
        },
        onApproveDevice = viewModel::approveDevice,
        // ⚠ No re-auth gate, deliberately, unlike onRevokeDevice above: readmitting restores a
        // device the roster already vouches for, grants no authority the A1-holder could not grant
        // by pairing anyway, and is idempotent. Gating a repair behind the same friction as a
        // destruction would teach the user that the two are equally dangerous.
        onReadmitDevice = viewModel::readmitDevice,
        onMessageShown = {
            viewModel.clearError()
            viewModel.clearNotice()
        },
        onAddDevice = onAddDevice,
        onShowRecoveryPhrase = onShowRecoveryPhrase,
        onCreateRealm = viewModel::createRealm
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncDashboardContent(
    uiState: SyncDashboardUiState,
    currentDeviceId: String,
    /**
     * "Now" for the per-device last-seen line. Injected rather than read inside the composable so
     * previews and screenshot tests stay deterministic; `0L` (their default) hides the line.
     */
    nowMs: Long = 0L,
    onRefresh: () -> Unit,
    /** The device to remove, plus the admittees the user chose to stop trusting (§7j.4). */
    onRevokeDevice: (deviceId: String, alsoRemove: List<String>) -> Unit,
    onApproveDevice: (String) -> Unit,
    onReadmitDevice: (String) -> Unit = {},
    /** Called once a surfaced [SyncDashboardUiState.error] / `notice` has been shown. */
    onMessageShown: () -> Unit = {},
    onAddDevice: () -> Unit,
    onShowRecoveryPhrase: () -> Unit,
    onCreateRealm: () -> Unit
) {
    // ⚠ Removing a device was a single un-confirmed icon tap, on an action that takes a device's
    // access away immediately and cannot be undone from the removed device itself. The share-link
    // revoke has had a confirmation since it shipped; this one never did.
    var pendingRevoke by remember { mutableStateOf<Device?>(null) }

    // ⚠⚠ Until now this screen rendered `uiState.error` NOWHERE — the VM folded a refused
    // revocation into it and the Compose surface dropped it on the floor, so a refusal and a
    // success looked identical (only the iOS roster ever showed it). `Restore access` cannot ship
    // into that: its whole effect is on ANOTHER device, so with no message here the button is
    // indistinguishable from one that does nothing. One host carries both outcomes.
    val snackbarHostState = remember { SnackbarHostState() }
    val messageDismiss = stringResource(Res.string.sync_dashboard_message_dismiss)
    val message = uiState.error ?: uiState.notice
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message = message, actionLabel = messageDismiss)
            onMessageShown()
        }
    }

    // "Add a device" is now reached only via the FAB → the unified "Add a device" screen
    // (control-pairing-ux.md Phase 2). The host-side "show a code" surface lives on that
    // screen's Show tab; the Approve/Deny approval is the app-global modal mounted at the
    // App root (HostPairingApprovalDialog driven by HostPairingMonitor.activeRequest).
    Scaffold(
        topBar = {
            AppTopAppBar(
                title = stringResource(Res.string.sync_dashboard_title),
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(Res.string.sync_dashboard_refresh),
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            AppFab(
                onClick = onAddDevice,
                icon = Icons.Default.Add,
                contentDescription = stringResource(Res.string.sync_dashboard_scan_device),
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .appContentWidth()
                    .fillMaxSize()
            ) {
                if (uiState.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Button(
                    onClick = onShowRecoveryPhrase,
                    modifier = Modifier.padding(16.dp).fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.sync_dashboard_view_recovery_phrase))
                }

                if (!uiState.isSyncEnabled) {
                    // Pre-realm onboarding stub: until a proper onboarding flow exists
                    // (see device-management proposal §2.3), the dashboard surfaces an
                    // explicit "Create Realm" action. The user must either create a
                    // realm here OR pair into an existing realm via the FAB → wizard.
                    // Auto-creating on dashboard open caused two devices to silently
                    // diverge into separate realms; this prompt is the minimal fix.
                    Text(
                        text = stringResource(Res.string.sync_dashboard_no_realm_body),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onCreateRealm,
                        modifier = Modifier.padding(16.dp).fillMaxWidth()
                    ) {
                        Text(stringResource(Res.string.sync_dashboard_create_realm))
                    }
                }

                // "in Pairing" placeholder row (control-pairing-ux.md §3.5): a visible home +
                // re-entry point while a host session is live. Tapping returns to the pairing
                // surface; the Approve/Deny SAS comparison is the app-global modal.
                if (uiState.isPairing) {
                    PairingStatusRow(
                        requestPending = uiState.pairingRequestPending,
                        remainingSeconds = uiState.pairingRemainingSeconds,
                        onClick = onAddDevice,
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ⚠ Computed once per list rather than per row: it is a whole-roster question
                    // (which rows share this device's key), and asking it per row would be O(n²)
                    // over a list every device in the realm appears in.
                    val previousIdentities =
                        previousIdentitiesOfThisDevice(uiState.devices, currentDeviceId)
                    items(uiState.devices) { device ->
                        DeviceItem(
                            device = device,
                            currentDeviceId = currentDeviceId,
                            isPreviousIdentity = device.deviceId.get() in previousIdentities,
                            policy = uiState.deviceSyncPolicies[device.deviceId.get()],
                            admittedByName = uiState.deviceAdmittedBy[device.deviceId.get()],
                            lastSeenMs = uiState.deviceLastSeen[device.deviceId.get()],
                            hardwareName = uiState.deviceHardwareNames[device.deviceId.get()],
                            nowMs = nowMs,
                            onRevoke = { pendingRevoke = device },
                            onApprove = { onApproveDevice(device.deviceId.get()) },
                            onReadmit = { onReadmitDevice(device.deviceId.get()) }
                        )
                    }
                }
            }
        }
    }

    pendingRevoke?.let { device ->
        // §7j.4: the devices it admitted, so a sleeper does not survive its admitter's removal
        // unseen. Computed from the roster this screen already renders.
        val admittees = remember(device, uiState.devices, currentDeviceId) {
            removalAdmittees(uiState.devices, device.deviceId.get(), currentDeviceId)
        }
        RevokeDeviceConfirmDialog(
            deviceName = device.name.get(),
            admittees = admittees,
            onConfirm = { alsoRemove ->
                onRevokeDevice(device.deviceId.get(), alsoRemove)
                pendingRevoke = null
            },
            onDismiss = { pendingRevoke = null },
        )
    }
}

/**
 * The "in Pairing" placeholder row (control-pairing-ux.md §3.5). Shows the countdown while
 * waiting and flips to "a device wants to join — tap to review" when a request arrives.
 * PII-free copy; tapping routes back to the pairing surface ([onClick]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingStatusRow(
    requestPending: Boolean,
    remainingSeconds: Int?,
    onClick: () -> Unit,
) {
    val text = when {
        requestPending -> stringResource(Res.string.sync_dashboard_pairing_request)
        remainingSeconds != null ->
            stringResource(Res.string.sync_dashboard_pairing_waiting, formatRemaining(remainingSeconds))
        else -> stringResource(Res.string.sync_dashboard_pairing_waiting_no_countdown)
    }
    Card(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Seconds → m:ss (e.g. 95 → "1:35"). */
private fun formatRemaining(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}

/**
 * Confirmation for removing a device.
 *
 * ⚠⚠ The body must not promise the device can be *added back*, which is what it used to say. Under
 * option F (`admission-revocation-names-the-capability.md` §4.1, chosen 2026-08-19) removal is final
 * **for that identity**: the hardware can be set up again, but it joins as a new device and does not
 * recover its name, its per-device settings or its place in this list. Copy that implies recovery
 * strands exactly the user who trusted it.
 *
 * ⚠ The checkbox is the mitigation F trades for — F has no re-admission, so the mis-tap has to be
 * caught *before* it happens rather than undone after. It gates the confirm button for that reason
 * and for no other; do not soften it into a second "are you sure".
 */
@Composable
private fun RevokeDeviceConfirmDialog(
    deviceName: String,
    admittees: RemovalAdmittees,
    onConfirm: (alsoRemove: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Only composed while `pendingRevoke` is non-null, so this resets every time the dialog opens —
    // an acknowledgement that survived a cancel would defeat the point of having one.
    var acknowledged by remember { mutableStateOf(false) }
    // §7j.4: per admittee, "keep trusting it?" — default KEEP. An honest device the removed one
    // added is the common case; removing it by default would punish the user for the removal.
    // ⚠⚠ Track what the user explicitly UNticked, never a kept-set snapshot: the roster is live, and
    // an admittee that arrives while the dialog is open would otherwise show unticked and be removed
    // on confirm — an irreversible removal nobody chose. Pinned by
    // `SyncDashboardScreenTest.anAdmitteeArrivingWhileTheDialogIsOpen_isKept_notRemoved`.
    var deselected by remember { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.device_revoke_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(Res.string.device_revoke_dialog_body, deviceName))
                if (admittees.admittees.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.device_revoke_dialog_admittees_intro, deviceName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    admittees.admittees.forEach { admittee ->
                        val id = admittee.deviceId.get()
                        val name = admittee.name.get().ifEmpty { id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = id !in deselected,
                                    onValueChange = { kept -> deselected = if (kept) deselected - id else deselected + id },
                                    role = Role.Checkbox,
                                ),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Checkbox(checked = id !in deselected, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(Res.string.device_revoke_dialog_keep_admittee, name),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (admittees.unlistedCount > 0) {
                    // ⚠ Never imply the list is complete: the admitter field is self-written, and
                    // a device that blanked it is listed only in the roster, removable by id.
                    Text(
                        text = stringResource(
                            Res.string.device_revoke_dialog_unlisted,
                            admittees.unlistedCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The whole row toggles, matching CheckoutScreen's acknowledgement: a 20 dp
                        // hit target for the control standing in front of an irreversible act reads
                        // as unresponsive on touch.
                        .toggleable(
                            value = acknowledged,
                            onValueChange = { acknowledged = it },
                            role = Role.Checkbox,
                        ),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.device_revoke_dialog_acknowledge),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(admittees.admittees.map { it.deviceId.get() }.filter { it in deselected })
                },
                enabled = acknowledged,
            ) {
                Text(stringResource(Res.string.device_revoke_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.device_revoke_dialog_cancel))
            }
        },
    )
}

@Preview
@Composable
private fun SyncDashboardPreview() = PreviewBox {
    SyncDashboardContent(
        uiState = SyncDashboardUiState(
            isSyncEnabled = true
        ),
        currentDeviceId = "device-1",
        onRefresh = {},
        onRevokeDevice = { _, _ -> },
        onApproveDevice = {},
        onAddDevice = {},
        onShowRecoveryPhrase = {},
        onCreateRealm = {}
    )
}
