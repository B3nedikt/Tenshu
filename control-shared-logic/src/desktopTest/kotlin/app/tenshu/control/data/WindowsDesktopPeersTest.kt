package app.tenshu.control.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Windows half of `control-app-catalogue.md` C2 (`desktop-windows-port.md` §3.2). */
class WindowsDesktopPeersTest {

    @Test
    fun everyDeclaredWindowsPackageIsAdmissible_andResolvesToItsApp() {
        val declared = BundledAppCatalogue.apps.flatMap { app ->
            app.clients.filterIsInstance<ClientBinding.WindowsPackage>().map { it.packageName to app.appId }
        }
        assertTrue(declared.isNotEmpty(), "Drive declares a Windows package")
        declared.forEach { (name, _) -> assertTrue(name in FirstPartyDesktopPeers.WINDOWS_PACKAGES, "$name must be admissible") }
        assertTrue(("Tenshu.Drive" to "app.tenshu.drive") in declared)
    }

    /** ⚠ Control is bridge-only: it must never register as an app on ipc.sock. */
    @Test
    fun controlIsAdmittedToTheBridgeOnly() {
        FirstPartyDesktopPeers.WINDOWS_CONTROL_PACKAGES.forEach {
            assertFalse(it in FirstPartyDesktopPeers.WINDOWS_PACKAGES, "$it must not reach ipc.sock")
            assertTrue(it in FirstPartyDesktopPeers.WINDOWS_BRIDGE_PACKAGES)
        }
    }

    @Test
    fun anAppWithoutAWindowsBindingIsNotAdmissible() {
        val apps = listOf(
            FirstPartyApp(BundledAppManifestSource.DRIVE_MANIFEST, listOf(ClientBinding.WindowsPackage("Example.Win"))),
            FirstPartyApp(BundledAppManifestSource.FIT_MANIFEST, listOf(ClientBinding.MacCodeSignature("app.tenshu.fit.ios"))),
        )
        assertEquals(setOf("Example.Win"), windowsPackagesOf(apps))
    }

    /** A Windows package name and a macOS identifier must never be confused for one another. */
    @Test
    fun windowsAndMacIdentitiesDoNotOverlap() {
        assertTrue((FirstPartyDesktopPeers.WINDOWS_BRIDGE_PACKAGES intersect FirstPartyDesktopPeers.BRIDGE_IDENTIFIERS).isEmpty())
    }
}
