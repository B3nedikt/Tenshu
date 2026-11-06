package app.tenshu.control.ui.screens.newuser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
import app.tenshu.control.shared.resources.new_user_continue
import app.tenshu.control.shared.resources.new_user_error_biometric_enroll_failed
import app.tenshu.control.shared.resources.new_user_error_biometric_finalize_failed
import app.tenshu.control.shared.resources.new_user_error_local_unlock_setup_failed
import app.tenshu.control.shared.resources.new_user_error_realm_bootstrap_failed
import app.tenshu.control.shared.resources.new_user_registering
import app.tenshu.control.shared.resources.new_user_turn_on_sync
import app.tenshu.control.shared.resources.new_user_youre_set_up
import app.tenshu.control.shared.resources.new_user_youre_set_up_body
import app.tenshu.control.ui.screens.unlock.DeviceProtectionStep
import app.tenshu.control.ui.screens.unlock.SetupUnlockStep
import app.tenshu.ui.components.marks.MarkOutline
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * New-user flow per `docs/proposals/onboarding-low-friction.md` §4. The
 * recovery-phrase ceremony is no longer in the first-run path — phrase
 * generation is silent, and capture is deferred to Settings (§7.4).
 *
 * Screens in order:
 *  - `SetupUnlock` (§4.1): passphrase entry. Phrase is minted lazily on
 *    submit; never shown.
 *  - `Bootstrapping`: invisible spinner while `createRealm` + the
 *    passphrase-wrapping run.
 *  - `YoureSetUp` (§4.2): "You're set up" terminal page with a single
 *    Continue CTA into Control's home surface.
 *
 * The single exit callback [onOpenDrive] lands on Home. The screen routes
 * via [exitTo] in the UI state; back-navigation out of the first-run flow is
 * handled by the nav host (hardware/gesture back).
 *
 * [onSignIn] is the optional detour off `YoureSetUp` into the email-OTP screen — see that step
 * for why sync is the one thing worth offering here and recovery/pairing are not.
 */
@Composable
fun NewUserScreen(
    onOpenDrive: () -> Unit,
    onSignIn: () -> Unit,
) {
    val viewModel = koinViewModel<NewUserViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val promptHost = rememberBiometricPromptHost()
    val promptCopy = BiometricPromptCopy(
        title = stringResource(Res.string.biometric_prompt_setup_title),
        subtitle = stringResource(Res.string.biometric_prompt_setup_subtitle),
        negativeButton = stringResource(Res.string.biometric_prompt_setup_negative),
    )

    LaunchedEffect(state.step, state.exitTo) {
        if (state.step != NewUserStep.Done) return@LaunchedEffect
        when (state.exitTo) {
            NewUserExit.OpenDrive -> onOpenDrive()
            null -> Unit
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.step) {
                NewUserStep.SetupUnlock -> if (!state.advancedUnlock) {
                    // Device-protection default (control-keep-device-unlocked.md §4.1):
                    // headless realm creation, no passphrase floor.
                    DeviceProtectionStep(
                        busy = state.unlockSetupBusy,
                        error = state.error?.label(),
                        onContinue = viewModel::onUseDeviceProtection,
                        onAddLock = viewModel::onShowAdvancedUnlock,
                    )
                } else {
                    // Expert opt-in — the old forced floor, now optional.
                    SetupUnlockStep(
                        passphrase = state.unlockPassphrase,
                        confirm = state.unlockPassphraseConfirm,
                        busy = state.unlockSetupBusy,
                        error = state.error?.label(),
                        onPassphraseChange = viewModel::onUnlockPassphraseChanged,
                        onConfirmChange = viewModel::onUnlockPassphraseConfirmChanged,
                        onSubmit = viewModel::onUnlockSetupSubmit,
                        onSkip = {},
                        allowSkip = false,
                        biometricAvailable = state.biometricAvailable && promptHost != null,
                        biometricBusy = state.biometricBusy,
                        onChooseBiometric = {
                            promptHost?.let { viewModel.onChooseBiometric(it, promptCopy) }
                        },
                        onChoosePassphrase = viewModel::onChoosePassphrase,
                        showPassphrase = state.passphraseExpanded,
                        biometricChosen = state.biometricChosen,
                        onBack = viewModel::onBack,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = viewModel::onExitAdvancedUnlock,
                        enabled = !state.unlockSetupBusy && !state.biometricBusy,
                    ) {
                        Text(stringResource(Res.string.device_protection_back))
                    }
                }
                NewUserStep.Bootstrapping -> BootstrappingStep()
                NewUserStep.YoureSetUp -> YoureSetUpStep(
                    onContinue = viewModel::onOpenDrive,
                    onSignIn = onSignIn,
                )
                NewUserStep.Done -> CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun NewUserError.label(): String = when (this) {
    is NewUserError.PassphraseTooShort ->
        stringResource(Res.string.error_passphrase_too_short, minLength)

    NewUserError.PassphrasesDoNotMatch ->
        stringResource(Res.string.error_passphrases_do_not_match)

    NewUserError.LocalUnlockSetupFailed ->
        stringResource(Res.string.new_user_error_local_unlock_setup_failed)

    NewUserError.BiometricFinalizeFailed ->
        stringResource(Res.string.new_user_error_biometric_finalize_failed)

    is NewUserError.BiometricEnrollFailed ->
        detail ?: stringResource(Res.string.new_user_error_biometric_enroll_failed)

    NewUserError.RealmBootstrapFailed ->
        stringResource(Res.string.new_user_error_realm_bootstrap_failed)
}

@Composable
private fun BootstrappingStep() {
    Text(
        text = stringResource(Res.string.new_user_registering),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(32.dp))
    CircularProgressIndicator()
}

/**
 * §4.2 "You're set up" — the terminal screen of the redesigned new-user
 * flow. The primary CTA exits to Control's home surface. The earlier
 * multi-device + recovery CTAs were dropped: at this point we don't know
 * whether Drive is installed, and both paths are already surfaced one tap
 * later on Home (the recovery affordance + the pairing notification).
 *
 * ⚠⚠ **[onSignIn] is the one that came back, and for a reason the dropped two did not have.**
 * A realm created here is held local-only by `StorageSyncGate` until an account is bound, and a
 * host cannot be rescued by a peer (`joinedFundedRealm` is false for the device that started the
 * realm) — so a user who taps straight through has a Tenshu that will never reach another device.
 * Recovery and pairing are *discoverable later*; this one is the difference between a realm that
 * syncs and one that silently cannot (`ios-services-status-and-purchase.md` §3.5). §4.2's own spec
 * listed a secondary account CTA here; the implementation had shrunk it away.
 *
 * ⚠ Secondary, never a gate: local-first (ADR 0019) means finishing without an account is a
 * legitimate choice, not a mistake to block.
 */
@Composable
private fun YoureSetUpStep(
    onContinue: () -> Unit,
    onSignIn: () -> Unit,
) {
    MarkOutline(size = 160.dp)
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(Res.string.new_user_youre_set_up),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(Res.string.new_user_youre_set_up_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(32.dp))
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.new_user_continue))
    }
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(
        onClick = onSignIn,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.new_user_turn_on_sync))
    }
}
