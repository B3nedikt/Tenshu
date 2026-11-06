package org.tenshu.sdk.ios

import app.tenshu.control.data.BiometricUnlockDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.file.proxy.ios.IosUploadSourceReader
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * ⚠⚠ Drive's half of the per-app guard (`docs/proposals/ios-per-app-frameworks.md` §6.2, D4): the
 * runtime in **Drive's own framework**, assembled the way drive-ios assembles it
 * ([DriveLocalRuntime.assemble]), declares exactly [PinnedServiceVersions] — the list every other
 * app's guard checks too (`<App>DeclaredServiceVersionsTest` in each `:tenshu-sdk-ios-<app>`).
 *
 * Drive shares the App Group store with every route-1 app: one service slot behind wipes it, a
 * missing service loses that service's rows at ingest.
 *
 * ⚠ Real dispatchers and `Main` redirected, for [DriveLocalRuntimeTest]'s reasons. The replica is a
 * temp directory and the Keychain in-memory: the declared vector depends on the modules, not on
 * these.
 */
class DriveDeclaredServiceVersionsTest {

    @BeforeTest
    fun redirectMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun restoreMain() = Dispatchers.resetMain()

    @Test
    fun driveDeclaresThePinnedServiceVersions() {
        val replica = NSTemporaryDirectory() + "declared-service-versions-drive-" + Random.nextLong().toString(16)
        val koin = DriveLocalRuntime.assemble(replica, InMemorySecureStorageDelegate()).koin
        assertDeclaresPinnedServiceVersions("Drive", koin)
        // D6: Drive, and only Drive, reads `phasset://` sources — its override wins over the base.
        assertSame<IosUploadSourceReader>(IosPhotoUploadSource, koin.get())
        // §3.3: the Face ID realm unlock is Control's alone. Bound in Drive's graph it would keep
        // LocalAuthentication in Drive's binary, and Drive declares no NSFaceIDUsageDescription.
        assertNull(koin.getOrNull<BiometricUnlockDelegate>(), "Drive's graph binds a BiometricUnlockDelegate")
    }
}
