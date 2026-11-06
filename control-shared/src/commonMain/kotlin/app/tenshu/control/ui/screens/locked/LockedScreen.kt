package app.tenshu.control.ui.screens.locked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.rememberBiometricPromptHost
import app.tenshu.control.ui.LocalWindowVisible
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.biometric_prompt_unlock_negative
import app.tenshu.control.shared.resources.biometric_prompt_unlock_subtitle
import app.tenshu.control.shared.resources.biometric_prompt_unlock_title
import app.tenshu.control.shared.resources.locked_biometric_body
import app.tenshu.control.shared.resources.locked_biometric_cta
import app.tenshu.control.shared.resources.locked_biometric_waiting
import app.tenshu.control.shared.resources.locked_error_biometric_enrollment_changed
import app.tenshu.control.shared.resources.locked_error_recovery_phrase_unreadable
import app.tenshu.control.shared.resources.locked_error_biometric_unlock_failed
import app.tenshu.control.shared.resources.locked_error_passphrase_invalid
import app.tenshu.control.shared.resources.locked_lost_access
import app.tenshu.control.shared.resources.locked_passphrase_body
import app.tenshu.control.shared.resources.locked_passphrase_label
import app.tenshu.control.shared.resources.locked_too_many_tries
import app.tenshu.control.shared.resources.locked_unlock
import app.tenshu.control.shared.resources.locked_unlocking
import app.tenshu.control.shared.resources.locked_use_biometric_instead
import app.tenshu.control.shared.resources.locked_use_passphrase_instead
import app.tenshu.control.shared.resources.locked_welcome_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * §3 Welcome Back unlock screen per
 * `docs/proposals/control-onboarding-ux.md`. On Android with biometric
 * available + enrolled, the prompt auto-triggers on first composition;
 * passphrase entry stays behind the "Use passphrase instead" link as
 * the fallback. On desktop, the biometric delegate is inert
 * (`isAvailable=false`) and this collapses to the passphrase-only
 * layout v1 shipped.
 */
@Composable
fun LockedScreen(
    onUnlocked: () -> Unit,
    onRecover: () -> Unit,
) {
    val viewModel = koinViewModel<LockedViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Null when the host can't satisfy `BiometricPrompt` (e.g. desktop,
    // or an Android UI not rooted in a FragmentActivity). The biometric
    // branch is gated on a non-null host throughout.
    val activeHost =
        rememberBiometricPromptHost().takeIf {
            state.biometricAvailable && state.biometricEnrolled
        }
    val promptCopy = BiometricPromptCopy(
        title = stringResource(Res.string.biometric_prompt_unlock_title),
        subtitle = stringResource(Res.string.biometric_prompt_unlock_subtitle),
        negativeButton = stringResource(Res.string.biometric_prompt_unlock_negative),
    )

    LaunchedEffect(state.unlocked) {
        if (state.unlocked) onUnlocked()
    }

    // Auto-trigger biometric on first composition when the delegate is
    // ready. Gate on `passphraseExpanded == false` so we never re-prompt
    // after the user explicitly switched to passphrase.
    //
    // Also gate on the window actually being visible: on desktop the
    // autostarted-hidden Control daemon keeps this screen composed behind a
    // hidden window (Main.kt never drops the window), and an ungated effect
    // would fire the OS biometric sheet over a blank screen — then again once
    // the user opened the window, the double prompt this guards against. When
    // the window flips visible the effect re-runs and prompts at the right
    // moment. On Android `LocalWindowVisible` defaults to true (the UI only
    // composes in the foreground), so behaviour there is unchanged.
    val windowVisible = LocalWindowVisible.current
    LaunchedEffect(activeHost, windowVisible) {
        if (windowVisible && activeHost != null && !state.passphraseExpanded && !state.biometricInFlight) {
            viewModel.onUnlockBiometric(activeHost, promptCopy)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state.initializing) {
            // Hold a neutral spinner until the biometric probe resolves so the
            // passphrase fallback doesn't flash before biometric is known.
            CircularProgressIndicator()
            return@Box
        }
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(Res.string.locked_welcome_back),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (activeHost != null && !state.passphraseExpanded) {
                BiometricPrimary(
                    busy = state.biometricInFlight,
                    onUnlockBiometric = { viewModel.onUnlockBiometric(activeHost, promptCopy) },
                    onUsePassphrase = viewModel::onUsePassphrase,
                )
            } else {
                PassphraseEntry(
                    passphrase = state.passphrase,
                    isLoading = state.isLoading,
                    error = state.error?.label(),
                    onPassphraseChanged = viewModel::onPassphraseChanged,
                    onUnlock = viewModel::onUnlock,
                    onRecover = onRecover,
                )
                // The way back to biometric. `passphraseExpanded` is otherwise a one-way latch:
                // a *cancelled* prompt sets it with no error copy, and backgrounding the app while
                // the sheet is up counts as a cancel — so an accidental dismissal stranded the
                // whole session on the passphrase field with the fingerprint reader silently
                // unavailable, recoverable only by killing Control. Shown only when the device can
                // actually satisfy a prompt (`activeHost != null` already encodes
                // available + enrolled), so it never offers a dead affordance — in particular it
                // stays hidden after `KeyInvalidated`/`NotAvailable`, which clear `biometricEnrolled`.
                if (activeHost != null && !state.isLoading) {
                    TextButton(onClick = viewModel::onUseBiometric) {
                        Text(stringResource(Res.string.locked_use_biometric_instead))
                    }
                }
            }

            if (state.failureCount >= MAX_PASSPHRASE_ATTEMPTS) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.locked_too_many_tries),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BiometricPrimary(
    busy: Boolean,
    onUnlockBiometric: () -> Unit,
    onUsePassphrase: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.locked_biometric_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onUnlockBiometric,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (busy) stringResource(Res.string.locked_biometric_waiting)
            else stringResource(Res.string.locked_biometric_cta)
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = onUsePassphrase, enabled = !busy) {
        Text(stringResource(Res.string.locked_use_passphrase_instead))
    }
}

@Composable
private fun PassphraseEntry(
    passphrase: String,
    isLoading: Boolean,
    error: String?,
    onPassphraseChanged: (String) -> Unit,
    onUnlock: () -> Unit,
    onRecover: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.locked_passphrase_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(24.dp))
    val canUnlock = passphrase.isNotEmpty() && !isLoading
    OutlinedTextField(
        value = passphrase,
        onValueChange = onPassphraseChanged,
        label = { Text(stringResource(Res.string.locked_passphrase_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (canUnlock) onUnlock() }),
        modifier = Modifier.fillMaxWidth(),
        isError = error != null,
    )
    Spacer(modifier = Modifier.height(20.dp))
    Button(
        onClick = onUnlock,
        enabled = canUnlock,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (isLoading) stringResource(Res.string.locked_unlocking)
            else stringResource(Res.string.locked_unlock)
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    TextButton(onClick = onRecover) {
        Text(stringResource(Res.string.locked_lost_access))
    }
    error?.let {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

private const val MAX_PASSPHRASE_ATTEMPTS = 5

@Composable
private fun LockedError.label(): String = when (this) {
    LockedError.PassphraseInvalid ->
        stringResource(Res.string.locked_error_passphrase_invalid)

    LockedError.BiometricEnrollmentChanged ->
        stringResource(Res.string.locked_error_biometric_enrollment_changed)

    is LockedError.BiometricUnlockFailed ->
        detail ?: stringResource(Res.string.locked_error_biometric_unlock_failed)

    LockedError.RecoveryPhraseUnreadable ->
        stringResource(Res.string.locked_error_recovery_phrase_unreadable)
}
