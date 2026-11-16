package org.tenshu.sync.proxy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SYNC_CHANGES_PAGE_LIMIT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GR-7's caught-up gate as the scheduler feeds it (`RealmRegistry.personalPulls`, `RealmRuntime.pulls`): which
 * ticks count as a complete pull. [PullProgressTest] pins the counting; this pins where it is fed from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PullProgressWiringTest {

    private suspend fun CatchUpFixture.tick(scope: TestScope) {
        scheduler.tickOnce()
        scope.runCurrent()
    }

    @Test
    fun onTheTwoLaneFallback_anAnsweredForwardPullIsComplete() = runTest {
        // No `/sync/log` in the graph: there is no backward walk, so the backfill cursor is never set.
        val f = CatchUpFixture.create(this, wireMergedLane = false)
        f.syncManager.realmRelayRegistered = true
        val seen = f.components.realmRegistry.personalPulls.observe()

        f.tick(this)

        assertTrue(f.components.realmRegistry.personalPulls.completeSince(seen), "the forward lanes are the whole stream")
    }

    @Test
    fun withABackwardWalkStillOpen_theForwardPullAloneIsNotComplete() = runTest {
        val f = CatchUpFixture.create(this)
        f.syncManager.realmRelayRegistered = true
        f.syncManager.backfillCursor = 400 // a walk a previous run left part-way, down to 0
        f.syncManager.backfillTarget = 0
        f.backward += CatchUpFixture.Answer.Page(blocks = f.junkBlocks(2), nextCursor = 300, hasMore = true) // part-way down
        val seen = f.components.realmRegistry.personalPulls.observe()

        f.tick(this)

        assertFalse(f.components.realmRegistry.personalPulls.completeSince(seen), "history below the live tail is outstanding")
    }

    @Test
    fun aSharedRealmPull_isCompleteOnlyWhenItLeftNothingBehind() = runTest {
        val f = CatchUpFixture.create(this)
        f.syncManager.realmRelayRegistered = true
        f.registerSharedRealm(ArrayDeque(listOf(f.junkBlocks(SYNC_CHANGES_PAGE_LIMIT), emptyList())))
        val runtime = f.components.realmRegistry.sharedRuntimesWithRelay().single()
        val seen = runtime.pulls.observe()

        f.tick(this) // a full page: more is waiting
        assertFalse(runtime.pulls.completeSince(seen), "a full page means a backlog")

        f.tick(this) // the rest: an empty page
        assertTrue(runtime.pulls.completeSince(seen))
    }

    @Test
    fun aStuckSpell_isTimedByThisDevicesOwnClock_fromItsFirstLook_andEndsOnClear() = runTest {
        val f = CatchUpFixture.create(this)
        f.registerSharedRealm(ArrayDeque())
        val registry = f.components.realmRegistry
        val realmId = registry.sharedRuntimesWithRelay().single().context.ownerId

        assertEquals(0L, registry.stuckForMillis(realmId, nowMillis = 5_000L), "no spell before the first look")
        assertFalse(registry.caughtUpSinceStuck(realmId, nowMillis = 10_000L), "the first look only observes")
        assertEquals(3_600_000L, registry.stuckForMillis(realmId, nowMillis = 3_610_000L))

        registry.clearStuck(realmId)
        assertEquals(0L, registry.stuckForMillis(realmId, nowMillis = 9_000_000L), "a cleared spell restarts")
    }
}
