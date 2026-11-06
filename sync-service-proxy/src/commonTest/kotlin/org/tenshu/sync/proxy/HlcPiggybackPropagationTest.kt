package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end regression for the HLC-piggyback wire layer per
 * `sync-backend-relay-plan.md` §5.1. Two proxies share a single `MockSyncRelay`;
 * each polls and the OTHER proxy's signed tip shows up in its tracker.
 *
 * Companion to:
 *   - `HlcTipStoreTest` (server storage primitive)
 *   - `SyncChangesPiggybackTest` (server endpoint)
 *   - `HlcDivergenceTrackerTest` (receiver-side detection)
 *
 * This is the "the data actually flows end-to-end" test — without it, all three
 * pieces could unit-pass while the wire-layer integration silently broke.
 */
class HlcPiggybackPropagationTest {

    @Test
    fun twoProxies_eachReceivesPeerTipFromOther() = runTest {
        val serverRegistry = createCoreTestRegistry().apply {
            registerConfig(syncServiceCommonModule)
            registerConfig(identityServiceCommonModule)
        }
        val relay = MockSyncRelay(createTestContext(serverRegistry))

        val (_, syncManagerA, trackerA) = setupProxy(relay, backgroundScope)
        val (_, syncManagerB, trackerB) = setupProxy(relay, backgroundScope)

        // Wait for the schedulers to round-trip a few polls each. After both have
        // pushed their own tips and pulled peer tips, each tracker should have
        // observed the OTHER's deviceId.
        repeat(60) {
            val aSeesB = syncManagerB.currentDeviceId in trackerA.seenPeerDeviceIds()
            val bSeesA = syncManagerA.currentDeviceId in trackerB.seenPeerDeviceIds()
            if (aSeesB && bSeesA) return@repeat
            delay(100)
        }

        assertTrue(
            syncManagerB.currentDeviceId in trackerA.seenPeerDeviceIds(),
            "Proxy A's tracker should have observed Proxy B's tip"
        )
        assertTrue(
            syncManagerA.currentDeviceId in trackerB.seenPeerDeviceIds(),
            "Proxy B's tracker should have observed Proxy A's tip"
        )
        // Healthy propagation produces no warnings — both tips verify (against null
        // public-key lookup since we didn't seed Device entities; tracker treats as
        // transient and accepts) and HLCs only advance.
        assertNull(trackerA.warning.value)
        assertNull(trackerB.warning.value)
    }

    private fun setupProxy(
        relay: MockSyncRelay,
        scope: CoroutineScope
    ): Triple<TestProxy, SyncManager, HlcDivergenceTracker> {
        lateinit var syncManager: SyncManager
        lateinit var crypto: SyncCryptoManager
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().also {
                it.registerConfig(syncServiceCommonModule)
                it.registerConfig(identityServiceCommonModule)
            },
            additionalComponents = mapOf(SyncRelay.ID to relay),
            coroutineScope = scope
        ) { id, _ ->
            syncProxyImplementationFactory(id, relay, syncManager, crypto)
        }
        crypto = BootstrapSyncCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        syncManager.initialize("piggyback-realm-phrase")
        val components = proxy.executionContext.initSyncService(
            syncManager = syncManager,
            cryptoManager = crypto,
            coroutineScope = scope,
            pollingIntervalMs = 100,
            platformScheduler = NoOpPlatformScheduler,
        )
        return Triple(proxy, syncManager, components.divergenceTracker)
    }
}
