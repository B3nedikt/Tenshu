package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.trait.isHeadlessSafe
import org.tenshu.sync.SyncNow
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.SyncNowImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Off-device acceptance for the client-initiated "Sync now over cellular" override
 * ([SyncNow], `sync-metered-deferred-status.md` §2.3). The on-device banner tap is
 * device-gated; these cover the wiring:
 *
 *  1. [SyncNow] is **NOT** `HeadlessSafe` — unlike its headless sibling `DrainSync`, it
 *     deliberately overrides a user policy and so must never be scheduled to run with no
 *     user present.
 *  2. Dispatching `SyncNow.ID` through the real `syncServiceCommonModule` schema fires the
 *     force-drain thunk (proves the op schema is registered so dispatch resolves it).
 *  3. The production `syncProxyImplementationFactory` routes `SyncNow.ID` to a
 *     `SyncNowImplementation`.
 */
class SyncNowTest {

    @Test
    fun syncNow_isNotHeadlessSafe() {
        assertFalse(
            SyncNow.Schema.isHeadlessSafe(),
            "SyncNow overrides a user policy — it must NOT be HeadlessSafe (no headless run)",
        )
    }

    @Test
    fun dispatchingSyncNow_firesTheForceDrainThunk() = runTest {
        val forced = IntArray(1)
        val proxy = TestProxy(
            implementationFactoryOverride = { id, _ ->
                if (id == SyncNow.ID) SyncNowImplementation { forced[0]++ } else null
            },
        ).apply { schemaRegistry.registerConfig(syncServiceCommonModule) }

        proxy.executionContext.executeAsync<Data>(operationId = SyncNow.ID, caller = null)

        assertEquals(1, forced[0], "dispatching SyncNow.ID must fire the force-drain once")
    }

    @Test
    fun productionFactory_routesSyncNowId() {
        val crypto = BootstrapSyncCryptoManager()
        val context = createTestContext()
        val syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), context)
        val impl = syncProxyImplementationFactory(
            id = SyncNow.ID,
            relay = MockSyncRelay(context),
            syncManager = syncManager,
            cryptoManager = crypto,
            syncSchedulerHolder = SyncSchedulerHolder(),
        )

        assertTrue(impl is SyncNowImplementation, "production factory must route SyncNow.ID")
    }
}
