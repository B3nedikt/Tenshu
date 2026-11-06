package org.tenshu.sdk.ios

import app.tenshu.control.data.BiometricUnlockDelegate
import app.tenshu.notify.AppAlert
import app.tenshu.notify.NotificationPresenter
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
 * ⚠⚠ Calendar's half of the per-app guard (`docs/proposals/ios-per-app-frameworks.md` §6.2, D4):
 * the runtime in **Calendar's own framework**, assembled the way calendar-ios assembles it, declares
 * exactly [PinnedServiceVersions] — the list every other app's guard checks too.
 *
 * Calendar shares the App Group store with every route-1 app: one service slot behind wipes it, a
 * missing service loses that service's rows at ingest.
 *
 * Through [CalendarLocalRuntime.assembleBase] with a stand-in presenter: the real one reaches
 * `UNUserNotificationCenter` at construction, which aborts this bundle-less test host. The override
 * module is the same binding either way.
 *
 * ⚠ Real dispatchers and `Main` redirected, for `DriveLocalRuntimeTest`'s reasons. The replica is a
 * temp directory and the Keychain in-memory: the declared vector depends on the modules, not on
 * these.
 */
class CalendarDeclaredServiceVersionsTest {

    @BeforeTest
    fun redirectMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun restoreMain() = Dispatchers.resetMain()

    @Test
    fun calendarDeclaresThePinnedServiceVersions() {
        val base = CalendarLocalRuntime.assembleBase(
            replicaDirectory = NSTemporaryDirectory() + "declared-service-versions-calendar-" + Random.nextLong().toString(16),
            secureStorage = InMemorySecureStorageDelegate(),
            handedOffMaterial = null,
            relayBaseUrl = null,
            presenter = SilentPresenter,
        )
        assertDeclaresPinnedServiceVersions("Calendar", base.koin)
        // D6: every runtime but Drive's binds the base reader, which has no PhotoKit in it.
        assertSame<IosUploadSourceReader>(IosFileUploadSource, base.koin.get())
        // §3.3: the Face ID realm unlock is Control's alone (P3).
        assertNull(base.koin.getOrNull<BiometricUnlockDelegate>(), "Calendar's graph binds a BiometricUnlockDelegate")
    }

    private object SilentPresenter : NotificationPresenter {
        override fun canPost(): Boolean = false
        override fun post(alert: AppAlert) = Unit
        override fun cancel(ids: Collection<String>) = Unit
    }
}
