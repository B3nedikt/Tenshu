package app.tenshu.control.ui.screens.sync

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.control.shared.resources.pairing_error_no_authority
import app.tenshu.control.shared.resources.pairing_error_admitter_unrecognised
import app.tenshu.control.shared.resources.pairing_error_device_removed
import app.tenshu.control.shared.resources.pairing_error_this_device_removed
import app.tenshu.control.shared.resources.pairing_error_approver_revoked
import app.tenshu.ui.error.label
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.add_device_connection_lost
import app.tenshu.control.shared.resources.add_device_offer_body
import app.tenshu.control.shared.resources.add_device_offer_denied
import app.tenshu.control.shared.resources.add_device_offer_failed
import app.tenshu.control.shared.resources.add_device_offer_heading
import app.tenshu.control.shared.resources.add_device_offer_retry
import app.tenshu.control.shared.resources.add_device_offer_sas_label
import app.tenshu.control.shared.resources.add_device_offer_scanned_body
import app.tenshu.control.shared.resources.add_device_offer_scanned_heading
import app.tenshu.control.shared.resources.add_device_scan_body
import app.tenshu.control.shared.resources.add_device_show_body
import app.tenshu.control.shared.resources.add_device_show_heading
import app.tenshu.control.shared.resources.add_device_show_stop
import app.tenshu.control.shared.resources.add_device_show_success_body
import app.tenshu.control.shared.resources.add_device_show_success_heading
import app.tenshu.control.shared.resources.unfunded_realm_body
import app.tenshu.control.shared.resources.unfunded_realm_cta
import app.tenshu.control.shared.resources.unfunded_realm_heading
import app.tenshu.control.shared.resources.unfunded_realm_host_note
import app.tenshu.control.shared.resources.add_device_tab_scan
import app.tenshu.control.shared.resources.add_device_tab_show
import app.tenshu.control.shared.resources.add_device_title
import app.tenshu.control.shared.resources.pairing_approval_body
import app.tenshu.control.shared.resources.pairing_approval_cancel
import app.tenshu.control.shared.resources.pairing_approval_computing
import app.tenshu.control.shared.resources.pairing_approval_heading
import app.tenshu.control.shared.resources.pairing_approval_match_prompt
import app.tenshu.control.shared.resources.pairing_sas_version_hint
import app.tenshu.control.shared.resources.pairing_approval_sas_placeholder
import app.tenshu.control.shared.resources.pairing_manual_continue
import app.tenshu.control.shared.resources.pairing_manual_heading
import app.tenshu.control.shared.resources.pairing_manual_label
import app.tenshu.control.shared.resources.pairing_manual_placeholder
import app.tenshu.control.shared.resources.pairing_name_body
import app.tenshu.control.shared.resources.pairing_name_continue
import app.tenshu.control.shared.resources.pairing_name_heading
import app.tenshu.control.shared.resources.pairing_name_label
import app.tenshu.control.shared.resources.pairing_name_placeholder
import app.tenshu.control.shared.resources.pairing_name_registering
import app.tenshu.control.shared.resources.pairing_name_rename_later
import app.tenshu.control.shared.resources.pairing_responder_adding
import app.tenshu.control.shared.resources.pairing_responder_approve
import app.tenshu.control.shared.resources.pairing_responder_body
import app.tenshu.control.shared.resources.pairing_responder_deny
import app.tenshu.control.shared.resources.pairing_responder_heading
import app.tenshu.control.shared.resources.pairing_responder_hub_verified_note
import app.tenshu.control.shared.resources.pairing_responder_match_note
import app.tenshu.control.shared.resources.pairing_responder_success_match_note
import app.tenshu.control.shared.resources.pairing_responder_reading
import app.tenshu.control.shared.resources.pairing_scan_enter_code
import app.tenshu.control.shared.resources.pairing_scan_error_hub_not_armed
import app.tenshu.control.shared.resources.pairing_scan_error_hub_unsupported
import app.tenshu.control.shared.resources.pairing_scan_error_hub_verify_failed
import app.tenshu.control.shared.resources.pairing_scan_error_malformed
import app.tenshu.control.shared.resources.pairing_scan_error_unknown_role
import app.tenshu.control.shared.resources.pairing_scan_hint
import app.tenshu.control.shared.resources.pairing_success_body
import app.tenshu.control.shared.resources.pairing_success_continue
import app.tenshu.control.shared.resources.pairing_success_heading
import app.tenshu.control.shared.resources.pairing_success_note
import app.tenshu.control.shared.resources.pairing_title_approval
import app.tenshu.control.shared.resources.pairing_title_name_device
import app.tenshu.control.shared.resources.pairing_title_responder_approval
import app.tenshu.control.shared.resources.pairing_title_responder_success
import app.tenshu.control.shared.resources.pairing_title_success
import app.tenshu.control.shared.resources.qr_code_content_description
import app.tenshu.control.shared.resources.sync_dashboard_copy_clipboard
import app.tenshu.control.shared.resources.sync_dashboard_manual_code_label
import app.tenshu.control.shared.resources.sync_dashboard_pairing_error
import app.tenshu.ui.components.QrCode
import app.tenshu.ui.feedback.AppAutoContinueButton
import app.tenshu.ui.feedback.AutoContinueState
import app.tenshu.ui.feedback.appCancelAutoContinueOnTouch
import app.tenshu.ui.feedback.rememberAutoContinueState
import app.tenshu.ui.components.QrScanner
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.input.AppTextField
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Unified "Add a device" screen (`docs/proposals/control-pairing-ux.md` Phase 2). A
 * segmented control switches between the two pairing roles:
 *  - **Scan** — the joiner wizard (ScanQr → ManualCode → NameDevice → Approval → Success).
 *  - **Show** — the host origin QR + copyable pairing code, backed by the process-lifetime
 *    `HostPairingMonitor`. Approval is the app-global modal, not part of this screen.
 *
 * The segmented control is shown only at "tab-root" states (Scan's ScanQr/ManualCode, or
 * Show); it's hidden once the user is committed to the deeper joiner flow (NameDevice /
 * Approval / Success). The VM owns transitions and the joiner handshake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingWizardScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    /**
     * "Set up storage" from a terminal step that discovered the realm cannot sync
     * (`enforcement-followup-tracks.md` Track D). Deep-links to the account/plan route; defaulted
     * to a no-op so a caller that has nowhere to send the user renders the explanation without a
     * dead button.
     */
    onSetUpStorage: () -> Unit = {},
    viewModel: PairingWizardViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.step) {
        if (state.step == PairingStep.Done) onComplete()
    }

    // "tab-root" = the user is on a tab landing surface (can still freely switch tabs / go
    // back out of the whole screen), as opposed to being committed to the joiner flow.
    val atTabRoot = isTabRoot(state)

    // Auto-continue (control-pairing-ux.md §9): one cancel state per terminal, so reaching a new
    // terminal starts un-cancelled, and a tap anywhere on the screen stops the countdown.
    val autoContinue = rememberAutoContinueState(
        state.mode, state.step, state.offerRole, state.hostPairingComplete, state.offerPaired,
    )

    Scaffold(
        topBar = {
            AppTopAppBar(
                title = if (atTabRoot) stringResource(Res.string.add_device_title)
                else deepStepTitle(state.step),
                onBack = {
                    when {
                        atTabRoot -> onBack()
                        // A Show-tab terminal is no longer tab-root (above), but back from it must
                        // still LEAVE the wizard — `onBackToScanStart` below would reset the joiner
                        // flow, which is not the flow the user is looking at.
                        state.mode == AddDeviceMode.Show -> onBack()
                        state.step == PairingStep.Success ||
                            state.step == PairingStep.ResponderSuccess -> viewModel.onSuccessContinue()
                        else -> viewModel.onBackToScanStart()
                    }
                },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .appCancelAutoContinueOnTouch(autoContinue),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Fill the height and centre the content. The Scan step's camera claims the
                // leftover space via weight(), so fixed elements (text, buttons) always fit;
                // shorter steps (Show / manual / approval) stay vertically centred as before.
                verticalArrangement = Arrangement.Center,
            ) {
                if (atTabRoot) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = state.mode == AddDeviceMode.Scan,
                            onClick = viewModel::onSelectScan,
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) {
                            Text(stringResource(Res.string.add_device_tab_scan))
                        }
                        SegmentedButton(
                            selected = state.mode == AddDeviceMode.Show,
                            onClick = viewModel::onSelectShow,
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) {
                            Text(stringResource(Res.string.add_device_tab_show))
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                when (state.mode) {
                    // The Show tab's role branches on realm binding (VM-decided): an unpaired
                    // device shows the §5.1 offerer QR (an existing device scans + approves);
                    // a paired device hosts the origin QR as before.
                    AddDeviceMode.Show -> if (state.offerRole) OfferCodeStep(
                        offerUri = state.offerUri,
                        paired = state.offerPaired,
                        scanned = state.offerScanned,
                        sas = state.offerSas,
                        denied = state.offerDenied,
                        failed = state.offerFailed,
                        removed = state.offerRemoved,
                        onRetry = viewModel::onRetryOffer,
                        onStop = viewModel::onStopShowing,
                        onContinue = viewModel::onSuccessContinue,
                        realmUnfunded = state.realmUnfunded,
                        onSetUpStorage = onSetUpStorage,
                        autoContinueArmed = state.autoContinueArmed,
                        autoContinue = autoContinue,
                    ) else ShowCodeStep(
                        pairingString = state.hostPairingString,
                        connectionLost = state.hostConnectionLost,
                        pairingComplete = state.hostPairingComplete,
                        realmUnfunded = state.realmUnfunded,
                        startError = pairingErrorText(state.error),
                        onRetry = viewModel::onSelectShow,
                        onStop = viewModel::onStopShowing,
                        onDone = onComplete,
                        onSetUpStorage = onSetUpStorage,
                        autoContinueArmed = state.autoContinueArmed,
                        autoContinue = autoContinue,
                    )

                    AddDeviceMode.Scan -> when (state.step) {
                        // PickMethod is subsumed (§3.2); Scan defaults to ScanQr. Kept in the
                        // taxonomy, but if it ever surfaces, render the scanner.
                        PairingStep.PickMethod,
                        PairingStep.ScanQr -> ScanQrStep(
                            onScanned = viewModel::onQrScanned,
                            onUseManual = viewModel::onPickManual,
                            error = scanErrorText(state.scanError) ?: pairingErrorText(state.error),
                        )
                        PairingStep.ManualCode -> ManualCodeStep(
                            onSubmit = viewModel::onQrScanned,
                            error = scanErrorText(state.scanError) ?: pairingErrorText(state.error),
                        )
                        PairingStep.NameDevice -> NameDeviceStep(
                            isLoading = state.isLoading,
                            error = pairingErrorText(state.error),
                            onConfirm = viewModel::confirmPairing,
                        )
                        PairingStep.Approval -> ApprovalStep(
                            sas = state.sas,
                            status = state.status,
                            error = pairingErrorText(state.error),
                            onCancel = viewModel::onBackToScanStart,
                        )
                        PairingStep.Success -> SuccessStep(
                            realmUnfunded = state.realmUnfunded,
                            onContinue = viewModel::onSuccessContinue,
                            onSetUpStorage = onSetUpStorage,
                            autoContinueArmed = state.autoContinueArmed,
                            autoContinue = autoContinue,
                        )
                        PairingStep.ResponderApproval -> ResponderApprovalStep(
                            deviceName = state.responderDeviceName,
                            sas = state.responderSas,
                            isHub = state.responderIsHub,
                            isLoading = state.isLoading,
                            error = pairingErrorText(state.error),
                            onApprove = viewModel::approveOffer,
                            onDeny = viewModel::denyOffer,
                        )
                        PairingStep.ResponderSuccess -> ResponderSuccessStep(
                            sas = state.responderSas,
                            isHub = state.responderIsHub,
                            onContinue = viewModel::onSuccessContinue,
                            autoContinueArmed = state.autoContinueArmed,
                            autoContinue = autoContinue,
                        )
                        PairingStep.Done -> CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

/**
 * "tab-root" = a tab landing surface. True for the whole Show tab, and for the Scan tab while
 * the user is still on the entry steps (scanner / manual code). False once the joiner is
 * committed (NameDevice / Approval / Success / Done), where the segmented control + the
 * "Add a device" title give way to the per-step joiner chrome.
 */
private fun isTabRoot(state: PairingWizardUiState): Boolean = when (state.mode) {
    // ⚠ The Show tab's TERMINALS are not tab roots: "Device added" is an outcome, not a landing.
    // Keeping the segmented control there offers a choice that no longer means anything and pushes
    // the outcome down behind a title + tab strip it has no use for. (Reported on iOS 2026-08-26;
    // the Compose twin had it too.)
    AddDeviceMode.Show -> !state.hostPairingComplete && !state.offerPaired
    AddDeviceMode.Scan -> state.step == PairingStep.PickMethod ||
        state.step == PairingStep.ScanQr ||
        state.step == PairingStep.ManualCode
}

/**
 * Copy for a typed [PairingError].
 *
 * ⭐ [PairingError.AdmitterUnrecognised]'s sentence is the one `core-error-copy-catalogue.md` §1
 * opens with. It was a string literal in `ApprovePairingOfferImplementation`; it is not lost, it
 * is here, where a translator can reach it.
 */
@Composable
private fun pairingErrorText(error: PairingError?): String? = when (error) {
    null -> null
    PairingError.AdmitterUnrecognised -> stringResource(Res.string.pairing_error_admitter_unrecognised)
    PairingError.NoAuthority -> stringResource(Res.string.pairing_error_no_authority)
    PairingError.DeviceRemoved -> stringResource(Res.string.pairing_error_device_removed)
    PairingError.ApproverRevoked -> stringResource(Res.string.pairing_error_approver_revoked)
    PairingError.ThisDeviceRemoved -> stringResource(Res.string.pairing_error_this_device_removed)
    is PairingError.Unexpected -> error.copyKey.label()
}

/** Localised copy for the typed scan-path errors (`sync-pairing-inverted-direction.md` §3.2). */
@Composable
private fun scanErrorText(scanError: PairingScanError?): String? = when (scanError) {
    PairingScanError.UnknownRole -> stringResource(Res.string.pairing_scan_error_unknown_role)
    PairingScanError.Malformed -> stringResource(Res.string.pairing_scan_error_malformed)
    PairingScanError.HubVerifyFailed -> stringResource(Res.string.pairing_scan_error_hub_verify_failed)
    PairingScanError.HubNotArmed -> stringResource(Res.string.pairing_scan_error_hub_not_armed)
    PairingScanError.HubUnsupported -> stringResource(Res.string.pairing_scan_error_hub_unsupported)
    null -> null
}

@Composable
private fun deepStepTitle(step: PairingStep): String = when (step) {
    PairingStep.NameDevice -> stringResource(Res.string.pairing_title_name_device)
    PairingStep.Approval -> stringResource(Res.string.pairing_title_approval)
    PairingStep.Success -> stringResource(Res.string.pairing_title_success)
    PairingStep.ResponderApproval -> stringResource(Res.string.pairing_title_responder_approval)
    PairingStep.ResponderSuccess -> stringResource(Res.string.pairing_title_responder_success)
    else -> stringResource(Res.string.add_device_title)
}

/**
 * "Show a code" surface (§3.2): origin QR + copyable pairing code, backed by the host
 * monitor. Renders the [connectionLost] banner (monitor-owned, Phase-1 follow-up), a
 * [startError] with a retry when the start minted no code, and an explicit Stop button that ends
 * the host session.
 */
@Composable
internal fun ColumnScope.ShowCodeStep(
    pairingString: String?,
    connectionLost: Boolean,
    pairingComplete: Boolean,
    realmUnfunded: Boolean,
    startError: String?,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
    onSetUpStorage: () -> Unit,
    autoContinueArmed: Boolean = false,
    autoContinue: AutoContinueState = rememberAutoContinueState(),
) {
    // Terminal state: the host approved a request and the proxy accepted it. Show a "device
    // added" confirmation with a Done button instead of falling back into the no-code spinner
    // (approval clears pairingString, so without this branch the screen would spin forever).
    if (pairingComplete) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(Res.string.add_device_show_success_heading),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.politeAnnouncement(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                // D2: the host knows FIRST — its own pushes have been refused all along — so the
                // device that ran the pairing is where "…but nothing will reach it" belongs.
                text = if (realmUnfunded) stringResource(Res.string.unfunded_realm_host_note)
                else stringResource(Res.string.add_device_show_success_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (realmUnfunded) {
                Button(onClick = onSetUpStorage, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.unfunded_realm_cta))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.pairing_success_continue))
                }
            } else {
                // §9: the host terminal arms with no settle delay (no join race on this side).
                AppAutoContinueButton(
                    text = stringResource(Res.string.pairing_success_continue),
                    onContinue = onDone,
                    armed = autoContinueArmed,
                    state = autoContinue,
                )
            }
        }
        return
    }

    // Take the leftover height after the segmented tabs and scroll within it. The QR keeps its
    // full 250.dp (shrinking it would hurt scannability); instead, when the "Can't reach the
    // relay" banner appears and pushes the total past the screen, the manual code + Stop button
    // stay reachable by scrolling rather than getting clipped.
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.add_device_show_heading),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.add_device_show_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))

        // ⚠ No "this device isn't recognised, go and scan instead" surface here any more: when the
        // relay refuses this device the VM switches this tab to the OFFER role, so the user is shown
        // a code to be approved — the same gesture as adding any new device — rather than a dialog
        // telling them to go the other way. See `PairingWizardViewModel.onSelectShow`.
        if (connectionLost) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(Res.string.add_device_connection_lost),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (pairingString != null) {
            @Suppress("DEPRECATION")
            val clipboardManager = LocalClipboardManager.current
            QrCode(
                data = pairingString,
                modifier = Modifier.size(250.dp),
                contentDescription = stringResource(Res.string.qr_code_content_description),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(Res.string.sync_dashboard_manual_code_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            AppTextField(
                value = pairingString,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(
                        onClick = { clipboardManager.setText(AnnotatedString(pairingString)) }
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(Res.string.sync_dashboard_copy_clipboard),
                        )
                    }
                },
            )
        } else if (startError != null) {
            // The start produced no session, so nothing is coming: say why and offer another
            // attempt. Stop (below) still leaves for Scan.
            Text(
                text = startError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.add_device_offer_retry))
            }
        } else if (!connectionLost) {
            // No code yet (the host session is still starting) and no error — show a spinner.
            CircularProgressIndicator()
        } else {
            Text(stringResource(Res.string.sync_dashboard_pairing_error))
        }

        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(Res.string.add_device_show_stop))
        }
    }
}

