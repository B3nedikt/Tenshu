package org.tenshu.sync.proxy

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.SyncService
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.client.syncHealth
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Local (single-proxy) round-trip proving the sync-health relation end-to-end
 * without sync (`drive-connectivity-service.md`): [initSyncHealth] derives the
 * [SyncHealthHolder]'s figures into the single-row `syncHealth` relation, and the
 * client chain surfaces them — the initial optimistic `online`, then reacting to
 * each holder change. Mirrors `StorageServiceLocalTest`.
 */
class SyncHealthRelationLocalTest {

    @Test
    fun syncHealth_reflectsHolder_throughClientChain() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(syncServiceClientModule)

        val holder = SyncHealthHolder()
        proxy.executionContext.initSyncHealth(backgroundScope, holder)

        val service = environmentFor(proxy).getService<SyncService>(SyncService.ID)

        // Initial optimistic figures: online, not syncing, no backlog.
        val initial = service.syncHealth().filterNotNull().first()
        assertEquals(SyncHealthStatus.CONNECTIVITY_ONLINE, initial.connectivity.get())
        assertEquals(false, initial.isSyncing.get())
        assertEquals(0L, initial.pendingBlockCount.get())

        // A failed tick with a backlog → the derived row reacts.
        holder.onTickComplete(online = false, pendingBlockCount = 5, lastSyncedAtMillis = 123L)
        val offline = service.syncHealth()
            .filterNotNull()
            .first { it.connectivity.get() == SyncHealthStatus.CONNECTIVITY_OFFLINE }
        assertEquals(5L, offline.pendingBlockCount.get())
        assertEquals(123L, offline.lastSyncedAtMillis.get())
    }

    @Test
    fun deviceUnrecognized_derivesItsOwnConnectivityValue_aheadOfEntitlement() = runTest {
        // The row is what crosses the IPC boundary, so the state is only real if it survives the
        // derivation. It is carried as a new VALUE of the existing `connectivity` field, never a new
        // field: the composite is positional, so a Drive built against an older Control still parses
        // the row and degrades to its offline banner (see the constant's KDoc).
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(syncServiceClientModule)

        val holder = SyncHealthHolder()
        proxy.executionContext.initSyncHealth(backgroundScope, holder)
        val service = environmentFor(proxy).getService<SyncService>(SyncService.ID)

        // Both flags set: the relay refuses this device's identity before it ever reaches the
        // entitlement check, so `device_unrecognized` must win over `needs_attention`.
        holder.onTickComplete(
            online = true,
            pendingBlockCount = 0,
            lastSyncedAtMillis = 0L,
            entitlementBlocked = true,
            deviceUnrecognized = true,
        )
        val row = service.syncHealth()
            .filterNotNull()
            .first { it.connectivity.get() != SyncHealthStatus.CONNECTIVITY_ONLINE }
        assertEquals(SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED, row.connectivity.get())
    }

    @Test
    fun seededLocked_publishesLockedOnTheVeryFirstEmission() = runTest {
        // Regression: on a sealed cold start the storage figures go `hasStorage = false`
        // synchronously at init, but the persisted health row still held the previous (unlocked)
        // process's value until the first scheduler tick — so Drive saw "not subscribed, not
        // locked" and rendered "Local only — set up storage" during startup. Seeding the holder
        // before the derivation's first emission closes that window: no tick is required here.
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(syncServiceClientModule)

        val holder = SyncHealthHolder()
        holder.seedLocked(true)
        proxy.executionContext.initSyncHealth(backgroundScope, holder)

        val service = environmentFor(proxy).getService<SyncService>(SyncService.ID)

        val first = service.syncHealth().filterNotNull().first()
        assertEquals(
            SyncHealthStatus.CONNECTIVITY_LOCKED,
            first.connectivity.get(),
            "a sealed proxy must publish `locked` without waiting for a tick",
        )
    }

    @Test
    fun seedLocked_falseLeavesTheOptimisticOnlineSeed() = runTest {
        // The seed must not itself become a source of wrong state: an unsealed proxy keeps the
        // documented optimistic `online` start so the banner never flashes on a healthy launch.
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(syncServiceClientModule)

        val holder = SyncHealthHolder()
        holder.seedLocked(false)
        proxy.executionContext.initSyncHealth(backgroundScope, holder)

        val service = environmentFor(proxy).getService<SyncService>(SyncService.ID)

        assertEquals(
            SyncHealthStatus.CONNECTIVITY_ONLINE,
            service.syncHealth().filterNotNull().first().connectivity.get(),
        )
    }

    private fun environmentFor(proxy: TestProxy): TenshuEnvironment {
        val executor = LocalTaskExecutor(proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = proxy.tContext,
        )
    }
}
