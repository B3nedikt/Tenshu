package app.tenshu.control.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

@Serializable
sealed class Destination {
    abstract val label: String
    abstract val icon: ImageVector

    @Serializable
    data object Home : Destination() {
        override val label = "Home"
        override val icon = Icons.Default.Home
    }

    /** Central notifications — inbound actionable requests (control-central-notifications.md).
     *  Reached from the bell in Home's top bar, not a top-level nav item. */
    @Serializable
    data object Notifications : Destination() {
        override val label = "Notifications"
        override val icon = Icons.Default.Notifications
    }

    @Serializable
    data object Apps : Destination() {
        override val label = "Apps"
        override val icon = Icons.Default.Apps
    }

    @Serializable
    data object Data : Destination() {
        override val label = "Data"
        override val icon = Icons.Default.Storage
    }

    /** Per-app detail (permissions, install info, uninstall). Carries the [appId]
     *  argument, so it is a `data class` rather than a `data object`. `label` / `icon`
     *  are getter-form (no backing field) so kotlinx-serialization serializes only
     *  [appId] and doesn't trip over the non-serializable `ImageVector`. */
    @Serializable
    data class AppDetail(val appId: String) : Destination() {
        override val label: String get() = "App"
        override val icon: ImageVector get() = Icons.Default.Apps
    }

    /** EPC app-pairing — the proxy-side QR + SAS ceremony reached from Apps →
     *  "Connect on another device" (control-ia.md §4.3; ios-proxy-architecture.md §10.6).
     *  This Control acts as the proxy and shows the QR; the app scans it to pair. */
    @Serializable
    data object EpcPairing : Destination() {
        override val label = "Connect an app"
        override val icon = Icons.Default.Apps
    }

    @Serializable
    data object Services : Destination() {
        override val label = "Services"
        override val icon = Icons.Default.Cloud
    }

    /**
     * Data → Shares (control-ia.md §5.3): realm-wide outbound-share inventory +
     * revoke. Reached from the Data screen; per-file *creation* of links stays
     * in Drive.
     */
    @Serializable
    data object Shares : Destination() {
        override val label = "Shares"
        override val icon = Icons.Default.Share
    }

    @Serializable
    data object Settings : Destination() {
        override val label = "Settings"
        override val icon = Icons.Default.Settings
    }

    @Serializable
    data object Sync : Destination() {
        override val label = "Sync"
        override val icon = Icons.Default.Sync
    }

    @Serializable
    data object BackgroundSync : Destination() {
        override val label = "Background sync"
        override val icon = Icons.Default.Sync
    }

    @Serializable
    data object BackgroundIngest : Destination() {
        override val label = "Background ingest"
        override val icon = Icons.Default.Cloud
    }

    /**
     * Settings → Errors (Phase 5 of `docs/proposals/core-error-channel.md`):
     * visibility-only view of the realm-scoped diagnostic log captured by
     * `:logging-service-proxy`. Phase 6 adds the "Send error report" CTA.
     */
    @Serializable
    data object Errors : Destination() {
        override val label = "Errors"
        override val icon = Icons.Default.ErrorOutline
    }

    @Serializable
    data object SettingsUnlock : Destination() {
        override val label = "Unlock"
        override val icon = Icons.Default.Lock
    }

    /**
     * Settings → Offline maps (`maps-region-delivery.md` §7 phase 6 / D5-amended): the canonical
     * management surface for downloaded basemap regions (shared by Maps/Timeline/Fit, so it lives in
     * Control, the store owner). A data-availability surface, distinct from Services.
     */
    @Serializable
    data object OfflineMaps : Destination() {
        override val label = "Offline maps"
        override val icon = Icons.Default.Map
    }

    /**
     * Settings → Models (`inference-service.md` IN-2c / IN-D10): the canonical management surface
     * for on-device inference models (shared by Photos/Mia, so it lives in Control, the store
     * owner). ⚠ The only route by which a model reaches a device.
     */
    @Serializable
    data object Models : Destination() {
        override val label = "Models"
        override val icon = Icons.Default.Memory
    }

