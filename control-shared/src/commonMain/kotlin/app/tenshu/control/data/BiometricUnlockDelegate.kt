package app.tenshu.control.data

import androidx.compose.runtime.Composable

/**
 * Compose-tree resolver for the platform [BiometricPromptHost]. Returns
 * `null` when the current host can't satisfy the prompt's requirements
 * (e.g. Android UI is hosted by something other than a
 * `FragmentActivity`, desktop without a windowed Compose surface).
 *
 * Screens call this and gate the biometric CTAs on a non-null result —
 * passphrase remains the fallback when the host is missing.
 *
 * This is the only Compose-coupled half of the biometric-unlock surface; it stays
 * in `:control-shared` while the [BiometricUnlockDelegate] contract + the
 * [BiometricPromptHost] type live in the Compose-free `:control-shared-logic`
 * (Control-on-iOS C2 split). The platform `actual`s build the logic-module
 * [BiometricPromptHost] via its public constructor.
 */
@Composable
expect fun rememberBiometricPromptHost(): BiometricPromptHost?
