package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.number.TInteger
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.GetSyncLog
import org.tenshu.sync.GetSyncLogBefore
import org.tenshu.sync.PushBlock
import org.tenshu.sync.SyncLogResponse
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Newest-first catch-up (`sync-device-catch-up.md` C2 / §5.4) — the client half.
 *
 * The whole design is two cursors walking toward each other: `lastSyncTimestamp` up from where the
 * device left off, `backfillCursor` down from the relay's tip. What has to be true, and is tested
 * here, is that the walk **starts at the tip**, **only ever moves down**, and **terminates** — once
 * by meeting the forward cursor and once by exhausting the log. A walk that failed to terminate
 * would re-request the same page every tick forever; one that moved the wrong way would skip
 * history silently.
 */
class BackfillCatchUpTest {

    private class Fixture(
        val proxy: TestProxy,
        val scheduler: SyncScheduler,
        val syncManager: SyncManagerImpl,
        val backfillAsks: () -> List<Long>,
        val forwardAsks: () -> List<Long>,
    )

    /**
     * @param pages what the relay answers for each successive backward ask, in order. Each entry is
     *   `(nextCursor, hasMore)`; the walk consumes one per tick.
     */
    private suspend fun fixture(
        scope: CoroutineScope,
        pages: List<Pair<Long, Boolean>>,
    ): Fixture {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = scope,
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val backfillAsks = mutableListOf<Long>()
        val forwardAsks = mutableListOf<Long>()

        proxy.additionalImplementations[PushBlock.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
        }
        proxy.additionalImplementations[GetSyncLog.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                forwardAsks += (params?.get(GetSyncLog.Params.SINCE_TIMESTAMP) as TInteger).get()
                @Suppress("UNCHECKED_CAST")
                return SyncLogResponse.create(
                    context = executionContext.context,
                    blocks = emptyList(),
                    tombstones = emptyList(),
                    peerTips = emptyList(),
                    // The forward lane is idle in these tests — this is about the backward one.
                    nextCursor = 0L,
                    hasMore = false,
                ) as T
            }
        }
        proxy.additionalImplementations[GetSyncLogBefore.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                val before = (params?.get(GetSyncLogBefore.Params.BEFORE_SEQ) as TInteger).get()
                backfillAsks += before
                val (cursor, hasMore) = pages.getOrElse(backfillAsks.size - 1) { 0L to false }
                @Suppress("UNCHECKED_CAST")
                return SyncLogResponse.create(
                    context = executionContext.context,
                    blocks = emptyList(),
                    tombstones = emptyList(),
                    peerTips = emptyList(),
                    nextCursor = cursor,
                    hasMore = hasMore,
                ) as T
            }
        }

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("mock phrase")
        syncManager.currentRealmContext()?.deviceId?.takeIf { it.isNotEmpty() }?.let { deviceId ->
            proxy.executionContext.saveData(
                DeviceSchemaVersion.create(
                    context = proxy.tContext,
                    deviceId = deviceId,
                    versions = proxy.schemaRegistry.currentVersionVector(proxy.tContext),
                ),
                WriteOrigin.SYNC_INGEST,
            )
        }

        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            // Stepped by hand via `tickOnce`, so the loops are never started rather than started
            // and then stopped: a loop that never starts has no first tick to race. Why "start,
            // then `stopAndAwait`" was not enough is in `SyncHealthReportingTest.fixture`; here
            // the startup tick walked the backfill before the test set `realmRelayRegistered`
            // (forced pair 3/3 red → 3/3 green, 2026-09-03).
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )
        return Fixture(
            proxy = proxy,
            scheduler = components.scheduler,
            syncManager = syncManager,
            backfillAsks = { backfillAsks.toList() },
            forwardAsks = { forwardAsks.toList() },
        )
    }

    @Test
    fun theFirstWalkStartsAtTheTip() = runTest {
        // ⚠ A fresh device has NO backfill cursor, and that is not the same as a floor of 0 — it
        // means "start from the newest thing there is". Getting this wrong is the difference between
        // recent-state-first and replaying July all over again.
        val f = fixture(this, pages = listOf(9_000L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            assertNull(f.syncManager.backfillCursor, "a fresh device has never walked")

            f.scheduler.tickOnce()

            assertEquals(listOf(Long.MAX_VALUE), f.backfillAsks(), "the first ask is the tip")
            assertEquals(9_000L, f.syncManager.backfillCursor)
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun aWalkStartedUnderTheTwoPositionBuildIsMigrated() = runTest {
        // ⚠⚠ THE MIGRATION. A device that began backfilling under the two-position build has a
        // non-null floor and NO target. A `floor == null` handover test skips it forever: it keeps
        // descending while its forward pass crawls — which is precisely the symptom this fix
        // exists to remove. Observed on the real iPhone (floor 748 414, never promoted).
        //
        // The target's ABSENCE is the marker of the old scheme, so no version flag is needed.
        val f = fixture(this, pages = listOf(747_900L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            f.syncManager.lastSyncTimestamp = 104_891L
            f.syncManager.lastTombstoneSyncTimestamp = 104_891L
            f.syncManager.backfillCursor = 748_414L   // mid-walk, old build
            assertNull(f.syncManager.backfillTarget, "the old scheme records no target")

            f.scheduler.tickOnce()

            assertEquals(
                747_900L, f.syncManager.lastSyncTimestamp,
                "an in-progress walk must still promote its live tail — otherwise the devices this " +
                    "fix is for are the ones it never reaches"
            )
            assertEquals(104_891L, f.syncManager.backfillTarget, "and the crawl position becomes the target")
            assertEquals(747_900L, f.syncManager.backfillCursor, "the walk continues downward")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun anEmptyPageAtHandoverNeverJumpsTheLiveTail() = runTest {
        // ⚠⚠ THE SEVERE ONE. An empty backward page reports `nextCursor == beforeSeq` by contract
        // ("the cursor stays where you asked"), and at handover `beforeSeq` is `Long.MAX_VALUE`.
        // An earlier draft adopted that as the live tail, which set the forward cursor past every
        // seq the relay will ever mint — the device declared itself caught up with the end of time
        // and went **permanently deaf**. `HomeDeviceControlSystemTest` caught it as a convergence
        // timeout; without that system test it would have shipped and bricked every device.
        val f = fixture(this, pages = listOf(Long.MAX_VALUE to false))
        try {
            f.syncManager.realmRelayRegistered = true
            f.syncManager.lastSyncTimestamp = 500L
            f.syncManager.lastTombstoneSyncTimestamp = 500L

            f.scheduler.tickOnce()

            assertEquals(
                500L, f.syncManager.lastSyncTimestamp,
                "an empty page must NOT move the live tail — adopting Long.MAX_VALUE would deafen " +
                    "the device forever"
            )
            assertEquals(0L, f.syncManager.backfillCursor, "and the walk retires: there is nothing below")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun handoverPromotesTheForwardPassToALiveTail() = runTest {
        // ⚠⚠ THE FIX. Without it C2 was half a fix: the backward walk descends and never returns,
        // so NEW writes — which land at the tip, above everything it already passed — were
        // reachable only by the forward crawl. Measured on a real iPhone: a 643 523-seq gap at
        // 7.5 seq/s ⇒ ~24 h before an upload from another device became visible.
        val f = fixture(this, pages = listOf(700_000L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            f.syncManager.lastSyncTimestamp = 104_891L // far behind, as the real device was
            f.syncManager.lastTombstoneSyncTimestamp = 104_891L

            f.scheduler.tickOnce()

            assertEquals(
                700_000L, f.syncManager.lastSyncTimestamp,
                "the live tail must jump to the top of the first backward page, so the very next " +
                    "forward pull asks from near the tip instead of crawling up from 104 891"
            )
            assertEquals(104_891L, f.syncManager.backfillTarget, "and the old position becomes the walk's target")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun aReopenedWalkKeepsItsOriginalTarget() = runTest {
        // ⚠⚠ THE REGRESSION IN THE REPAIR ITSELF (2026-08-14). `SyncManager.reopenBackfill`
        // clears the floor to null, and `handover` fires on `floor == null` — so a re-opened walk
        // landed in the handover branch and RE-CAPTURED its target from the forward cursor. On any
        // device that had already handed over once that cursor is a live tail near the tip, so the
        // repair reset the stopping point to almost the top of the log and the re-walk covered
        // nearly nothing.
        //
        // Measured on the maintainer's iPhone: the repair moved `backfillTarget` 500 → 19 661, the
        // walk re-ran over `(19 661, tip]`, found nothing and re-latched — leaving unread exactly
        // the range it had been re-opened to cover.
        val f = fixture(this, pages = listOf(700_000L to true, 650_000L to true, 600_000L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            f.syncManager.lastSyncTimestamp = 104_891L
            f.syncManager.lastTombstoneSyncTimestamp = 104_891L

            f.scheduler.tickOnce() // handover: target := 104 891, live tail jumps to 700 000
            assertEquals(104_891L, f.syncManager.backfillTarget)
            assertEquals(700_000L, f.syncManager.lastSyncTimestamp)

            // The device latched complete (under the old inferred rule); the repair re-opens it.
            f.syncManager.backfillCursor = 0L
            f.syncManager.reopenBackfill()
            assertNull(f.syncManager.backfillCursor, "re-opened: the walk starts again from the tip")

            f.scheduler.tickOnce()

            assertEquals(
                104_891L, f.syncManager.backfillTarget,
                "the ORIGINAL boundary must survive the re-open — it is where the forward pass " +
                    "genuinely read to. Re-deriving it from a cursor that has since jumped to the " +
                    "tip does not observe that boundary, it destroys it, and the re-walk then " +
                    "covers almost nothing",
            )
            assertEquals(
                700_000L, f.syncManager.lastSyncTimestamp,
                "and the live tail must not be dragged BACKWARDS by a page that merely sits above " +
                    "the preserved target",
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun theWalkTerminatesAgainstTheTargetNotTheLiveTail() = runTest {
        // ⚠⚠ The bug this design invites: after the jump, `lastSyncTimestamp` sits ABOVE the whole
        // unread range. Comparing the walk to it would complete it on page ONE and silently
        // abandon every seq below — the device would look caught up while missing its history.
        val f = fixture(this, pages = listOf(700_000L to true, 600_000L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            f.syncManager.lastSyncTimestamp = 104_891L
            f.syncManager.lastTombstoneSyncTimestamp = 104_891L

            f.scheduler.tickOnce()
            assertEquals(
                700_000L, f.syncManager.backfillCursor,
                "the walk must CONTINUE after handover, not complete against its own live tail"
            )

            f.scheduler.tickOnce()
            assertEquals(600_000L, f.syncManager.backfillCursor, "and keep descending")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun newWritesReachTheDeviceOnTheTickAfterHandover() = runTest {
        // The user-visible property: an upload made on another device shows up promptly. The
        // forward pull's `since` is the observable — after handover it must be near the tip.
        val f = fixture(this, pages = listOf(700_000L to true, 600_000L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            f.syncManager.lastSyncTimestamp = 104_891L
            f.syncManager.lastTombstoneSyncTimestamp = 104_891L

            f.scheduler.tickOnce()
            f.scheduler.tickOnce()

            assertEquals(
                listOf(104_891L, 700_000L), f.forwardAsks(),
                "tick 1 asks from the old position; tick 2 tails from the tip"
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun aCaughtUpDeviceIsNotDraggedBackwards() = runTest {
        // ⚠ The live tail must only ever move FORWARD. A device already past the first page's floor
        // has read everything below it; moving its cursor down would re-read history, and the walk
        // has nothing left to do.
        val f = fixture(this, pages = listOf(500_000L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            f.syncManager.lastSyncTimestamp = 749_000L // already at the tip
            f.syncManager.lastTombstoneSyncTimestamp = 749_000L

            f.scheduler.tickOnce()

            assertEquals(749_000L, f.syncManager.lastSyncTimestamp, "no backward jump")
            assertEquals(0L, f.syncManager.backfillCursor, "and the walk completes — nothing to fill")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun theFloorOnlyEverMovesDown() = runTest {
        val f = fixture(this, pages = listOf(9_000L to true, 8_000L to true, 7_000L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce()
            f.scheduler.tickOnce()
            f.scheduler.tickOnce()

            assertEquals(listOf(Long.MAX_VALUE, 9_000L, 8_000L), f.backfillAsks())
            assertEquals(7_000L, f.syncManager.backfillCursor)
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun exhaustingTheLogCompletesTheWalkPermanently() = runTest {
        // Termination signal #1. `hasMore == false` means there is nothing below; the walk must
        // stop for good, not re-ask from the same floor on every subsequent tick forever.
        val f = fixture(this, pages = listOf(5_000L to false))
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce()
            assertEquals(0L, f.syncManager.backfillCursor, "0 means complete")

            f.scheduler.tickOnce()
            f.scheduler.tickOnce()
            assertEquals(1, f.backfillAsks().size, "a completed walk never asks again")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun meetingTheForwardCursorCompletesTheWalk() = runTest {
        // ⚠⚠ Termination signal #2, and the one that makes the two-cursor design work: the passes
        // walk toward each other and stop when they MEET. Without this the backward walk would keep
        // going to seq 0, re-reading everything the forward pass had already applied.
        val f = fixture(this, pages = listOf(4_000L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            f.syncManager.lastSyncTimestamp = 4_500L // the forward pass got this far
            f.syncManager.lastTombstoneSyncTimestamp = 4_500L

            f.scheduler.tickOnce()

            assertEquals(
                0L, f.syncManager.backfillCursor,
                "a floor at or below the forward cursor means every seq is covered by one side or " +
                    "the other — there is nothing left to walk"
            )
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun aRelayThatMakesNoProgressKeepsTheWalkOpen() = runTest {
        // ⚠⚠ **This assertion is the reverse of what it used to be, deliberately.** It read
        // "no progress ⇒ stop, do not spin", justified as *"the forward pass still covers the
        // history, slowly"*. That justification is false for exactly the rows this walk exists to
        // fetch: the forward pass starts where the device joined and moves UP, so anything below
        // its start is reachable only backward. Latching on no-progress therefore did not trade
        // speed for safety — it abandoned pre-join history permanently, and silently, because `0`
        // is a one-way latch.
        //
        // Measured on a real iPhone 2026-08-13: `backfillCursor = 0`, `backfillTarget = 500`, and
        // 98 rows that existed on the Android peer had never arrived and never would.
        //
        // The spin the old comment feared is real but small: one request per tick, now with a WARN
        // naming the condition. A bounded, observable retry beats silent data loss.
        val f = fixture(this, pages = listOf(9_000L to true, 9_000L to true, 9_000L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            f.scheduler.tickOnce() // floor -> 9000
            f.scheduler.tickOnce() // relay answers 9000 again: no progress

            assertEquals(
                9_000L, f.syncManager.backfillCursor,
                "a page that did not descend is not evidence of the bottom — the floor holds",
            )
            f.scheduler.tickOnce()
            assertEquals(3, f.backfillAsks().size, "and the walk keeps asking rather than retiring")
        } finally {
            f.scheduler.stop()
        }
    }

    @Test
    fun theForwardPassIsUnaffected() = runTest {
        // The blast-radius check. C2 adds a pass; it must not perturb the one that already works.
        val f = fixture(this, pages = listOf(9_000L to true))
        try {
            f.syncManager.realmRelayRegistered = true
            f.syncManager.lastSyncTimestamp = 100L
            f.syncManager.lastTombstoneSyncTimestamp = 100L

            f.scheduler.tickOnce()

            assertEquals(listOf(100L), f.forwardAsks(), "forward still asks from its own cursor")
            assertTrue(f.backfillAsks().isNotEmpty(), "and backfill ran alongside it")
        } finally {
            f.scheduler.stop()
        }
    }
}
