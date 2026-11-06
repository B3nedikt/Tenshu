package app.tenshu.control.ui.screens.unlock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.data.BiometricPromptCopy
import app.tenshu.control.data.rememberBiometricPromptHost
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.biometric_prompt_setup_negative
import app.tenshu.control.shared.resources.biometric_prompt_setup_subtitle
import app.tenshu.control.shared.resources.biometric_prompt_setup_title
import app.tenshu.control.shared.resources.device_protection_back
import app.tenshu.control.shared.resources.error_passphrase_too_short
import app.tenshu.control.shared.resources.error_passphrases_do_not_match
import app.tenshu.control.shared.resources.setup_unlock_error_biometric_enroll_failed
import app.tenshu.control.shared.resources.setup_unlock_error_biometric_setup_failed
import app.tenshu.control.shared.resources.setup_unlock_error_local_unlock_setup_failed
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Standalone screen the pairing-as-requester (§4a.6) and recovery
 * (§7.3-tail) flows route through on completion.
 *
 * Leads with the headless [DeviceProtectionStep] per
 * `docs/proposals/control-keep-device-unlocked.md` §4.1 — same shape as
 * `NewUserScreen`, which got it in Ph1.1. The [SetupUnlockStep] passphrase
 * floor is the expert opt-in behind it, and calls `setUpUnlock` on submit.
 * Either exit signals the navigation host via [onComplete] so the app routes
 * to Home.
 *
 * `NewUserScreen`'s §4b.5 step uses both composables too, but keeps its setup
 * state inside `NewUserViewModel` (it's part of the multi-step new-user state
 * machine).
 */
@Composable
fun SetupUnlockScreen(
    onComplete: () -> Unit,
) {
    val viewModel = koinViewModel<SetupUnlockViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val promptHost = rememberBiometricPromptHost()
    val promptCopy = BiometricPromptCopy(
        title = stringResource(Res.string.biometric_prompt_setup_title),
        subtitle = stringResource(Res.string.biometric_prompt_setup_subtitle),
        negativeButton = stringResource(Res.string.biometric_prompt_setup_negative),
    )

    LaunchedEffect(state.done) {
        if (state.done) onComplete()
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.advancedUnlock) {
                // Device-protection default (control-keep-device-unlocked.md §4.1):
                // the joiner payload is already keystore-sealed, so completing here
                // leaves the realm autoloading headlessly.
                DeviceProtectionStep(
                    busy = state.busy,
                    error = state.error?.label(),
                    onContinue = viewModel::onUseDeviceProtection,
                    onAddLock = viewModel::onShowAdvancedUnlock,
                )
            } else {
                // Expert opt-in — the old forced floor, now optional.
                SetupUnlockStep(
                    passphrase = state.passphrase,
                    confirm = state.confirm,
                    busy = state.busy,
                    error = state.error?.label(),
                    onPassphraseChange = viewModel::onPassphraseChanged,
                    onConfirmChange = viewModel::onConfirmChanged,
                    onSubmit = viewModel::onSubmit,
                    onSkip = {},
                    allowSkip = false,
                    // Only surface biometric when the platform-side delegate AND the
                    // Compose host both agree (a FragmentActivity-less Android host
                    // still falls back to passphrase-only).
                    biometricAvailable = state.biometricAvailable && promptHost != null,
                    biometricBusy = state.biometricBusy,
                    onChooseBiometric = {
                        promptHost?.let { viewModel.onChooseBiometric(it, promptCopy) }
                    },
                    onChoosePassphrase = viewModel::onChoosePassphrase,
                    showPassphrase = state.showPassphrase,
                    biometricChosen = state.biometricChosen,
                    onBack = viewModel::onBack,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = viewModel::onExitAdvancedUnlock,
                    enabled = !state.busy && !state.biometricBusy,
                ) {
                    Text(stringResource(Res.string.device_protection_back))
                }
            }
        }
    }
}

@Composable
private fun SetupUnlockError.label(): String = when (this) {
    is SetupUnlockError.PassphraseTooShort ->
        stringResource(Res.string.error_passphrase_too_short, minLength)

    SetupUnlockError.PassphrasesDoNotMatch ->
        stringResource(Res.string.error_passphrases_do_not_match)

    SetupUnlockError.LocalUnlockSetupFailed ->
        stringResource(Res.string.setup_unlock_error_local_unlock_setup_failed)

    SetupUnlockError.BiometricSetupFailed ->
        stringResource(Res.string.setup_unlock_error_biometric_setup_failed)

    is SetupUnlockError.BiometricEnrollFailed ->
        detail ?: stringResource(Res.string.setup_unlock_error_biometric_enroll_failed)
}
