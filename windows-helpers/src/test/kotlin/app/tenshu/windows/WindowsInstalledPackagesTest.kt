package app.tenshu.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app-model bindings resolve on Windows and answer for an unpackaged process. ⚠ Availability is a
 * hard assertion on Windows (the `WindowsDpapiTest` rule): a binding that silently failed to resolve
 * would make every answer `null`, which would also read as "not from the Store".
 */
class WindowsInstalledPackagesTest {

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Test
    fun availableExactlyOnWindows() {
        assertEquals(windows, WindowsInstalledPackages.isAvailable())
    }

    @Test
    fun aTestJvmHasNoPackageIdentity() {
        assertNull(WindowsInstalledPackages.current())
    }

    @Test
    fun aFamilyThatIsNotInstalledHasNoOrigin() {
        assertNull(WindowsInstalledPackages.originOf("Tenshu.NotInstalled_0000000000000"))
    }

    @Test
    fun anInstalledFamilyReportsAKnownOrigin() {
        if (!windows) return
        // The Store app itself, where the OS has one (not on Windows Server runners): any answer must be a
        // real PackageOrigin value, so a misread buffer cannot pass as "from the Store".
        val origin = WindowsInstalledPackages.originOf("Microsoft.WindowsStore_8wekyb3d8bbwe") ?: return
        assertTrue(origin in 0..6, "PackageOrigin $origin")
    }

    @Test
    fun theIdentityNameDropsThePublisherId() {
        assertEquals("62647B3nedikt.TenshuDrive", WindowsInstalledPackages.Copy("62647B3nedikt.TenshuDrive_1pr1nmnfah2xa", 3).identityName)
    }
}
