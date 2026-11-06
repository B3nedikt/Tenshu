package org.tenshu.sdk.ios

import app.tenshu.control.data.BiometricUnlockDelegate
import app.tenshu.control.data.IosBiometricUnlockDelegate
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.InProcessDatabaseLock
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * ⚠⚠ Control's half of the per-app guard (`docs/proposals/ios-per-app-frameworks.md` §6.2, D4):
 * the runtime in **Control's own framework** declares exactly [PinnedServiceVersions], the list
 * every other app's guard checks too (`<App>DeclaredServiceVersionsTest` in each `:tenshu-sdk-ios-<app>`).
 *
 * Control shares the App Group store with every route-1 app: one service slot behind wipes it, a
 * missing service loses that service's rows at ingest. Control does NOT go through
 * `FirstPartyLocalRuntime.assembleBase`; `installRealRuntime` builds its graph from
 * [ControlSurface.runtimeModules], which is what this assembles.
 */
class ControlDeclaredServiceVersionsTest {

    @Test
    fun controlDeclaresThePinnedServiceVersions() {
        // ⚠ The overrides are isolation only — Control's store, lock and Keychain would otherwise
        // be the test host's real ones. The declared vector depends on the modules, not on these.
        val koin = koinApplication {
            modules(
                ControlSurface.runtimeModules() + module {
                    single<SecureStorageDelegate> { InMemorySecureStorageDelegate() }
                    single<ObjectStore> { InMemoryObjectStore() }
                    single<DatabaseLock> { InProcessDatabaseLock() }
                },
            )
        }.koin
        assertDeclaresPinnedServiceVersions("Control", koin)
        // §3.3 (P3): the Face ID realm unlock is bound in Control's own graph, not in the shared
        // platform module every route-1 app assembles. `installRealRuntime` resolves it.
        assertIs<IosBiometricUnlockDelegate>(koin.get<BiometricUnlockDelegate>())
    }
}
