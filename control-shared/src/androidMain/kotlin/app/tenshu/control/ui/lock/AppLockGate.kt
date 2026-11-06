package app.tenshu.control.ui.lock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.tenshu.control.data.AppLockAuthResult
import app.tenshu.control.data.AppLockAvailability
import app.tenshu.control.data.AppLockController
import app.tenshu.control.data.ReauthPromptCopy
import app.tenshu.control.data.Reauthenticator
import app.tenshu.control.data.rememberBiometricPromptHost
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.app_lock_subtitle
import app.tenshu.control.shared.resources.app_lock_title
import app.tenshu.control.shared.resources.app_lock_unlock
import app.tenshu.ui.components.marks.MarkOutline
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Control's **soft app-lock** UI gate
 * (`docs/proposals/control-keep-device-unlocked.md` §4.2, toggle 1).
 *
 * Wraps the whole app content. When [AppLockController.locked] is true it draws
 * an **opaque overlay** over the UI and presents a `BiometricPrompt`
 * (`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`, so it falls back to the device PIN on
 * any device with a secure lock screen). A success clears the gate until the app
 * next goes to background; the overlay stays put on cancel/error with a manual
 * *Unlock* retry. On a device with **no** secure lock screen the gate passes
 * through ([AppLockController.passIfUnenforceable]) — there is nothing to ask for.
 *
 * **This is UI only** — it never touches key material. The realm stays headless
 * and background sync keeps running whether or not the gate is showing (the
 * ProcessLifecycle observer only flips a boolean; see [AppLockController]).
 *
 * Lives in `:control-shared` androidMain (not `:control`) because it reads the
 * module-`internal` compose-resource `Res` strings; `:control`'s MainActivity
 * just wraps `App { … }` with it.
 */
@Composable
actual fun AppLockGate(active: Boolean, content: @Composable () -> Unit) {
    val controller: AppLockController = koinInject()
    val locked by controller.locked.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        // Only engage over the active app UI — never over the hard LockedScreen /
        // onboarding, so the two unlock screens never stack (see the expect KDoc).
        if (active && locked) {
            LockOverlay(controller = controller)
        }
    }
}

@Composable
private fun LockOverlay(controller: AppLockController) {
    // ⚠ The gate and Ph-reauth share ONE prompt implementation (`AndroidReauthenticator`) rather
    // than keeping two copies of the same BiometricPrompt setup — the second copy is where the two
    // would drift on authenticator flags or on which errors are terminal.
    val reauthenticator: Reauthenticator = koinInject()
    val availability: AppLockAvailability = koinInject()
    val host = rememberBiometricPromptHost()
    val copy = ReauthPromptCopy(
        title = stringResource(Res.string.app_lock_title),
        subtitle = stringResource(Res.string.app_lock_subtitle),
    )

    // Bumped to re-trigger the prompt after a cancel/error (the manual retry).
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        // ⚠⚠ A device with no screen lock cannot answer this prompt, ever — and "Require unlock"
        // defaults ON regardless (the Settings toggle being disabled does not turn it off). Without
        // this, a phone with no PIN, or one whose PIN was removed later, is locked out of Control.
        if (controller.passIfUnenforceable(availability)) return@LaunchedEffect
        val promptHost = host ?: return@LaunchedEffect
        // ⚠⚠ Otherwise Success ONLY — deliberately not `confirm(...)`. The prompt's own error codes
        // never clear the gate (lockout is `Failure`, and an overlay that clears itself on an error
        // is not a gate); only the availability probe above can, which answers "is there any
        // credential at all", not "did this attempt fail". So after an `Unavailable` we re-ask the
        // probe — the lock screen may have been removed while the overlay was up.
        when (reauthenticator.authenticate(promptHost, copy)) {
            AppLockAuthResult.Success -> controller.onAuthenticated()
            AppLockAuthResult.Unavailable -> controller.passIfUnenforceable(availability)
            AppLockAuthResult.Cancelled, AppLockAuthResult.Failure -> Unit
        }
    }

    // ⚠ The overlay outlives a trip to the background — `onEnterBackground` re-arms a gate that is
    // already armed, so nothing above re-runs on the way back. A screen lock removed meanwhile would
    // then still need a tap on *Unlock* to be noticed; re-probing on every resume makes it pass at
    // once. Probe only — it never re-prompts, so a cancelled sheet does not reopen by itself.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            controller.passIfUnenforceable(availability)
        }
    }

    // Opaque cover so the underlying UI never shows through the gate.
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MarkOutline(size = 120.dp)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = copy.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { attempt++ }) {
                    Text(stringResource(Res.string.app_lock_unlock))
                }
            }
        }
    }
}
