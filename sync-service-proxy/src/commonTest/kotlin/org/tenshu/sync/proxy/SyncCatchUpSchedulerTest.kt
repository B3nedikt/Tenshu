package org.tenshu.sync.proxy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.common.SYNC_CHANGES_PAGE_LIMIT
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.FirstPullMarker
import org.tenshu.sync.common.SyncGate
import org.tenshu.sync.proxy.CatchUpFixture.Answer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scheduler half of the catch-up signal (`ios-sync-activity-indicator.md` §3.1): when a real
 * [SyncScheduler] raises it, when it settles it, and what it writes to the first-pull marker.
 *
 * Three observation points, because a raise and its settle usually share one tick:
 *  - **at the request** ([CatchUpFixture.onForwardRequest]) — was it raised *before* the pull?
 *  - **in the tick, after every pull** (a sync-cycle hook) — did a page of rows raise it?
 *  - **after the tick** — did it settle?
 *
 * ⚠ A device that never raises never writes its row at all, so `row() == null` after a tick is
 * the proof that no raise happened in it, however briefly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCatchUpSchedulerTest {

    // ---- the evidence table ------------------------------------------------------------------

    @Test
    fun aCaughtUpDeviceWithNothingToPull_neverRaises() = runTest {
        val f = caughtUp()
        val inTick = f.observeInTick()

        repeat(3) { f.tick(this) }

        assertEquals(listOf<Boolean?>(null, null, null), inTick)
        assertNull(f.row(), "nothing was ever raised, so the row was never written")
    }

    @Test
    fun aLockedRealm_neverRaises() = runTest {
        val f = CatchUpFixture.create(this, enabled = false)

        f.tick(this)

        assertNull(f.row())
        assertNull(f.syncManager.firstPullMarker, "a locked realm starts no pull")
    }

    @Test
    fun aDeviceWhosePullIsNotOpen_neverRaises_thoughTheBootstrapWouldFetch() = runTest {
        // Never funded, never registered: local-only. The slice bootstrap still runs its GET, and
        // by the marker alone this device "never pulled" — raising here would say "Syncing…" on
        // every launch of a device that syncs nothing.
        val store = RecordingBlobStore()
        val f = CatchUpFixture.create(
            this,
            contentBlobStore = store::build,
            withSliceProducer = true,
            storageGate = SyncGate { false },
        )
        store.onGet = { store.rowAtGet += f.row() }

        f.tick(this)

        assertEquals(listOf<Boolean?>(null), store.rowAtGet, "the bootstrap did fetch, unannounced")
        assertNull(f.row())
        assertNull(f.syncManager.firstPullMarker)
    }

    @Test
    fun aNeverPulledDevice_showsFromBeforeItsFirstRequest_untilThatPullCompletes_andNeverAfter() = runTest {
        val f = CatchUpFixture.create(this)
        f.syncManager.realmRelayRegistered = true
        val atRequest = mutableListOf<Pair<FirstPullMarker?, Boolean?>>()
        f.onForwardRequest = { atRequest += f.syncManager.firstPullMarker to f.row() }

        f.tick(this) // an EMPTY realm: the relay answers, with nothing

        assertEquals(
            listOf<Pair<FirstPullMarker?, Boolean?>>(FirstPullMarker.STARTED to true),
            atRequest,
            "STARTED is written, and the row reads true, before the request is sent",
        )
        assertEquals(FirstPullMarker.COMPLETED, f.syncManager.firstPullMarker, "an answered empty page completes it")
        assertEquals(false, f.row())

        f.tick(this)

        assertEquals(FirstPullMarker.COMPLETED to false, atRequest.last(), "never on a later pull")
    }

    @Test
    fun anUnfinishedPersistedBackfill_showsFromBeforeTheRequest_andWhileItWalks() = runTest {
        val f = caughtUp()
        f.syncManager.midBackfill()
        val atRequest = mutableListOf<Boolean?>()
        f.onForwardRequest = { atRequest += f.row() }
        f.backward += Answer.Page(blocks = f.junkBlocks(2), nextCursor = 300, hasMore = true)
        f.backward += Answer.Page(blocks = f.junkBlocks(2), nextCursor = 200, hasMore = false)

        f.tick(this)
        assertEquals(listOf<Boolean?>(true), atRequest)
        assertEquals(true, f.row(), "still walking: the flag holds across the tick end")

        f.tick(this)
        assertEquals(false, f.row(), "the walk reached the bottom")
    }

    @Test
    fun aFailedBackfillPage_reportsNoBacklog() = runTest {
        // What `backfillOnce` returns when its page fails was "not determined" in the note; it
        // must be false, or a stalled request would hold the flag for ever.
        val f = caughtUp()
        f.syncManager.midBackfill()
        f.backward += Answer.Fails

        f.tick(this)

        assertEquals(false, f.row())
        assertEquals(400L, f.syncManager.backfillCursor, "and the walk is where it was")
    }

    @Test
    fun aJoiningDevice_showsFromBeforeTheBootstrapFetch() = runTest {
        val store = RecordingBlobStore()
        val f = CatchUpFixture.create(this, contentBlobStore = store::build, withSliceProducer = true)
        f.syncManager.realmRelayRegistered = true
        store.onGet = { store.rowAtGet += f.row() }

        f.tick(this)

        assertEquals(listOf<Boolean?>(true), store.rowAtGet)
    }

    @Test
    fun aCompletedFirstPullWithAnUnfinishedBackfill_showsFromBeforeTheBootstrap_afterARestart() = runTest {
        // After a merged-lane handover the marker reads COMPLETED while history is outstanding.
        // The bootstrap's own guard does not skip (the cursor is not 0), so it fetches.
        val store = RecordingBlobStore()
        val f = CatchUpFixture.create(this, contentBlobStore = store::build, withSliceProducer = true)
        f.syncManager.realmRelayRegistered = true
        f.syncManager.firstPullMarker = FirstPullMarker.COMPLETED
        f.syncManager.midBackfill()
        store.onGet = { store.rowAtGet += f.row() }

        f.tick(this)

        assertEquals(listOf<Boolean?>(true), store.rowAtGet)
    }

    @Test
    fun aCompletedDevice_neverShowsForTheBootstrap_evenOnARelayWithoutTheMergedLane() = runTest {
        // Without the merged lane no backfill cursor is ever stored, so the bootstrap is attempted
        // in every process. A device that finished its first pull must not announce it.
        val store = RecordingBlobStore()
        val f = CatchUpFixture.create(
            this,
            wireMergedLane = false,
            contentBlobStore = store::build,
            withSliceProducer = true,
        )
        f.syncManager.realmRelayRegistered = true
        f.syncManager.firstPullMarker = FirstPullMarker.COMPLETED
        store.onGet = { store.rowAtGet += f.row() }

        f.tick(this)

        assertEquals(listOf<Boolean?>(null), store.rowAtGet, "it fetched, and said nothing")
        assertNull(f.row())
    }

    // ---- raise rule 3: one test per apply path -----------------------------------------------

    @Test
    fun rowsOnTheMergedLog_raise() = runTest {
        val f = caughtUp()
        val inTick = f.observeInTick()
        f.forward += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 1_001)

        f.tick(this)

        assertEquals(listOf<Boolean?>(true), inTick)
        assertEquals(false, f.row(), "one page, no more: settled at the tick end")
    }

    @Test
    fun tombstonesAloneOnTheMergedLog_raise() = runTest {
        val f = caughtUp()
        val inTick = f.observeInTick()
        f.forward += Answer.Page(tombstones = f.junkTombstones(1), nextCursor = 1_001)

        f.tick(this)

        assertEquals(listOf<Boolean?>(true), inTick)
    }

    @Test
    fun rowsOnTheBackfill_raise() = runTest {
        // A never-walked floor is not evidence, so nothing is raised before the request; the
        // page of rows is what raises it.
        val f = caughtUp(backfillComplete = false)
        val atRequest = mutableListOf<Boolean?>()
        f.onForwardRequest = { atRequest += f.row() }
        val inTick = f.observeInTick()
        f.backward += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 900, hasMore = false)

        f.tick(this)

        assertEquals(listOf<Boolean?>(null), atRequest)
        assertEquals(listOf<Boolean?>(true), inTick)
        assertEquals(false, f.row())
    }

    @Test
    fun rowsOnTheFallbackBlockLane_raise() = runTest {
        val f = caughtUp(wireMergedLane = false)
        val inTick = f.observeInTick()
        f.blockLane += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 1_001)

        f.tick(this)

        assertEquals(listOf<Boolean?>(true), inTick)
        assertEquals(false, f.row())
    }

    @Test
    fun rowsOnTheFallbackTombstoneLane_raise() = runTest {
        val f = caughtUp(wireMergedLane = false)
        val inTick = f.observeInTick()
        f.tombstoneLane += Answer.Page(tombstones = f.junkTombstones(1), nextCursor = 1_001)

        f.tick(this)

        assertEquals(listOf<Boolean?>(true), inTick)
        assertEquals(false, f.row())
    }

    @Test
    fun rowsOnTheFallback_afterTheRelayRefusesTheMergedRoute_raise() = runTest {
        val f = caughtUp(relaySupportsMergedLane = false)
        val inTick = f.observeInTick()
        f.blockLane += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 1_001)

        f.tick(this)

        assertEquals(listOf<Boolean?>(true), inTick)
    }

    @Test
    fun rowsFromASharedRealm_raise() = runTest {
        val f = caughtUp()
        val inTick = f.observeInTick()
        f.registerSharedRealm(ArrayDeque(listOf(f.junkBlocks(1))))

        f.tick(this)

        assertEquals(listOf<Boolean?>(true), inTick)
        assertEquals(false, f.row())
    }

    // ---- settling ----------------------------------------------------------------------------

    @Test
    fun aMultiPagePersonalCatchUp_holdsSteady_withNoClearBetweenTicks() = runTest {
        val f = caughtUp()
        repeat(9) { f.forward += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 2_000L + it, hasMore = true) }
        f.forward += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 3_000)

        repeat(9) { page ->
            f.tick(this)
            assertEquals(true, f.row(), "page ${page + 1} of 10 reported more")
        }
        f.tick(this)

        assertEquals(false, f.row())
    }

    @Test
    fun aMultiPageSharedRealmCatchUp_holdsSteady_withNoClearBetweenTicks() = runTest {
        // The personal realm is idle throughout. Without the shared half of the verdict the flag
        // would clear at every tick end and be raised again by the next page.
        val f = caughtUp()
        val full = SYNC_CHANGES_PAGE_LIMIT
        f.registerSharedRealm(
            ArrayDeque(listOf(f.junkBlocks(full), f.junkBlocks(full), f.junkBlocks(full), f.junkBlocks(3))),
        )

        repeat(3) { page ->
            f.tick(this)
            assertTrue(f.components.catchUp.catchingUp, "full shared page ${page + 1}: a backlog remains")
            assertEquals(true, f.row())
        }
        f.tick(this)

        assertEquals(false, f.row(), "a short page ends it")
    }

    @Test
    fun aFailedPage_settles_andLeavesTheFirstPullStarted() = runTest {
        val f = CatchUpFixture.create(this)
        f.syncManager.realmRelayRegistered = true
        val atRequest = mutableListOf<Boolean?>()
        f.onForwardRequest = { atRequest += f.row() }
        f.forward += Answer.Fails

        f.tick(this)

        assertEquals(listOf<Boolean?>(true), atRequest)
        assertEquals(false, f.row(), "a request that failed reports no backlog")
        assertEquals(FirstPullMarker.STARTED, f.syncManager.firstPullMarker, "a failure is not an answer")

        f.tick(this) // the relay is back, and the realm is empty

        assertEquals(listOf<Boolean?>(true, true), atRequest, "and it shows again until the pull completes")
        assertEquals(FirstPullMarker.COMPLETED, f.syncManager.firstPullMarker)
    }

    @Test
    fun aTickWhoseApplyThrows_settles_andLeavesTheFirstPullStarted() = runTest {
        val f = CatchUpFixture.create(this)
        f.syncManager.realmRelayRegistered = true
        f.forward += Answer.Page(blocks = listOf(f.realBlock()), nextCursor = 10)
        f.proxy.executionContext.addDataObserver(object : DataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin) {
                if (origin == WriteOrigin.SYNC_INGEST) throw IllegalStateException("scripted: apply fails")
            }
            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit
        })

        assertFailsWith<IllegalStateException> { f.scheduler.tickOnce() }
        runCurrent()

        assertFalse(f.components.catchUp.catchingUp)
        assertEquals(false, f.row())
        assertEquals(FirstPullMarker.STARTED, f.syncManager.firstPullMarker)
        assertEquals(0L, f.syncManager.lastSyncTimestamp, "and no cursor moved past the page that threw")
    }

    @Test
    fun aCancelledBootstrap_settles() = runTest {
        val store = RecordingBlobStore()
        val f = CatchUpFixture.create(this, contentBlobStore = store::build, withSliceProducer = true)
        f.syncManager.realmRelayRegistered = true
        store.onGet = { awaitCancellation() }

        val tick = launch { f.scheduler.tickOnce() }
        runCurrent()
        assertEquals(true, f.row(), "raised, and parked inside the slice fetch")

        tick.cancelAndJoin()
        runCurrent()

        assertFalse(f.components.catchUp.catchingUp)
        assertEquals(false, f.row())
    }

    @Test
    fun stop_clears() = runTest {
        val f = caughtUp()
        f.forward += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 2_000, hasMore = true)
        f.tick(this)
        assertEquals(true, f.row())

        f.scheduler.stop()
        runCurrent()

        assertEquals(false, f.row())
    }

    @Test
    fun stopRacingATickThatHasRecordedABacklog_endsFalse() = runTest {
        // The tick is not even cancelled here: it is past its forward pull, holding a backlog
        // verdict, parked on the shared realm's request. `stop()` returns; the tick runs on and
        // reaches its `finally` afterwards. That late verdict must not land.
        val f = caughtUp()
        f.forward += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 2_000, hasMore = true)
        var parked = false
        f.registerSharedRealm(ArrayDeque(), onRequest = {
            parked = true
            kotlinx.coroutines.delay(10_000)
        })

        val tick = launch { f.scheduler.tickOnce() }
        runCurrent()
        assertTrue(parked)
        assertEquals(true, f.row())

        f.scheduler.stop()
        advanceUntilIdle()
        tick.join()

        assertFalse(f.components.catchUp.catchingUp, "the tick's verdict arrived after the stop and was refused")
        assertEquals(false, f.row())
    }

    @Test
    fun aRealmThatLocksMidCatchUp_clears() = runTest {
        val f = CatchUpFixture.create(this, enabled = false)
        f.components.catchUp.raise()
        assertEquals(true, f.row())

        f.tick(this)

        assertEquals(false, f.row(), "the disabled branch delivers false with its health report")
    }

    // ---- the first-pull marker, row by row ---------------------------------------------------

    @Test
    fun aPageThatReportsMore_leavesTheFirstPullStarted() = runTest {
        val f = CatchUpFixture.create(this)
        f.syncManager.realmRelayRegistered = true
        f.forward += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 500, hasMore = true)

        f.tick(this)

        assertEquals(FirstPullMarker.STARTED, f.syncManager.firstPullMarker)
        assertEquals(true, f.row())
    }

    @Test
    fun aTruncatedPageOnTheFallback_leavesTheFirstPullStarted() = runTest {
        val f = CatchUpFixture.create(this, wireMergedLane = false)
        f.syncManager.realmRelayRegistered = true
        f.blockLane += Answer.Page(blocks = f.junkBlocks(SYNC_CHANGES_PAGE_LIMIT), nextCursor = 500)

        f.tick(this)

        assertEquals(FirstPullMarker.STARTED, f.syncManager.firstPullMarker)
        assertEquals(true, f.row(), "a full page means the lane has more")

        f.tick(this) // both lanes answer, short

        assertEquals(FirstPullMarker.COMPLETED, f.syncManager.firstPullMarker)
    }

    @Test
    fun aBlockPageThatArrivedBesideAFailedTombstonePage_leavesTheFirstPullStarted() = runTest {
        val f = CatchUpFixture.create(this, wireMergedLane = false)
        f.syncManager.realmRelayRegistered = true
        f.blockLane += Answer.Page(blocks = f.junkBlocks(1), nextCursor = 500)
        f.tombstoneLane += Answer.Fails

        f.tick(this)

        assertEquals(500L, f.syncManager.lastSyncTimestamp, "the block lane did advance")
        assertEquals(
            FirstPullMarker.STARTED, f.syncManager.firstPullMarker,
            "one lane answering is not the pull finishing",
        )
    }

    @Test
    fun aKillAfterACursorWasPersisted_stillShowsOnTheNextLaunch() = runTest {
        // STARTED with a cursor above zero: the previous process got a page in and died before the
        // backlog was through. The cursor alone would read as "has pulled before".
        val f = CatchUpFixture.create(this)
        f.syncManager.realmRelayRegistered = true
        f.syncManager.firstPullMarker = FirstPullMarker.STARTED
        f.syncManager.lastSyncTimestamp = 700
        f.syncManager.lastTombstoneSyncTimestamp = 700
        val atRequest = mutableListOf<Boolean?>()
        f.onForwardRequest = { atRequest += f.row() }

        f.tick(this)

        assertEquals(listOf<Boolean?>(true), atRequest)
    }

    @Test
    fun noMarkerWithACursorAboveZero_isAnUpgradedDevice_completedWithoutShowing() = runTest {
        val f = CatchUpFixture.create(this)
        f.syncManager.realmRelayRegistered = true
        f.syncManager.lastSyncTimestamp = 700
        f.syncManager.lastTombstoneSyncTimestamp = 700
        f.syncManager.backfillCursor = 0
        val atRequest = mutableListOf<Pair<FirstPullMarker?, Boolean?>>()
        f.onForwardRequest = { atRequest += f.syncManager.firstPullMarker to f.row() }

        f.tick(this)

        assertEquals(listOf<Pair<FirstPullMarker?, Boolean?>>(FirstPullMarker.COMPLETED to null), atRequest)
        assertNull(f.row())
    }

    // ---- a realm replacement ------------------------------------------------------------------

    @Test
    fun aDeviceRePairedToAnotherRealm_showsFromBeforeThatRealmsFirstRequest_evenIfItIsEmpty() = runTest {
        // Realm A, pulled for real: one page takes both cursors up and the marker to COMPLETED.
        val f = CatchUpFixture.create(this)
        f.syncManager.realmRelayRegistered = true
        f.forward += Answer.Page(blocks = f.junkBlocks(2), nextCursor = 1_000)
        f.tick(this)
        f.syncManager.backfillCursor = 0
        f.syncManager.backfillTarget = 500
        assertEquals(FirstPullMarker.COMPLETED, f.syncManager.firstPullMarker)
        assertEquals(1_000, f.syncManager.lastSyncTimestamp)
        assertEquals(1_000, f.syncManager.lastTombstoneSyncTimestamp)

        // What `installReceivedPairingPayload` does once the adopted owner differs.
        f.syncManager.discardSupersededRealm()
        f.syncManager.joinedFundedRealm = true

        assertNull(f.syncManager.firstPullMarker, "realm B has not been pulled")
        assertEquals(0, f.syncManager.lastSyncTimestamp)
        assertEquals(0, f.syncManager.lastTombstoneSyncTimestamp, "a position in A's log reads as 'pulled before'")
        assertNull(f.syncManager.backfillCursor, "A's finished walk says nothing about B's history")
        assertNull(f.syncManager.backfillTarget)

        val atRequest = mutableListOf<Pair<FirstPullMarker?, Boolean?>>()
        f.onForwardRequest = { atRequest += f.syncManager.firstPullMarker to f.row() }

        f.tick(this) // realm B is EMPTY: only the pre-request raise can show anything

        assertEquals(
            listOf<Pair<FirstPullMarker?, Boolean?>>(FirstPullMarker.STARTED to true),
            atRequest,
            "the first pull of the new realm is announced before its request",
        )
        assertEquals(FirstPullMarker.COMPLETED, f.syncManager.firstPullMarker)
        assertEquals(false, f.row())
    }

    @Test
    fun forgettingThisDevice_leavesNoPullProgress_onAStoreThatWasNotWiped() = runTest {
        val f = caughtUp()
        f.syncManager.midBackfill()

        f.syncManager.forgetThisDevice()

        assertNull(f.syncManager.firstPullMarker)
        assertEquals(0, f.syncManager.lastSyncTimestamp)
        assertEquals(0, f.syncManager.lastTombstoneSyncTimestamp)
        assertNull(f.syncManager.backfillCursor)
        assertNull(f.syncManager.backfillTarget)
    }

    // ---- at the relation ---------------------------------------------------------------------

    @Test
    fun onePage_withASlowApply_readsTrueWhileItApplies_andFalseAfter() = runTest {
        // Raise, apply and settle share one tick. A collector over a conflated flow would only
        // ever see the final false; the row must read true DURING the apply.
        val f = caughtUp()
        f.forward += Answer.Page(blocks = listOf(f.realBlock()), nextCursor = 1_001)
        val duringApply = mutableListOf<Boolean?>()
        f.proxy.executionContext.addDataObserver(object : DataObserver {
            override fun onDataSaved(data: Data, origin: WriteOrigin) {
                if (origin == WriteOrigin.SYNC_INGEST) duringApply += f.row()
            }
            override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit
        })

        f.tick(this)

        assertTrue(duringApply.isNotEmpty(), "the page was applied")
        assertTrue(duringApply.all { it == true }, "and the row read true at every save: $duringApply")
        assertEquals(false, f.row())
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** A device that has pulled before, on a registered realm, with nothing outstanding. */
    private suspend fun TestScope.caughtUp(
        wireMergedLane: Boolean = true,
        relaySupportsMergedLane: Boolean = true,
        backfillComplete: Boolean = true,
    ): CatchUpFixture {
        val f = CatchUpFixture.create(
            this,
            wireMergedLane = wireMergedLane,
            relaySupportsMergedLane = relaySupportsMergedLane,
        )
        f.syncManager.realmRelayRegistered = true
        f.syncManager.firstPullMarker = FirstPullMarker.COMPLETED
        f.syncManager.lastSyncTimestamp = 1_000
        f.syncManager.lastTombstoneSyncTimestamp = 1_000
        if (backfillComplete) f.syncManager.backfillCursor = 0
        return f
    }

    /** A backward walk a previous run left part-way: floor 400, walking down to 0. */
    private fun SyncManagerImpl.midBackfill() {
        backfillCursor = 400
        backfillTarget = 0
    }

    /** One tick, then let the publisher's collector write whatever the tick settled. */
    private suspend fun CatchUpFixture.tick(scope: TestScope) {
        scheduler.tickOnce()
        scope.runCurrent()
    }

    /** Records the row once per tick, after every pull has run and before the tick settles. */
    private fun CatchUpFixture.observeInTick(): List<Boolean?> {
        val seen = mutableListOf<Boolean?>()
        scheduler.addSyncCycleHook { seen += row() }
        return seen
    }

    /** A content plane that holds nothing, and reports each GET so a test can look around. */
    private class RecordingBlobStore {
        var onGet: suspend () -> Unit = {}
        val rowAtGet = mutableListOf<Boolean?>()

        fun build(context: TContext): ContentBlobStore {
            val empty = InMemoryContentBlobStore(context)
            return object : ContentBlobStore by empty {
                override suspend fun getChunk(ref: String): TResult<TByteArray> {
                    onGet()
                    return empty.getChunk(ref)
                }
            }
        }
    }
}
