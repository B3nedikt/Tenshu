package app.tenshu.ui.storage

import app.tenshu.ui.ipc.ControlServerCheck
import app.tenshu.windows.WindowsInstalledPackages
import app.tenshu.windows.WindowsPackageOrigin

/**
 * The Microsoft Store pages of the reserved Windows packages, by MSIX identity name
 * (`desktop-windows-port.md` D7, Partner Center → Product identity).
 *
 * ⚠ Public data, and safe to compile in: a link is offered only to a copy whose package origin says
 * it came from the Store ([windowsInstallRoute], [windowsAppUpdatePages]), and such a copy exists only
 * once its product is published. An app reserved later is added here with its Store ID.
 */
internal val WINDOWS_STORE_IDS: Map<String, String> = mapOf(
    "62647B3nedikt.TenshuControl" to "9N7HTCGVX8PV",
    "62647B3nedikt.TenshuDrive" to "9NMC0PNRG2HH",
)

/** A reserved package's Store page, or `null` for a package with no Store ID. */
internal fun windowsStorePage(identityName: String): String? =
    WINDOWS_STORE_IDS[identityName]?.let { "https://apps.microsoft.com/detail/$it" }

/**
 * Windows' install route for Control (ADR 0025). Only a copy that came from the Store
 * ([own] origin [WindowsPackageOrigin.STORE]) is sent to the Store; a side-loaded, dev or unpackaged
 * copy installs Control "the way you installed this app".
 */
internal fun windowsInstallRoute(own: WindowsInstalledPackages.Copy?): ControlInstallRoute = when {
    own?.origin != WindowsPackageOrigin.STORE -> ControlInstallRoute.SameChannelAsThisApp
    else -> windowsStorePage(ControlServerCheck.WINDOWS_CONTROL_PACKAGE)
        ?.let { ControlInstallRoute.Store(it) }
        ?: ControlInstallRoute.NotPublishedYet
}

/**
 * Windows' update pages, each from the channel of the copy it updates (`AppUpdatePages`'s rule):
 * this app's Store page when this copy came from the Store; Control's when the Control installed here
 * came from the Store ([controlOrigin]). ⚠ A Store app beside a side-loaded Control gets no Control
 * page: sending that user to the Store would leave Control on two channels.
 */
internal fun windowsAppUpdatePages(own: WindowsInstalledPackages.Copy?, controlOrigin: Int?): AppUpdatePages {
    val ownPage = own?.takeIf { it.origin == WindowsPackageOrigin.STORE }?.let { windowsStorePage(it.identityName) }
    if (own?.identityName == ControlServerCheck.WINDOWS_CONTROL_PACKAGE) return AppUpdatePages(thisApp = ownPage, control = ownPage)
    val controlPage = if (controlOrigin == WindowsPackageOrigin.STORE) windowsStorePage(ControlServerCheck.WINDOWS_CONTROL_PACKAGE) else null
    return AppUpdatePages(thisApp = ownPage, control = controlPage)
}

/** The origin of the Control installed here, under any publisher this build trusts, or `null`. */
internal fun installedControlOrigin(publisherIds: Set<String>): Int? =
    publisherIds.firstNotNullOfOrNull { id -> WindowsInstalledPackages.originOf("${ControlServerCheck.WINDOWS_CONTROL_PACKAGE}_$id") }

internal fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
