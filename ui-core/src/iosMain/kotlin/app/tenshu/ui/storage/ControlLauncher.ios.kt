package app.tenshu.ui.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Open Control on its **Services** screen from another first-party iOS app — the route
 * [RealmStorageBanner]'s action needs, and the one platform where it did not exist at all.
 *
 * ## ⚠⚠ iOS can do this; the standing summary is too strong
 *
 * `control-cross-app-open.md` rules out launching a sibling app **by bundle id**, and that remains
 * true — iOS has no equivalent of Android's `getLaunchIntentForPackage`. But Control-iOS registers
 * the URL scheme **`tenshu-control`** (`control-ios/project.yml` → `CFBundleURLSchemes`), and a
 * registered scheme is a different mechanism with a different answer. Taking "iOS cannot open a
 * sibling app" at face value would have left this banner action-free on the one platform where the
 * dead end it reports is worst — `ios-services-status-and-purchase.md` §3.5's iOS-only user has no
 * in-app path out at all.
 *
 * ⚠ `canOpenURL` requires `tenshu-control` in **this** app's `LSApplicationQueriesSchemes`; without
 * it iOS answers `false` even when Control is installed, and the banner correctly degrades to text.
 * That entry belongs in each app's **`project.yml`** — `Info.plist` is generated and gitignored.
 */
@Composable
actual fun rememberControlServicesLauncher(): (() -> Unit)? {
    val url = remember { NSURL.URLWithString("tenshu-control://$CONTROL_SERVICES_DEEPLINK") }
    val reachable = remember(url) {
        url != null && UIApplication.sharedApplication.canOpenURL(url)
    }
    if (!reachable || url == null) return null
    return remember(url) { { UIApplication.sharedApplication.openURL(url) } }
}
