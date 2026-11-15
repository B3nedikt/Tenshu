package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.common.and
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * The user's pause switch ([SyncGate] KDoc, `web-apex-home.md` §4.6). ⚠⚠ Unlike the policy
 * `syncGate` — read-only to the scheduler since `sync-metered-plane-split.md` — a closed `pauseGate`
 * must skip the **whole** cycle: push, pull, shared realms, and the cycle hooks that run after them.
 */
class SyncSchedulerPauseGateTest {

    @Test
    fun aPausedTickRunsNoCycle_andResumingRunsItWithTheDeferredEnableEdge() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        var paused = true
        var enabled = 0
        var maintenance = 0
        var cycle = 0
        val scheduler = SyncScheduler(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            syncProcessor = SyncProcessor(
                executionContext = proxy.executionContext,
                syncManager = syncManager,
                cryptoManager = crypto,
                wireIdResolver = WireIdResolver(syncManager),
            ),
            cryptoManager = crypto,
            coroutineScope = this,
            onEnabled = { enabled++ },
            pauseGate = SyncGate { !paused },
        )
        scheduler.addMaintenanceHook { maintenance++ }
        // Runs at the END of the drain/pull body — after push, the personal pull and the shared
        // realms' pulls. Reaching it means the tick went to the relay.
        scheduler.addSyncCycleHook { cycle++ }
        syncManager.initialize("mock phrase")

        scheduler.tickOnce()
        scheduler.tickOnce()
        assertEquals(0, cycle, "a paused tick must not reach the drain/pull body")
        // onEnabled replays held saves, which then want to push: it waits for the first unpaused tick.
        assertEquals(0, enabled, "the enable edge must wait out the pause")
        assertEquals(2, maintenance, "local maintenance is not sync, and keeps running while paused")

        paused = false
        scheduler.tickOnce()
        assertEquals(1, cycle, "resuming runs the cycle")
        assertEquals(1, enabled, "and fires the enable edge it deferred, once")

        paused = true
        scheduler.tickOnce()
        assertEquals(1, cycle, "pausing again stops the next cycle")
    }

    @Test
    fun andIsOpenOnlyWhileBothAre_andAlwaysAllowComposesAway() = runTest {
        var a = true
        var b = true
        val gateA = SyncGate { a }
        val both = gateA.and(SyncGate { b })
        assertTrue(both.shouldSync())
        b = false
        assertFalse(both.shouldSync(), "a closed pause closes the content plane")
        b = true; a = false
        assertFalse(both.shouldSync(), "and so does a closed policy gate")
        // Native wirings pass no pause: their content gate must stay the very same instance.
        assertSame(gateA, gateA.and(SyncGate.AlwaysAllow))
        assertSame(gateA, SyncGate.AlwaysAllow.and(gateA))
    }
}
