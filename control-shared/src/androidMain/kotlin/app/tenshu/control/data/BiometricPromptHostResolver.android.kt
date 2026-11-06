package app.tenshu.control.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity

/**
 * Android `actual` for the Compose-tree biometric host resolver. Builds the
 * `:control-shared-logic` [BiometricPromptHost] (public constructor) from the current
 * [FragmentActivity], or `null` when the UI isn't hosted by one (passphrase fallback).
 *
 * The contract type + the host live in the Compose-free `:control-shared-logic`; only
 * this @Composable resolver stays in `:control-shared` (Control-on-iOS C2 split).
 */
@Composable
actual fun rememberBiometricPromptHost(): BiometricPromptHost? {
    val context = LocalContext.current
    return remember(context) {
        (context as? FragmentActivity)?.let { BiometricPromptHost(it) }
    }
}
