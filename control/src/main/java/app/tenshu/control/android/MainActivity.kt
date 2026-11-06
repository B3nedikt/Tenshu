package app.tenshu.control.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import app.tenshu.control.ui.App
import app.tenshu.control.ui.navigation.Destination
import app.tenshu.ui.theme.TenshuTheme

/**
 * Hosts the Control Compose UI. Extends [FragmentActivity] (not the
 * bare `ComponentActivity` it used before this change) because
 * `androidx.biometric.BiometricPrompt` requires a `FragmentActivity` to
 * host the prompt — `AndroidBiometricUnlockDelegate` resolves this
 * activity via `LocalContext.current as FragmentActivity` inside
 * `rememberBiometricPromptHost()`.
 *
 * `FragmentActivity` extends `ComponentActivity`, so `enableEdgeToEdge()`
 * + `setContent { ... }` keep working unchanged.
 *
 * **Deep link** (`docs/proposals/storage-capacity-gating.md` Phase 5): Drive's
 * "set up storage" CTA launches Control with the [EXTRA_DEEPLINK] extra so it
 * opens straight on the Services screen instead of Home. Cross-app contract is
 * a plain string extra on the existing launcher intent (no manifest filter
 * needed — MainActivity is already the exported LAUNCHER). [App] applies the
 * deep link only when the app is in its Active state. An already-running
 * instance is handled via [onNewIntent] → [recreate].
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deepLink = deepLinkFrom(intent)
        setContent {
            TenshuTheme {
                // The soft app-lock gate lives inside App() now (control-keep-device-unlocked.md
                // §4.2) — it wraps only the active app UI, so it never stacks over the
                // LockedScreen / onboarding.
                App(deepLink = deepLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-launch with the new intent so a deep link applied to an
        // already-running Control takes effect (recompose from onCreate).
        setIntent(intent)
        recreate()
    }

    private fun deepLinkFrom(intent: Intent?): Destination? =
        when (intent?.getStringExtra(EXTRA_DEEPLINK)) {
            DEEPLINK_SERVICES -> Destination.Services
            DEEPLINK_NOTIFICATIONS -> Destination.Notifications
            DEEPLINK_SYNC -> Destination.Sync
            else -> null
        }

    companion object {
        /** Cross-app deep-link contract — kept in sync with Drive's `AndroidStorageSetupLauncher`. */
        const val EXTRA_DEEPLINK = "app.tenshu.control.deeplink"
        const val DEEPLINK_SERVICES = "services"

        /** Internal deep link — a central-notifications OS alert opens the center (§4.3.4). */
        const val DEEPLINK_NOTIFICATIONS = "notifications"

        /** Internal deep link — the host-pairing FGS notification opens the Sync surface so
         *  the app-global approval modal (control-pairing-ux.md §3.5 / Phase 1.5) shows. */
        const val DEEPLINK_SYNC = "sync"
    }
}
