package app.tenshu.control.ui.screens.unlock

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.setup_unlock_back
import app.tenshu.control.shared.resources.setup_unlock_biometric_waiting
import app.tenshu.control.shared.resources.setup_unlock_choice_biometric
import app.tenshu.control.shared.resources.setup_unlock_choice_body
import app.tenshu.control.shared.resources.setup_unlock_choice_passphrase
import app.tenshu.control.shared.resources.setup_unlock_confirm_label
import app.tenshu.control.shared.resources.setup_unlock_passphrase_body
import app.tenshu.control.shared.resources.setup_unlock_passphrase_label
import app.tenshu.control.shared.resources.setup_unlock_recovery_body
import app.tenshu.control.shared.resources.setup_unlock_set_passphrase
import app.tenshu.control.shared.resources.setup_unlock_setting_up
import app.tenshu.control.shared.resources.setup_unlock_skip
import app.tenshu.control.shared.resources.setup_unlock_title
import org.jetbrains.compose.resources.stringResource

/**
 * Shared "Lock Tenshu when you're not using it" step — used by the new-user flow
 * ([NewUserScreen]), post-pairing ([SetupUnlockScreen]), and post-recovery.
 *
 * **Passphrase floor** (`docs/proposals/desktop-masterkeystore.md`): biometric is
 * the frictionless everyday unlock, but a recovery passphrase is *required*
 * underneath it — the way back in if the biometric credential is ever lost. So
 * when biometric is available the step opens on a **choice** of two
 * equally-prominent options (biometric / passphrase); choosing biometric enrolls
 * it, then routes to passphrase entry with recovery-framing copy. Without
 * biometric (desktop w/o the helper, Android w/o hardware) it opens directly on
 * passphrase entry. Pure UI; the calling screen owns state + the proxy call.
 */
@Composable
fun SetupUnlockStep(
    passphrase: String,
    confirm: String,
    busy: Boolean,
    error: String?,
    onPassphraseChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    allowSkip: Boolean = true,
    biometricAvailable: Boolean = false,
    biometricBusy: Boolean = false,
    onChooseBiometric: () -> Unit = {},
    onChoosePassphrase: () -> Unit = {},
    /** True once a credential choice has been made and we're on passphrase entry. */
    showPassphrase: Boolean = false,
    /** True when biometric was chosen → passphrase entry shows recovery-floor framing. */
    biometricChosen: Boolean = false,
    onBack: () -> Unit = {},
) {
    Text(
        text = stringResource(Res.string.setup_unlock_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))

    val onChoiceScreen = biometricAvailable && !showPassphrase
    val anyBusy = busy || biometricBusy

    if (onChoiceScreen) {
        ChoiceBody(
            biometricBusy = biometricBusy,
            allowSkip = allowSkip,
            onChooseBiometric = onChooseBiometric,
            onChoosePassphrase = onChoosePassphrase,
            onSkip = onSkip,
        )
    } else {
        PassphraseBody(
            passphrase = passphrase,
            confirm = confirm,
            busy = anyBusy,
            allowSkip = allowSkip,
            recoveryFraming = biometricChosen,
            canGoBack = biometricAvailable,
            onPassphraseChange = onPassphraseChange,
            onConfirmChange = onConfirmChange,
            onSubmit = onSubmit,
            onSkip = onSkip,
            onBack = onBack,
        )
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

@Composable
private fun ChoiceBody(
    biometricBusy: Boolean,
    allowSkip: Boolean,
    onChooseBiometric: () -> Unit,
    onChoosePassphrase: () -> Unit,
    onSkip: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.setup_unlock_choice_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))
    // Two equally-prominent options (filled + outlined of equal width).
    Button(
        onClick = onChooseBiometric,
        enabled = !biometricBusy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (biometricBusy) stringResource(Res.string.setup_unlock_biometric_waiting)
            else stringResource(Res.string.setup_unlock_choice_biometric)
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = onChoosePassphrase,
        enabled = !biometricBusy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.setup_unlock_choice_passphrase))
    }
    if (allowSkip) {
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onSkip, enabled = !biometricBusy) {
            Text(stringResource(Res.string.setup_unlock_skip))
        }
    }
}

@Composable
private fun PassphraseBody(
    passphrase: String,
    confirm: String,
    busy: Boolean,
    allowSkip: Boolean,
    recoveryFraming: Boolean,
    canGoBack: Boolean,
    onPassphraseChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        text = stringResource(
            if (recoveryFraming) Res.string.setup_unlock_recovery_body
            else Res.string.setup_unlock_passphrase_body
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))
    val confirmFocusRequester = remember { FocusRequester() }
    val canSubmit = !busy && passphrase.isNotEmpty() && confirm.isNotEmpty()
    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphraseChange,
        label = { Text(stringResource(Res.string.setup_unlock_passphrase_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { confirmFocusRequester.requestFocus() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = confirm,
        onValueChange = onConfirmChange,
        label = { Text(stringResource(Res.string.setup_unlock_confirm_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(confirmFocusRequester),
    )
    Spacer(modifier = Modifier.height(20.dp))
    Button(
        onClick = onSubmit,
        enabled = canSubmit,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (busy) stringResource(Res.string.setup_unlock_setting_up)
            else stringResource(Res.string.setup_unlock_set_passphrase)
        )
    }
    // Back to the credential choice (only when a choice screen exists).
    if (canGoBack) {
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onBack, enabled = !busy) {
            Text(stringResource(Res.string.setup_unlock_back))
        }
    }
    if (allowSkip) {
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onSkip, enabled = !busy) {
            Text(stringResource(Res.string.setup_unlock_skip))
        }
    }
}
