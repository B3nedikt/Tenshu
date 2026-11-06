package app.tenshu.drive.ui.share

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.tenshu.drive.data.StorageSetupLauncher
import app.tenshu.drive.domain.StorageGateDecision
import app.tenshu.drive.shared.resources.Res
import app.tenshu.drive.shared.resources.storage_gate_dismiss
import app.tenshu.drive.shared.resources.storage_gate_full_body
import app.tenshu.drive.shared.resources.storage_gate_full_cta
import app.tenshu.drive.shared.resources.storage_gate_full_title
import app.tenshu.drive.shared.resources.storage_gate_no_storage_body
import app.tenshu.drive.shared.resources.storage_gate_no_storage_cta
import app.tenshu.drive.shared.resources.storage_gate_no_storage_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Tier-1 gate dialog (`docs/proposals/storage-capacity-gating.md` §2, §4).
 * Shown instead of running a storage-consuming action when the user has no
 * storage ([StorageGateDecision.NeedsStorage]) or is out of room
 * ([StorageGateDecision.Full]). The primary CTA routes to storage setup via the
 * platform [StorageSetupLauncher]; [StorageGateDecision.Allow] never reaches here.
 */
@Composable
fun StorageGateDialog(
    decision: StorageGateDecision,
    onDismiss: () -> Unit,
    launcher: StorageSetupLauncher = koinInject(),
) {
    val isFull = decision is StorageGateDecision.Full
    val title = stringResource(
        if (isFull) Res.string.storage_gate_full_title else Res.string.storage_gate_no_storage_title,
    )
    val body = stringResource(
        if (isFull) Res.string.storage_gate_full_body else Res.string.storage_gate_no_storage_body,
    )
    val cta = stringResource(
        if (isFull) Res.string.storage_gate_full_cta else Res.string.storage_gate_no_storage_cta,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = {
                    launcher.openStorageSetup()
                    onDismiss()
                },
            ) { Text(cta) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.storage_gate_dismiss)) }
        },
    )
}
