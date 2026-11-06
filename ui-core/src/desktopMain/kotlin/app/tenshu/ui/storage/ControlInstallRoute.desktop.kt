package app.tenshu.ui.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File

/**
 * Desktop's install route — the answer the ADR originally punted on, and it turns out macOS records
 * exactly the fact needed.
 *
 * ⚠⚠ **A Mac App Store copy carries a receipt; a DMG or Homebrew copy does not.** So a link is
 * offered only to a MAS build, and every other desktop copy gets
 * [ControlInstallRoute.SameChannelAsThisApp] — which stays correct after Control ships to the MAS,
 * because a Homebrew user sent to the App Store ends up with two copies from two channels and no
 * idea which one updates. That is the whole reason this is not just a URL.
 *
 * ⚠ Windows and Linux answer [ControlInstallRoute.SameChannelAsThisApp] outright: an installer,
 * `apt`, a package manager or a zip, and none of them is a store we can link to.
 *
 * ⚠ A dev run (no bundle at all) lands there too, which is honest — there is no store copy of
 * anything in a Gradle tree.
 */
@Composable
actual fun rememberControlInstallRoute(): ControlInstallRoute = remember {
    val configured = ControlStoreConfig.MAC_APP_STORE_URL
    when {
        !isMacOs() -> ControlInstallRoute.SameChannelAsThisApp
        !installedFromMacAppStore() -> ControlInstallRoute.SameChannelAsThisApp
        configured.isBlank() -> ControlInstallRoute.NotPublishedYet
        else -> ControlInstallRoute.Store(configured)
    }
}

private fun isMacOs(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

/**
 * `Contents/_MASReceipt/receipt` — the App Store's own marker, written into the bundle at purchase
 * and absent from every other distribution of the same app.
 *
 * ⚠⚠ **Presence only, never validation.** A real receipt check is a cryptographic verification
 * against the device, and this is not that — nor should it be. The question here is "which sentence
 * do I show", not "is this user entitled to anything", and treating a forgeable file as an
 * entitlement is how receipt checks become security theatre. The worst case for a spoofed receipt
 * is a button to a public store page.
 *
 * ⚠ `jpackage.app-path` is the launcher inside `Contents/MacOS/`; two levels up is `Contents`. It
 * is absent in a dev run, which is the `null` branch.
 */
private fun installedFromMacAppStore(): Boolean {
    val launcher = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() } ?: return false
    val contents = File(launcher).parentFile?.parentFile ?: return false
    return File(contents, "_MASReceipt/receipt").isFile
}