/**
 * Offerer "Show a code" surface (`sync-pairing-inverted-direction.md` §5.1) — the Show tab on
 * an **unpaired** device. Renders the `tenshu://offer?…` QR + copyable code for a device that
 * already has Tenshu to scan and approve; terminal states: [paired] (success + the SAS for
 * post-hoc comparison against the responder's prompt), [denied], [failed] (couldn't mint).
 */
@Composable
private fun ColumnScope.OfferCodeStep(
    offerUri: String?,
    paired: Boolean,
    scanned: Boolean,
    sas: String?,
    denied: Boolean,
    failed: Boolean,
    removed: Boolean = false,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    realmUnfunded: Boolean,
    onSetUpStorage: () -> Unit,
    autoContinueArmed: Boolean = false,
    autoContinue: AutoContinueState = rememberAutoContinueState(),
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (paired || scanned || denied || failed || removed) Arrangement.Center else Arrangement.Top,
    ) {
        when {
            // Terminal success: the responder approved and the proxy installed the keys —
            // this device is paired. Show the SAS so the user can compare it against the
            // other device's approval prompt; Continue routes through the wizard's normal
            // completion (→ SetupUnlock → Home during onboarding).
            paired -> {
                Text(
                    text = stringResource(Res.string.pairing_success_heading),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.politeAnnouncement(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(Res.string.pairing_success_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (sas != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(Res.string.add_device_offer_sas_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // §4a.4 idiom: "XXX YYY" split for easy visual comparison.
                    val display = if (sas.length >= 6) "${sas.substring(0, 3)} ${sas.substring(3)}" else sas
                    Text(
                        text = display,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
                if (realmUnfunded) {
                    // The offerer just joined too, and inherits the same silence (D1).
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(Res.string.unfunded_realm_heading),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.unfunded_realm_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onSetUpStorage, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.unfunded_realm_cta))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.pairing_success_continue))
                    }
                    return@Column
                }
                Spacer(modifier = Modifier.height(24.dp))
                // §9: armed only when the SAS was on this screen BEFORE the responder approved
                // (the scanned announce). Without it the code above is the only comparison there
                // was — T-P-07's tripwire — and the screen must wait for the user.
                AppAutoContinueButton(
                    text = stringResource(Res.string.pairing_success_continue),
                    onContinue = onContinue,
                    armed = autoContinueArmed,
                    state = autoContinue,
                )
            }

            // ⚠ Before `failed`: a removed identity is refused on every retry, so it gets the remedy
            // (forget this device) instead of "try again" (F2′, `admission-revocation-names-the-capability.md` §7i.5).
            removed -> {
                Text(
                    text = stringResource(Res.string.pairing_error_this_device_removed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.add_device_show_stop))
                }
            }

            denied || failed -> {
                Text(
                    text = stringResource(
                        if (denied) Res.string.add_device_offer_denied
                        else Res.string.add_device_offer_failed
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.add_device_offer_retry))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.add_device_show_stop))
                }
            }

            // A3: the responder has scanned and announced, but has not decided. Replace the QR
            // with the SAS — the QR has done its job, and the code is now the thing the user must
            // act on. Both screens hold it simultaneously, so this is a glance, not a memory test.
            scanned -> {
                Text(
                    text = stringResource(Res.string.add_device_offer_scanned_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.add_device_offer_scanned_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Same 3-3 split as every other SAS surface — the codes must be
                        // comparable at a glance or the ceremony is harder than it needs to be.
                        val display = sas?.let {
                            if (it.length >= 6) "${it.substring(0, 3)} ${it.substring(3)}" else it
                        }.orEmpty()
                        Text(
                            text = display,
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.add_device_show_stop))
                }
            }
            else -> {
                Text(
                    text = stringResource(Res.string.add_device_offer_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.add_device_offer_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(20.dp))

                if (offerUri != null) {
                    @Suppress("DEPRECATION")
                    val clipboardManager = LocalClipboardManager.current
                    QrCode(
                        data = offerUri,
                        modifier = Modifier.size(250.dp),
                        contentDescription = stringResource(Res.string.qr_code_content_description),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(Res.string.sync_dashboard_manual_code_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    AppTextField(
                        value = offerUri,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(
                                onClick = { clipboardManager.setText(AnnotatedString(offerUri)) }
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(Res.string.sync_dashboard_copy_clipboard),
                                )
                            }
                        },
                    )
                } else {
                    CircularProgressIndicator()
                }

                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(Res.string.add_device_show_stop))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ScanQrStep(
    onScanned: (String) -> Unit,
    onUseManual: () -> Unit,
    error: String?,
) {
    Text(
        text = stringResource(Res.string.add_device_scan_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    // The preview claims the space left over after the fixed text/buttons via weight(), capped at
    // 360.dp on tall screens. On short phones it shrinks instead of pushing the hint + button
    // off-screen. Clipped to a rounded box for polish and to keep the camera feed in its bounds.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .heightIn(max = 360.dp)
            .clip(MaterialTheme.shapes.large),
    ) {
        QrScanner(
            onResult = onScanned,
            modifier = Modifier.fillMaxSize(),
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(Res.string.pairing_scan_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    TextButton(onClick = onUseManual) {
        Text(stringResource(Res.string.pairing_scan_enter_code))
    }
    error?.let {
        Spacer(modifier = Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ManualCodeStep(
    onSubmit: (String) -> Unit,
    error: String?,
) {
    var code by remember { mutableStateOf("") }
    Text(
        text = stringResource(Res.string.pairing_manual_heading),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    AppTextField(
        value = code,
        onValueChange = { code = it },
        label = stringResource(Res.string.pairing_manual_label),
        placeholder = stringResource(Res.string.pairing_manual_placeholder),
        modifier = Modifier.fillMaxWidth(),
        errorText = error,
    )
    Spacer(modifier = Modifier.height(20.dp))
    Button(
        onClick = { onSubmit(code) },
        enabled = code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.pairing_manual_continue))
    }
}

@Composable
private fun NameDeviceStep(
    isLoading: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
) {
    // Prefill with the platform-detected name (e.g., "MacBook Pro", "Pixel 7")
    // so users on a fresh pair don't have to think — they can hit Continue and
    // get a sensible default that matches what their OS exposes. Same idiom
    // `NewUserViewModel.createRealm` and `RecoveryViewModel` use for their
    // device-name fields. ⚠ This is the user's LABEL (`Device.name`); the hardware model is
    // published separately by the device itself (`DeviceHardware`), so editing it loses nothing.
    var name by remember { mutableStateOf(org.tenshu.core.common.util.getPlatformDeviceName()) }
    Text(
        text = stringResource(Res.string.pairing_name_heading),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(Res.string.pairing_name_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(20.dp))
    AppTextField(
        value = name,
        onValueChange = { name = it },
        label = stringResource(Res.string.pairing_name_label),
        placeholder = stringResource(Res.string.pairing_name_placeholder),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(Res.string.pairing_name_rename_later),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = { onConfirm(name) },
        enabled = name.isNotBlank() && !isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (isLoading) stringResource(Res.string.pairing_name_registering)
            else stringResource(Res.string.pairing_name_continue)
        )
    }
    error?.let {
        Spacer(modifier = Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ApprovalStep(
    sas: String?,
    status: String?,
    error: String?,
    onCancel: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.pairing_approval_heading),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(Res.string.pairing_approval_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(28.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.pairing_approval_match_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Spec §4a.4: render the SAS as "XXX YYY" (3-3 split) for
            // easier visual comparison with the host's prompt.
            val display = sas?.let { "${it.substring(0, 3)} ${it.substring(3)}" }
                ?: stringResource(Res.string.pairing_approval_sas_placeholder)
            Text(
                text = display,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
            if (sas != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.pairing_sas_version_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (sas == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.pairing_approval_computing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CircularProgressIndicator()
        }
    }
    Spacer(modifier = Modifier.height(20.dp))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.pairing_approval_cancel))
    }
    error?.let {
        Spacer(modifier = Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The joiner's terminal. [realmUnfunded] swaps the body — **not** the heading: the device really
 * is in the realm, and only the promise about syncing is withdrawn (Track D1).
 *
 * ⚠⚠ The default body says *"your existing data will sync over the next few minutes"*. On a realm
 * with no storage plan the relay refuses every write, so that sentence is a promise nothing can
 * keep — printed at the moment the user is paying the most attention, which is exactly why this
 * branch exists rather than leaving it to a banner in another app.
 */
@Composable
private fun SuccessStep(
    realmUnfunded: Boolean,
    onContinue: () -> Unit,
    onSetUpStorage: () -> Unit,
    autoContinueArmed: Boolean,
    autoContinue: AutoContinueState,
) {
    Text(
        text = stringResource(Res.string.pairing_success_heading),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.politeAnnouncement(),
    )
    Spacer(modifier = Modifier.height(16.dp))
    if (realmUnfunded) {
        Text(
            text = stringResource(Res.string.unfunded_realm_heading),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.unfunded_realm_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onSetUpStorage, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.unfunded_realm_cta))
        }
        Spacer(modifier = Modifier.height(8.dp))
        // ⚠ Never a dead end: joining succeeded, so "Continue" must still be here. The user may
        // decline storage and still use the device they just added.
        OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.pairing_success_continue))
        }
        return
    }
    Text(
        text = stringResource(Res.string.pairing_success_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(Res.string.pairing_success_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))
    // §9: arms only once the join-race settle has passed — until then [realmUnfunded] may still
    // swap this body for the "Nothing will sync yet" branch above.
    AppAutoContinueButton(
        text = stringResource(Res.string.pairing_success_continue),
        onContinue = onContinue,
        armed = autoContinueArmed,
        state = autoContinue,
    )
}

/**
 * Offer-flow responder approval (`sync-pairing-inverted-direction.md` §5.2): this existing device
 * scanned a *new* device's `tenshu://offer?…` QR and now confirms admission — the inverse of
 * [ApprovalStep], which waits on the *host*. Mirrors the host-approval visual vocabulary
 * (device name + SAS + no passive dismiss). While the scanned offer is still being parsed
 * ([deviceName]/[sas] null) it shows a brief reading state.
 */
@Composable
private fun ResponderApprovalStep(
    deviceName: String?,
    sas: String?,
    isHub: Boolean,
    isLoading: Boolean,
    error: String?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    // The hub flow has no SAS by design (hub proposal §11.4) — only the device name
    // gates the loading state there.
    if (deviceName == null || (sas == null && !isHub)) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.pairing_responder_reading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        return
    }

    Text(
        text = stringResource(Res.string.pairing_responder_heading, deviceName),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(Res.string.pairing_responder_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(28.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isHub || sas == null) {
                // Hub flow (hub proposal §11.4): no SAS — the proxy already verified the
                // relay-fetched offer's signature against the key on the scanned PRINTED
                // label, which replaces the comparison ceremony a headless device cannot
                // render. Say so, instead of showing a code with nothing to compare it to.
                Text(
                    text = stringResource(Res.string.pairing_responder_hub_verified_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    // NOT the join flow's "confirm it matches" prompt: the offerer cannot show
                    // the SAS until this side approves (it is derived from THIS device's
                    // ephemeral key, which only rides the approve POST — pairing-flow.md §4.5),
                    // so the comparison here is necessarily post-approval.
                    text = stringResource(Res.string.pairing_responder_match_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // §4a.4 idiom: render the SAS "XXX YYY" (3-3 split) for easy visual comparison.
                val display = if (sas.length >= 6) "${sas.substring(0, 3)} ${sas.substring(3)}" else sas
                Text(
                    text = display,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onApprove,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (isLoading) stringResource(Res.string.pairing_responder_adding)
            else stringResource(Res.string.pairing_responder_approve)
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = onDeny,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.pairing_responder_deny))
    }
    error?.let {
        Spacer(modifier = Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Offer-flow responder terminal — the scanned new device was admitted.
 *
 * ⚠ Auto-continue (`control-pairing-ux.md` §9) arms only in the hub flow: with a SAS this screen
 * IS the side-by-side comparison, and the responder cannot know whether the offerer saw the code
 * before approving. [autoContinueArmed] already encodes that ([PairingWizardUiState.autoContinueArmed]).
 */
@Composable
internal fun ResponderSuccessStep(
    sas: String?,
    isHub: Boolean,
    onContinue: () -> Unit,
    autoContinueArmed: Boolean = false,
    autoContinue: AutoContinueState = rememberAutoContinueState(),
) {
    Text(
        text = stringResource(Res.string.add_device_show_success_heading),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.politeAnnouncement(),
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(Res.string.add_device_show_success_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    // ⚠⚠ The code STAYS on screen here, and that is the whole point of this step showing it.
    // The offer flow's SAS is post-approval BY CONSTRUCTION — the offerer cannot derive it until
    // this device's ephemeral key rides the approve POST (pairing-flow.md §4.5) — so the user used
    // to approve, watch the code disappear, and only then be asked to compare it against the new
    // device's screen **from memory**. A comparison needing a six-digit code carried across an
    // action is one that mostly does not happen, which makes T-P-07's tripwire weaker in practice
    // than "detected, not prevented" suggests. Keeping it visible costs nothing and turns the
    // comparison into a glance. `sync-pairing-scanned-announce.md` §5.3.
    // ⚠ It does NOT fix the timing: the sealed payload has already been sent. Only the scanned
    // announce (§2 of that proposal) moves the comparison before the send.
    // ⚠ The hub flow has no SAS by design (hub proposal §11.4) — show nothing, not an empty box.
    if (!isHub && sas != null) {
        Spacer(modifier = Modifier.height(24.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.pairing_responder_success_match_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Same 3-3 split as the approval step: the two screens must render the code
                // identically or the comparison this exists for is harder than it needs to be.
                val display = if (sas.length >= 6) "${sas.substring(0, 3)} ${sas.substring(3)}" else sas
                Text(
                    text = display,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    AppAutoContinueButton(
        text = stringResource(Res.string.pairing_success_continue),
        onContinue = onContinue,
        // Belt and braces: never arm with a SAS on screen, whatever the caller passes.
        armed = autoContinueArmed && (isHub || sas == null),
        state = autoContinue,
    )
}

/** A terminal's title, announced politely when it appears (§9 — the screen may leave by itself). */
private fun Modifier.politeAnnouncement(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
