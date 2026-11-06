package org.tenshu.sync.proxy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.sync.SyncService
import org.tenshu.sync.client.catchUp
import org.tenshu.sync.client.syncServiceClientModule
import org.tenshu.sync.common.FirstPullMarker
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.CatchUpFixture.Answer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-writer `SyncCatchUpStatus` row and its one writer
 * (`ios-sync-activity-indicator.md` §3.1) — at the relation, through the client chain a surface
 * reads, not at the in-memory flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCatchUpRelationTest {

    @Test
    fun theClientChain_readsThisWritersRow_andFollowsIt() = runTest {
        val proxy = proxy()
        val catchUp = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a")
        val service = proxy.service()

        assertFalse(service.catchUp("app.a").first(), "no row reads as not catching up")

        catchUp.raise()
        assertTrue(service.catchUp("app.a").first { it })

        catchUp.settle(false)
        assertFalse(service.catchUp("app.a").first { !it })
    }

    @Test
    fun aRaise_hasLandedWhenItReturns_withNoCollectorHavingRun() = runTest {
        val proxy = proxy()
        val catchUp = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a")

        catchUp.raise() // nothing has yielded to the collector

        assertEquals(true, proxy.row("app.a"))
    }

    @Test
    fun aStaleFalse_deliveredLate_doesNotOverwriteANewerTrue() = runTest {
        val proxy = proxy()
        val catchUp = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a")
        catchUp.raise()
        runCurrent()

        // Tick N settles false; before the collector gets to write it, tick N+1 raises.
        catchUp.settle(false)
        catchUp.raise()
        assertEquals(true, proxy.row("app.a"))

        runCurrent() // the collector now wakes, holding the `false` it was woken for

        assertEquals(true, proxy.row("app.a"), "it wrote the CURRENT value, not the collected one")
    }

    @Test
    fun aRaiseAndItsSettle_withNoCollectorRunBetween_stillEndFalse() = runTest {
        // One tick: raise, apply, settle, and only then does anything else get to run. The flag
        // went false -> true -> false, which a conflating collector sees as "no change".
        val proxy = proxy()
        val catchUp = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a")
        runCurrent() // the collector has seen the initial `false`

        catchUp.raise()
        catchUp.settle(false)
        runCurrent()

        assertEquals(false, proxy.row("app.a"))
    }

    @Test
    fun aTenPageCatchUp_costsTwoWrites() = runTest {
        val writes = mutableListOf<Boolean>()
        var stored: Boolean? = null
        val catchUp = SyncCatchUp(
            object : SyncCatchUp.Row {
                override fun reads(value: Boolean) = if (value) stored == true else stored != true
                override fun write(value: Boolean) {
                    stored = value
                    writes += value
                }
            },
        )
        catchUp.publishIn(backgroundScope)

        repeat(10) { page ->
            catchUp.raise()
            catchUp.settle(page < 9)
            runCurrent()
        }

        assertEquals(listOf(true, false), writes)
    }

    @Test
    fun aRowLeftTrueByAKilledProcess_isReplacedAtStart_beforeAnyCollectorRuns() = runTest {
        val proxy = proxy()
        proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a").raise()
        assertEquals(true, proxy.row("app.a"))

        // The same app starts again over the same store. Nothing yields between these lines.
        proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a")

        assertEquals(false, proxy.row("app.a"))
    }

    @Test
    fun anOrdinaryStart_writesNothing() = runTest {
        val proxy = proxy()

        proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a")
        runCurrent()

        assertEquals(null, proxy.row("app.a"), "absent already reads false; a launch costs no store write")
    }

    @Test
    fun onceStopped_nothingIsAccepted_untilResumed() = runTest {
        val proxy = proxy()
        val catchUp = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a")
        catchUp.raise()

        catchUp.stop()
        catchUp.raise()
        catchUp.settle(true)
        runCurrent()
        assertFalse(catchUp.catchingUp)
        assertEquals(false, proxy.row("app.a"))

        catchUp.resume()
        catchUp.raise()
        assertEquals(true, proxy.row("app.a"))
    }

    @Test
    fun aRuntimeWithNoHostId_stillHasARowOfItsOwn() = runTest {
        val proxy = proxy()
        val catchUp = proxy.executionContext.initSyncCatchUp(backgroundScope, writerId = null)

        catchUp.raise()

        assertEquals(true, proxy.row(org.tenshu.sync.SyncCatchUpStatus.UNNAMED_WRITER))
    }

    // ---- two runtimes over one store ---------------------------------------------------------

    @Test
    fun twoRuntimesOverOneStore_anIdleOnesStartAndTickEnd_leaveTheOthersRowAlone() = runTest {
        // Two real schedulers, two proxies, ONE object store — the simulator's shape today, and
        // an App Group build's. On a singleton row B's start or B's tick end would clear A.
        val store = InMemoryObjectStore()
        val a = CatchUpFixture.create(this, writerId = "app.a", objectStore = store)
        a.syncManager.realmRelayRegistered = true
        a.syncManager.firstPullMarker = FirstPullMarker.COMPLETED
        a.syncManager.lastSyncTimestamp = 1_000
        a.syncManager.lastTombstoneSyncTimestamp = 1_000
        a.syncManager.backfillCursor = 0
        a.forward += Answer.Page(blocks = a.junkBlocks(1), nextCursor = 2_000, hasMore = true)
        a.scheduler.tickOnce()
        runCurrent()
        assertEquals(true, a.row())

        // B starts, idle, over the same store …
        val b = CatchUpFixture.create(this, writerId = "app.b", objectStore = store)
        b.syncManager.realmRelayRegistered = true
        b.syncManager.firstPullMarker = FirstPullMarker.COMPLETED
        b.syncManager.backfillCursor = 0
        runCurrent()
        assertEquals(true, b.rowOf("app.a"), "B's start left A's row")

        // … and ticks with nothing to pull.
        b.scheduler.tickOnce()
        runCurrent()
        assertEquals(true, b.rowOf("app.a"), "and so did B's tick end")

        // Each reader sees its own row, through its own client chain.
        assertTrue(a.proxy.service().catchUp("app.a").first())
        assertFalse(b.proxy.service().catchUp("app.b").first(), "B is idle, whatever A is doing")

        // A finishes; B's row is untouched by that too.
        a.forward += Answer.Page(blocks = a.junkBlocks(1), nextCursor = 3_000)
        a.scheduler.tickOnce()
        runCurrent()
        assertFalse(a.proxy.service().catchUp("app.a").first())
        assertFalse(b.proxy.service().catchUp("app.b").first())
    }

    @Test
    fun twoRuntimesOverOneStore_bothCatchingUp_eachClearsOnlyItsOwn() = runTest {
        val proxy = proxy()
        val a = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.a")
        val b = proxy.executionContext.initSyncCatchUp(backgroundScope, "app.b")
        a.raise()
        b.raise()

        a.settle(false)
        runCurrent()

        assertEquals(false, proxy.row("app.a"))
        assertEquals(true, proxy.row("app.b"))
    }

    private fun proxy(): TestProxy {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(syncServiceClientModule)
        return proxy
    }

    private fun TestProxy.service(): SyncService {
        val executor = LocalTaskExecutor(executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = tContext,
        ).getService(SyncService.ID)
    }

    private fun TestProxy.row(writer: String): Boolean? = catchUpRow(writer)
}
