package app.tenshu.control.ui.screens.unlock

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.device_protection_add_lock
import app.tenshu.control.shared.resources.device_protection_body
import app.tenshu.control.shared.resources.device_protection_continue
import app.tenshu.control.shared.resources.device_protection_title
import app.tenshu.ui.components.marks.MarkOutline
import org.jetbrains.compose.resources.stringResource

/**
 * Device-protection default (`docs/proposals/control-keep-device-unlocked.md`
 * §4.1) — the primary onboarding exit on every path that used to force a
 * passphrase floor: new-user genesis ([app.tenshu.control.ui.screens.newuser.NewUserScreen]),
 * post-pairing and post-recovery ([SetupUnlockScreen]).
 *
 * The payload (`RECOVERY_PHRASE` for a host, `JOINER_PAYLOAD` for a joiner)
 * stays keystore-sealed and *unwrapped*, so `SyncManagerImpl.init` autoloads it
 * on every cold start and background sync survives a reboot with no app-level
 * unlock. The passphrase/biometric lock is a de-emphasised expert opt-in
 * ([onAddLock]) — and the everyday privacy curtain is the soft "Require unlock"
 * app-lock, which is on by default and never touches keys (§4.2).
 *
 * Pure UI; the calling screen owns the state and the proxy call.
 */
@Composable
fun DeviceProtectionStep(
    busy: Boolean,
    error: String?,
    onContinue: () -> Unit,
    onAddLock: () -> Unit,
) {
    MarkOutline(size = 120.dp)
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(Res.string.device_protection_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(Res.string.device_protection_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(
        onClick = onContinue,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.device_protection_continue))
    }
    Spacer(modifier = Modifier.height(4.dp))
    TextButton(onClick = onAddLock, enabled = !busy) {
        Text(stringResource(Res.string.device_protection_add_lock))
    }
    error?.let {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
