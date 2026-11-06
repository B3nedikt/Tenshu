package app.tenshu.control.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.forget_device_acknowledge
import app.tenshu.control.shared.resources.forget_device_body
import app.tenshu.control.shared.resources.forget_device_cancel
import app.tenshu.control.shared.resources.forget_device_confirm
import app.tenshu.control.shared.resources.forget_device_phrase_warning
import app.tenshu.control.shared.resources.forget_device_title
import app.tenshu.control.shared.resources.forget_device_working
import org.jetbrains.compose.resources.stringResource

/**
 * Confirmation for `ForgetThisDevice` — the desktop/Android twin of iOS's `LockedView` escape hatch.
 *
 * ## ⚠⚠ Why the phrase is shown here rather than the action being blocked
 *
 * Per `admission-revocation-names-the-capability.md` §5b.12.2, a flow that destroys A1 material must
 * **route through the reveal, never refuse.** Blocking was the first draft and is wrong: a user with
 * a broken or sold device must still be able to forget it, and a refusal teaches nothing. Showing
 * the phrase *resolves* the risk instead of deferring it — the user can always continue, they simply
 * cannot do it unknowingly.
 *
 * ⚠ The copy names the **realm-wide** consequence, not the device-local one. Forgetting is safe for
 * the device doing it; the harm lands on the devices left behind, which is exactly the asymmetry
 * that cost this project a realm (§5b.11).
 *
 * [phrase] is `null` when this device holds none — the common case for a paired joiner, which never
 * receives one. Then there is nothing to save and the acknowledgement is not asked for.
 *
 * ## ⚠⚠ [working] is not a nicety — its absence read as a broken button
 *
 * Confirming is not fast. `ForgetThisDevice` waits for this device's removal to actually reach the
 * realm, and that wait is bounded by an **8-second** timeout which elapses *in full* whenever the
 * relay cannot confirm — a dead realm, no network, a 404. On top of it sit the relay round-trip, the
 * outbound-queue purge and the local store wipe. Ten seconds is ordinary, not pathological.
 *
 * Without a busy state the dialog simply closed and the app sat on Settings for those ten seconds
 * with nothing to say, and it was reported — repeatedly — as *"confirming does nothing"* and *"no
 * navigation at all"*. Two days of diagnosis went into a coroutine that was working correctly the
 * whole time. Keep this state, and keep it honest about the wait.
 */
@Composable
internal fun ForgetDeviceDialog(
    phrase: String?,
    acknowledged: Boolean,
    onAcknowledgedChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** True from the moment confirm is pressed until the op returns — see the KDoc above. */
    working: Boolean = false,
) {
    AlertDialog(
        // ⚠ No dismissal once the work has started: it is already irreversible, and letting the
        // dialog go would put the user back on a Settings screen for a realm that is being torn
        // down under it.
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(Res.string.forget_device_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(Res.string.forget_device_working))
                    }
                }
                Text(stringResource(Res.string.forget_device_body))

                if (phrase != null) {
                    Text(
                        text = stringResource(Res.string.forget_device_phrase_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Shown, not hidden behind a reveal: at this moment the phrase is about to stop
                    // existing on this device, so a second tap between the user and it is friction
                    // with no upside.
                    Text(
                        text = phrase,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = acknowledged,
                                onValueChange = onAcknowledgedChange,
                                role = Role.Checkbox,
                            ),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(checked = acknowledged, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.forget_device_acknowledge),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            // ⚠ Gated only when there is a phrase to lose. A joiner has none, so requiring an
            // acknowledgement there would be theatre — and theatre is what trains people to click
            // through the acknowledgements that do matter.
            TextButton(
                onClick = onConfirm,
                enabled = !working && (phrase == null || acknowledged),
            ) {
                Text(stringResource(Res.string.forget_device_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text(stringResource(Res.string.forget_device_cancel))
            }
        },
    )
}
