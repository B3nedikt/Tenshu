package app.tenshu.ui.storage

import androidx.compose.runtime.Composable

/**
 * A callback that opens **Control** on its Services screen, or `null` when this device has no route
 * to Control at all.
 *
 * ⚠⚠ **`null` is a first-class answer, not a failure.** Control may not be installed — route-1 apps
 * hold their own proxy and work perfectly without it — and desktop has no launch path in v1. A
 * button that does nothing is worse than no button, so [RealmStorageBanner] takes the action as
 * nullable and states the situation in text either way.
 *
 * ⚠ Each app needs its own manifest-side permission to *see* Control: a `<queries>` entry on
 * Android, an `LSApplicationQueriesSchemes` entry on iOS. Without it the platform answers "not
 * installed" even when it is, and this returns `null` — the same failure the banner degrades to
 * gracefully, which is exactly why it must degrade rather than throw.
 */
@Composable
expect fun rememberControlServicesLauncher(): (() -> Unit)?

/**
 * The deep-link destination, spelled once.
 *
 * ⚠ Android passes `services` as a string extra to Control's launcher activity; iOS opens
 * `tenshu-control://services`. Both name the same screen, and both are kept in sync with Control's
 * own `MainActivity.DEEPLINK_SERVICES` / `CFBundleURLSchemes` entry.
 */
internal const val CONTROL_SERVICES_DEEPLINK = "services"
