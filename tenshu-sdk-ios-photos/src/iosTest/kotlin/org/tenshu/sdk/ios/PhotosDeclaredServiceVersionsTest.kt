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
 * ⚠⚠ Photos' half of the per-app guard (`docs/proposals/ios-per-app-frameworks.md` §6.2, D4): the
 * runtime in **Photos' own framework**, assembled the way photos-ios assembles it, declares exactly
 * [PinnedServiceVersions] — the list every other app's guard checks too.
 *
 * Photos shares the App Group store with every route-1 app: one service slot behind wipes it, a
 * missing service loses that service's rows at ingest.
 *
 * ⚠ Real dispatchers and `Main` redirected, for `DriveLocalRuntimeTest`'s reasons. The replica is a
 * temp directory and the Keychain in-memory: the declared vector depends on the modules, not on
 * these.
 */
class PhotosDeclaredServiceVersionsTest {

    @BeforeTest
    fun redirectMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun restoreMain() = Dispatchers.resetMain()

    @Test
    fun photosDeclaresThePinnedServiceVersions() {
        val replica = NSTemporaryDirectory() + "declared-service-versions-photos-" + Random.nextLong().toString(16)
        val koin = PhotosLocalRuntime.assemble(
            replicaDirectory = replica,
            secureStorage = InMemorySecureStorageDelegate(),
            inferenceRoot = replica + "-inference",
        ).koin
        assertDeclaresPinnedServiceVersions("Photos", koin)
        // D6: every runtime but Drive's binds the base reader, which has no PhotoKit in it.
        assertSame<IosUploadSourceReader>(IosFileUploadSource, koin.get())
        // §3.3: the Face ID realm unlock is Control's alone (P3).
        assertNull(koin.getOrNull<BiometricUnlockDelegate>(), "Photos' graph binds a BiometricUnlockDelegate")
    }
}
