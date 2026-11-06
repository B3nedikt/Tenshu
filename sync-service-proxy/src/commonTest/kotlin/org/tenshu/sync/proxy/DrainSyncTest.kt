package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.trait.isHeadlessSafe
import org.tenshu.sync.DrainSync
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.DrainSyncImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * bgproc Phase 1 (`bgproc-scheduling-roles.md` §6) acceptance tests for the
 * head-less `DrainSync` op. The Android WorkManager / app-closed path is
 * on-device-gated; these cover everything verifiable off-device:
 *
 *  1. `DrainSync` is `HeadlessSafe` — the fail-closed gate that lets
 *     `ScheduleService` schedule it (a non-`HeadlessSafe` op is rejected).
 *  2. Dispatching `DrainSync.ID` through the real `syncServiceCommonModule`
 *     schema + `executeAsync` runs the impl and fires its drain thunk
 *     (proves the op schema is registered so dispatch resolves it).
 *  3. The production `syncProxyImplementationFactory` routes `DrainSync.ID`
 *     to a `DrainSyncImplementation` (not "no implementation").
 */
class DrainSyncTest {

    @Test
    fun drainSync_isHeadlessSafe() {
        assertTrue(
            DrainSync.Schema.isHeadlessSafe(),
            "DrainSync must be HeadlessSafe or ScheduleService will refuse to schedule it",
        )
    }

    @Test
    fun dispatchingDrainSync_firesTheDrainThunk() = runTest {
        val drained = IntArray(1)
        val proxy = TestProxy(
            implementationFactoryOverride = { id, _ ->
                if (id == DrainSync.ID) DrainSyncImplementation { drained[0]++ } else null
            },
        ).apply { schemaRegistry.registerConfig(syncServiceCommonModule) }

        proxy.executionContext.executeAsync<Data>(operationId = DrainSync.ID, caller = null)

        assertEquals(1, drained[0], "dispatching DrainSync.ID must run the drain once")
    }

    @Test
    fun productionFactory_routesDrainSyncId() {
        val crypto = BootstrapSyncCryptoManager()
        val context = createTestContext()
        val syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), context)
        // The DrainSync branch ignores relay/syncManager/crypto; they only satisfy
        // the shared factory signature. A null-scheduler holder ⇒ the drain no-ops.
        val impl = syncProxyImplementationFactory(
            id = DrainSync.ID,
            relay = MockSyncRelay(context),
            syncManager = syncManager,
            cryptoManager = crypto,
            syncSchedulerHolder = SyncSchedulerHolder(),
        )

        assertTrue(impl is DrainSyncImplementation, "production factory must route DrainSync.ID")
    }
}
