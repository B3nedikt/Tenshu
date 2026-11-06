package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit: **desktop contributes the model store's cross-process lock, and takes it
 * before the properties file that holds the model index is read.**
 *
 * ⚠⚠ The failure this catches is silent. `ProxyModule` reads the ownership with `getOrNull` and
 * falls back to `ModelStoreOwnership.SoleProcess`, which is true of Android and iOS and false of
 * desktop — two desktop Controls can still overlap past `ControlInstanceGuard` (a build without
 * the guard). Deleting desktop's binding compiles, starts, and behaves identically until a second
 * Control's first index read sweeps an artifact the first one's index names.
 *
 * ⭐ No runtime test sees it: the graph is assembled only inside a live proxy process, and the
 * ordering is the order of two statements in one Koin provider. Source text is where both show.
 */
class ModelStoreOwnershipWiringTest {

    private val desktop = File("src/desktopMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt")
    private val common = File("src/commonMain/kotlin/app/tenshu/control/di/ProxyModule.kt")

    @Test
    fun `desktop binds the file lock and the common module consumes the binding`() {
        // ⚠ Anti-vacuity: if these paths move, go red rather than quietly stop guarding.
        assertTrue(desktop.isFile && common.isFile, "the scanned module files moved; this test guards nothing")
        val text = desktop.readText()

        assertTrue(
            "single<ModelStoreOwnership>" in text && "FileLockModelStoreOwnership(" in text,
            "desktop no longer binds FileLockModelStoreOwnership, so its model store falls back to " +
                "SoleProcess while two Controls can still run.",
        )
        assertTrue(
            "getOrNull<ModelStoreOwnership>()" in common.readText(),
            "ProxyModule no longer reads the platform's ModelStoreOwnership; desktop's lock is bound and unused.",
        )
    }

    @Test
    fun `desktop takes the lock before it reads the properties file`() {
        val text = desktop.readText()
        val provider = text.indexOf("single<SecureStorageDelegate>")
        val takesLock = text.indexOf("get<ModelStoreOwnership>()", startIndex = provider)
        val readsFile = text.indexOf("DesktopSecureStorageDelegate(", startIndex = provider)

        assertTrue(provider >= 0 && readsFile > provider, "the SecureStorageDelegate provider moved")
        assertTrue(
            takesLock in (provider + 1) until readsFile,
            "the SecureStorageDelegate provider must resolve ModelStoreOwnership BEFORE it constructs " +
                "DesktopSecureStorageDelegate: the index is loaded in that constructor, and a lock " +
                "taken after it can be won with a stale index.",
        )
    }
}
