package app.tenshu.fit.ui.screens.record

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_record_permission_continue
import app.tenshu.fit.shared.resources.fit_record_permission_hr_body
import app.tenshu.fit.shared.resources.fit_record_permission_hr_title
import app.tenshu.fit.shared.resources.fit_record_permission_motion_body
import app.tenshu.fit.shared.resources.fit_record_permission_motion_title
import app.tenshu.fit.shared.resources.fit_record_permission_not_now
import app.tenshu.fit.ui.components.FitIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The in-app rationale shown **before** a capability's OS permission dialog (§9.8) — so the system
 * prompt arrives with context, the Google-Fit priming pattern. Rendered only when the capability
 * is not already granted (the caller gates on [RecorderPermissionGate.isGranted]); on desktop that
 * is always `true`, so the primer never appears.
 *
 * [onContinue] triggers the actual [RecorderPermissionGate.request]; [onDismiss] backs out without
 * asking. The recorder degrades per stream, so dismissing simply forgoes that capability.
 */
@Composable
fun RecorderPermissionPrimer(
    capability: RecorderCapability,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, body, icon) = when (capability) {
        RecorderCapability.Motion -> Triple(
            stringResource(Res.string.fit_record_permission_motion_title),
            stringResource(Res.string.fit_record_permission_motion_body),
            FitIcons.Gps,
        )
        RecorderCapability.HeartRate -> Triple(
            stringResource(Res.string.fit_record_permission_hr_title),
            stringResource(Res.string.fit_record_permission_hr_body),
            FitIcons.Heart,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(Res.string.fit_record_permission_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.fit_record_permission_not_now))
            }
        },
    )
}
