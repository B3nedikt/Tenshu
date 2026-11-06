package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scheduler's locked→enabled edge (fix for stranded saves-while-locked): [SyncScheduler]
 * fires its `onEnabled` hook exactly once each time the realm transitions from locked
 * (`!isEnabled()`) to enabled, and re-arms after a re-lock. Production points that hook at
 * [SyncObserver.reemitPendingLockedWork] so work held while locked is replayed on unlock.
 */
class SyncSchedulerEnableEdgeTest {

    @Test
    fun onEnabled_firesOncePerLockedToEnabledEdge() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        val processor = SyncProcessor(executionContext = proxy.executionContext, syncManager = syncManager, cryptoManager = crypto, wireIdResolver = WireIdResolver(syncManager))

        var enabledCount = 0
        // Drive tickOnce() directly (no start()) so the transitions are deterministic; the
        // drain/pull inside is a no-op here (empty queues; the pull's missing relay op is caught).
        val scheduler = SyncScheduler(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            syncProcessor = processor,
            cryptoManager = crypto,
            coroutineScope = this,
            onEnabled = { enabledCount++ },
        )

        scheduler.tickOnce() // still locked → no edge
        assertEquals(0, enabledCount, "a locked tick must not fire onEnabled")

        syncManager.initialize("mock phrase") // unlock
        scheduler.tickOnce()
        assertEquals(1, enabledCount, "the locked→enabled edge fires onEnabled once")

        scheduler.tickOnce() // still enabled
        assertEquals(1, enabledCount, "a subsequent enabled tick must not re-fire onEnabled")

        syncManager.lock() // re-arm
        scheduler.tickOnce()
        syncManager.initialize("mock phrase") // unlock again
        scheduler.tickOnce()
        assertEquals(2, enabledCount, "onEnabled re-arms and fires again after a re-lock")
    }

    @Test
    fun maintenanceRunsOnALockedTickWhileCycleHooksDoNot() = runTest {
        // ⚠⚠ The distinction this whole hook exists for. `tickOnce` returns at the enabled guard,
        // so anything registered with `addSyncCycleHook` never runs in a process whose realm stays
        // sealed — which is exactly Drive-on-iOS's route-1 read path ("no genesis, no unlock").
        //
        // Measured 2026-08-15: the orphan sweeper was constructed on every Drive launch and never
        // once called, leaving 786 orphaned rows that — because `UploadFileImplementation` tests
        // existence by path — also blocked their own re-upload. Control swept its own separate
        // store to completion throughout, which is what made it look healthy.
        //
        // GC needs no keys, so it belongs before the guard; the invite pickup needs realm keys, so
        // it must stay after. Both halves are asserted here, because moving the wrong one is the
        // easy mistake.
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        val scheduler = SyncScheduler(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            syncProcessor = SyncProcessor(executionContext = proxy.executionContext, syncManager = syncManager, cryptoManager = crypto, wireIdResolver = WireIdResolver(syncManager)),
            cryptoManager = crypto,
            coroutineScope = this,
        )

        var maintenance = 0
        var cycle = 0
        scheduler.addMaintenanceHook { maintenance++ }
        scheduler.addSyncCycleHook { cycle++ }

        scheduler.tickOnce() // realm still sealed

        assertEquals(1, maintenance, "maintenance must run on a sealed realm — GC needs no keys")
        assertEquals(0, cycle, "and a cycle hook must NOT, because it may need them")
    }
}
