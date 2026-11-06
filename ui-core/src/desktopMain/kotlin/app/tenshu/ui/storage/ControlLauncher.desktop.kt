package app.tenshu.ui.storage

import androidx.compose.runtime.Composable

/**
 * Desktop has no launch path to Control in v1 — a separate JVM process with no launch IPC — so
 * there is no route to offer and this is always `null`.
 *
 * ⚠ `null` rather than a no-op lambda, deliberately. Drive's `DesktopStorageSetupLauncher` returns
 * a launcher that silently does nothing, which renders a *button that does nothing*: the user
 * presses it, is not taken anywhere, and concludes the feature is broken. The nullable contract
 * exists so [RealmStorageBanner] can omit the button entirely and let its text carry the whole
 * instruction — which is what Drive's own `DeviceUnrecognized` copy already has to do for exactly
 * this reason.
 */
@Composable
actual fun rememberControlServicesLauncher(): (() -> Unit)? = null
