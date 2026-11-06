package app.tenshu.control.ui.sync

import app.tenshu.control.shared.resources.pairing_error_no_authority
import app.tenshu.control.shared.resources.pairing_error_admitter_unrecognised
import app.tenshu.ui.error.label
import app.tenshu.control.ui.screens.sync.PairingError
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.sync_dashboard_dialog_approve
import app.tenshu.control.shared.resources.sync_dashboard_dialog_authorization
import app.tenshu.control.shared.resources.sync_dashboard_dialog_deny
import app.tenshu.control.shared.resources.sync_dashboard_join_request
import app.tenshu.control.shared.resources.pairing_sas_version_hint
import app.tenshu.control.shared.resources.sync_dashboard_sas_prompt
import app.tenshu.control.ui.common.RaiseDialogToFront
import app.tenshu.ui.chrome.AppDialog
import org.jetbrains.compose.resources.stringResource
import org.tenshu.sync.PairingRequest

/**
 * App-global pairing-approval modal (`docs/proposals/control-pairing-ux.md` Phase 1).
 *
 * Extracted verbatim from the Sync screen's old approval-mode dialog so the iCloud-feel
 * approval — a paired device "is requesting to join" + a SAS comparison code + Approve /
 * Deny — overlays *any* destination, not just the Sync screen. It is mounted at the App
 * root, driven by [HostPairingMonitor.activeRequest][app.tenshu.control.sync.HostPairingMonitor],
 * so it appears wherever the user is when a request arrives.
 *
 * It is SAS-bearing and must not be reducible to a passive dismiss: there is no
 * tap-outside / back dismissal — the user must explicitly Approve or Deny.
 *
 * ## ⚠⚠ [RaiseDialogToFront] is load-bearing here, more than anywhere else
 *
 * This is the **most asynchronous modal in the app**: it arrives from
 * [HostPairingMonitor][app.tenshu.control.sync.HostPairingMonitor]'s poll, on no user action at all,
 * so Control is very often not the frontmost application when it appears — and on desktop a Compose
 * dialog raised that way lands behind its own parent or behind another app entirely.
 *
 * ⚠ And because it refuses passive dismissal *by design*, a copy the user never sees does not time
 * out or fall away. The pairing device simply waits, forever, on an approval sitting behind another
 * window. Every other invisible-modal case this project has hit cost a confusing minute; this one
 * costs a pairing that never completes.
 */
@Composable
fun HostPairingApprovalDialog(
    request: PairingRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    error: PairingError? = null,
) {
    RaiseDialogToFront()
    AppDialog(
        title = stringResource(Res.string.sync_dashboard_dialog_authorization),
        // SAS-bearing: no passive dismiss. Back/outside do nothing; only Approve/Deny
        // resolve it.
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        confirmButton = {
            Button(onClick = onApprove) {
                Text(stringResource(Res.string.sync_dashboard_dialog_approve))
            }
        },
        dismissButton = {
            Button(
                onClick = onDeny,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(stringResource(Res.string.sync_dashboard_dialog_deny))
            }
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Devices,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(
                        Res.string.sync_dashboard_join_request,
                        request.deviceName.get(),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                // §6.3 SAS comparison surface — derived locally on the proxy from this
                // device's ephemeral private key + the requester's public key (see
                // GetPendingPairingRequestsImplementation). Render "XXX YYY" (3-3 split)
                // for easier visual comparison with the requester's §4a.4 prompt; both
                // sides converge on the same value when no MITM is present.
                request.sas?.get()?.let { sas ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(Res.string.sync_dashboard_sas_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (sas.length == 6) "${sas.substring(0, 3)} ${sas.substring(3)}" else sas,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(Res.string.pairing_sas_version_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                // Approval failure (P0 guard): most often this device is a phrase-less joiner
                // and can't admit new devices. Surface the reason so the modal isn't a silent
                // dismiss; the user taps Deny to close it.
                error?.let { pairingError ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        // ⚠⚠ Copy the app owns, keyed by a typed reason — never the proxy's
                        // sentence (`core-error-copy-catalogue.md` §5.0).
                        text = when (pairingError) {
                            PairingError.AdmitterUnrecognised ->
                                stringResource(Res.string.pairing_error_admitter_unrecognised)
                            PairingError.NoAuthority ->
                                stringResource(Res.string.pairing_error_no_authority)
                            is PairingError.Unexpected -> pairingError.copyKey.label()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    )
}
