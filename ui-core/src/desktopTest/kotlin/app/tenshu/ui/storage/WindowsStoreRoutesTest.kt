package app.tenshu.ui.storage

import app.tenshu.reachability.UpdateTarget
import app.tenshu.ui.ipc.ControlServerCheck
import app.tenshu.windows.WindowsInstalledPackages.Copy
import app.tenshu.windows.WindowsPackageOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ADR 0025 on Windows: a Store link only for a copy that came from the Store. */
class WindowsStoreRoutesTest {

    private val publisher = "1pr1nmnfah2xa"
    private val controlPage = "https://apps.microsoft.com/detail/9N7HTCGVX8PV"
    private val drivePage = "https://apps.microsoft.com/detail/9NMC0PNRG2HH"

    private fun drive(origin: Int?) = Copy("62647B3nedikt.TenshuDrive_$publisher", origin)
    private fun control(origin: Int?) = Copy("${ControlServerCheck.WINDOWS_CONTROL_PACKAGE}_$publisher", origin)

    @Test
    fun aStoreCopyIsSentToControlsStorePage() {
        assertEquals(ControlInstallRoute.Store(controlPage), windowsInstallRoute(drive(WindowsPackageOrigin.STORE)))
    }

    @Test
    fun anyOtherCopyInstallsControlTheWayItWasInstalled() {
        for (origin in listOf(WindowsPackageOrigin.DEVELOPER_SIGNED, WindowsPackageOrigin.UNKNOWN, null)) {
            assertEquals(ControlInstallRoute.SameChannelAsThisApp, windowsInstallRoute(drive(origin)), "origin $origin")
        }
        // A dev run or jpackage image has no package identity at all.
        assertEquals(ControlInstallRoute.SameChannelAsThisApp, windowsInstallRoute(null))
    }

    @Test
    fun eachUpdatePageFollowsTheChannelOfTheCopyItUpdates() {
        val both = windowsAppUpdatePages(drive(WindowsPackageOrigin.STORE), controlOrigin = WindowsPackageOrigin.STORE)
        assertEquals(drivePage, both.pageFor(UpdateTarget.THIS_APP))
        assertEquals(controlPage, both.pageFor(UpdateTarget.CONTROL))

        // A Store Drive beside a side-loaded Control: no Control page, or Control ends up on two channels.
        val mixed = windowsAppUpdatePages(drive(WindowsPackageOrigin.STORE), controlOrigin = WindowsPackageOrigin.DEVELOPER_SIGNED)
        assertEquals(drivePage, mixed.thisApp)
        assertNull(mixed.control)

        // A side-loaded Drive beside a Store Control: Control's page, none for Drive.
        val other = windowsAppUpdatePages(drive(WindowsPackageOrigin.DEVELOPER_SIGNED), controlOrigin = WindowsPackageOrigin.STORE)
        assertNull(other.thisApp)
        assertEquals(controlPage, other.control)

        assertEquals(AppUpdatePages.NONE, windowsAppUpdatePages(null, controlOrigin = null))
    }

    @Test
    fun controlsOwnBannerUsesItsOwnPageForBoth() {
        val pages = windowsAppUpdatePages(control(WindowsPackageOrigin.STORE), controlOrigin = null)
        assertEquals(AppUpdatePages(thisApp = controlPage, control = controlPage), pages)
    }

    @Test
    fun aPackageWithoutAStoreIdGetsNoPage() {
        val photos = Copy("62647B3nedikt.TenshuPhotos_$publisher", WindowsPackageOrigin.STORE)
        assertNull(windowsAppUpdatePages(photos, controlOrigin = null).thisApp)
    }

    @Test
    fun theStoreIdsAreTheReservedIdentities() {
        // The pins of desktop-windows-port.md D7: Control's name must be the one the peer gate pins.
        assertEquals("9N7HTCGVX8PV", WINDOWS_STORE_IDS[ControlServerCheck.WINDOWS_CONTROL_PACKAGE])
        assertEquals("9NMC0PNRG2HH", WINDOWS_STORE_IDS["62647B3nedikt.TenshuDrive"])
    }
}
