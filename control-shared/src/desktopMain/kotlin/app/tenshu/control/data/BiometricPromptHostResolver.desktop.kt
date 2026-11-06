package app.tenshu.control.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop `actual` for the Compose-tree biometric host resolver. Returns a non-null
 * inert host so the UI's `biometricAvailable && promptHost != null` gate can pass;
 * `DesktopBiometricUnlockDelegate.isAvailable()` (helper-backed) is the real switch,
 * and the macOS auth sheet is presented by the helper process (no window context).
 *
 * The host type lives in the Compose-free `:control-shared-logic`; only this
 * @Composable resolver stays in `:control-shared` (Control-on-iOS C2 split).
 */
@Composable
actual fun rememberBiometricPromptHost(): BiometricPromptHost? =
    remember { BiometricPromptHost() }