    /**
     * Settings → Models → one feature (`inference-service.md` §38): which devices do its work.
     * Carries an argument, so a `data class`; ⚠ the id rather than the enum, so the route stays a
     * plain string. Never a nav-bar root.
     */
    @Serializable
    data class ModelFeature(val capabilityId: String) : Destination() {
        override val label: String get() = "Feature"
        override val icon: ImageVector get() = Icons.Default.Memory
    }

    /** Settings → Advanced — expert settings hub (control-keep-device-unlocked.md §4.4). */
    @Serializable
    data object SettingsAdvanced : Destination() {
        override val label = "Advanced"
        override val icon = Icons.Default.Settings
    }

    /** Settings → Open-source licences (`docs/oss-licences.md`) — `:ui-core`'s shared screen. */
    @Serializable
    data object SettingsLicences : Destination() {
        override val label = "Open-source licences"
        override val icon = Icons.Default.Gavel
    }

    /** Settings → Advanced → Security — the soft app-lock toggle + hard-mode placeholder. */
    @Serializable
    data object SettingsSecurity : Destination() {
        override val label = "Security"
        override val icon = Icons.Default.Lock
    }

    /**
     * Settings → Advanced → Security → "Encrypt on unlock only" enable flow — reuses
     * the SetupUnlock passphrase-floor screen, but pops back to Security on complete
     * (control-keep-device-unlocked.md §4.2, Ph-hard) rather than routing to Home.
     */
    @Serializable
    data object SettingsEncryptSetup : Destination() {
        override val label = "Encrypt on unlock"
        override val icon = Icons.Default.Lock
    }

    @Serializable
    data object RecoveryPhrase : Destination() {
        override val label = "Recovery Phrase"
        override val icon = Icons.Default.Lock
    }

    @Serializable
    data object PairingWizard : Destination() {
        override val label = "Add Device"
        override val icon = Icons.Default.Sync
    }

    @Serializable
    data object Welcome : Destination() {
        override val label = "Welcome"
        override val icon = Icons.Default.Home
    }

    @Serializable
    data object NewUser : Destination() {
        override val label = "New User"
        override val icon = Icons.Default.Home
    }

    @Serializable
    data object Recovery : Destination() {
        override val label = "Recovery"
        override val icon = Icons.Default.Lock
    }

    @Serializable
    data object Locked : Destination() {
        override val label = "Locked"
        override val icon = Icons.Default.Lock
    }

    @Serializable
    data object SetupUnlock : Destination() {
        override val label = "Set up unlock"
        override val icon = Icons.Default.Lock
    }

    @Serializable
    data object AccountPicker : Destination() {
        override val label = "Pick a plan"
        override val icon = Icons.Default.Cloud
    }

    /** The email-OTP account flow. [signIn] = false is *sign-up* (create the free account);
     *  [signIn] = true is *sign-in* (re-authenticate an existing account on this device —
     *  commerce-accounts-and-sessions.md §3.5 / D11). Carries an argument, so a `data class`;
     *  `label`/`icon` are getter-form so kotlinx-serialization serializes only the arguments.
     *
     *  [thenCheckout] is the paid tier the user was on the way to buying: a purchase is made
     *  signed in, so the email step comes first and carries on to that tier's checkout. */
    @Serializable
    data class FreeAccountSignup(
        val signIn: Boolean = false,
        val thenCheckout: String? = null,
    ) : Destination() {
        override val label: String get() = if (signIn) "Sign in" else "Sign up"
        override val icon: ImageVector get() = Icons.Default.Cloud
    }

    /** Paid-tier checkout for [productId] (Stripe hosted). Carries an argument, so a `data class`;
     *  `label`/`icon` are getter-form so kotlinx-serialization serializes only [productId]. */
    @Serializable
    data class Checkout(val productId: String) : Destination() {
        override val label: String get() = "Checkout"
        override val icon: ImageVector get() = Icons.Default.Cloud
    }

    @Serializable
    data object ReiKey : Destination() {
        override val label = "Have a code?"
        override val icon = Icons.Default.Cloud
    }

    @Serializable
    data object AccountDeletion : Destination() {
        override val label = "Delete account"
        override val icon = Icons.Default.Cloud
    }

    @Serializable
    data object DisconnectConfirm : Destination() {
        override val label = "Disconnect"
        override val icon = Icons.Default.Cloud
    }
}
