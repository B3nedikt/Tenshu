package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.identity.proxy.initIdentityService
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CR-2 B6 (`content-plane-cr2-b6-spec.md` §2, §4): the scheduler's `onTickSettled` hook stays because the
 * orphaned-bytes sweep rides it (`content-plane-cr2-quorumless.md` §10.9). ST-3b's reclamation sweep shared
 * the hook until B6 removed it, and `initSyncService` used to build the hook only while either was present —
 * so this pins that the orphaned-bytes sweep alone still reaches a settled tick, through the real wiring.
 */
class OrphanedBytesTickHookTest {

    @Test
    fun theOrphanedBytesSweepRunsOnASettledTick() = runTest {
        var runs = 0
        val scheduler = setup(backgroundScope) { runs++ }

        scheduler.tickOnce()

        assertEquals(1, runs, "the tick's settled hook must run the orphaned-bytes sweep")
    }

    @Test
    fun aThrowingSweepCostsALogLine_notTheTick() = runTest {
        var runs = 0
        val scheduler = setup(backgroundScope) {
            runs++
            error("the store is unavailable")
        }

        scheduler.tickOnce()
        scheduler.tickOnce()

        assertEquals(2, runs, "housekeeping that throws must not stop the next tick from running it")
    }

    private fun setup(scope: CoroutineScope, sweep: suspend () -> Unit): SyncScheduler {
        val registry = createCoreTestRegistry().apply {
            registerConfig(syncServiceCommonModule)
            registerConfig(identityServiceCommonModule)
        }
        val relay = MockSyncRelay(createTestContext(registry))
        lateinit var syncManager: SyncManager
        lateinit var crypto: SyncCryptoManager
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().also {
                it.registerConfig(syncServiceCommonModule)
                it.registerConfig(identityServiceCommonModule)
            },
            additionalComponents = mapOf(SyncRelay.ID to relay),
            coroutineScope = scope,
        ) { id, _ -> syncProxyImplementationFactory(id, relay, syncManager, crypto) }
        crypto = BootstrapSyncCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("orphaned-bytes-tick-hook-realm")
        proxy.executionContext.initIdentityService()
        return proxy.executionContext.initSyncService(
            syncManager = syncManager,
            cryptoManager = crypto,
            coroutineScope = scope,
            pollingIntervalMs = 100,
            startBackgroundLoops = false,
            orphanedBytesSweep = sweep,
            platformScheduler = NoOpPlatformScheduler,
        ).scheduler
    }
}
