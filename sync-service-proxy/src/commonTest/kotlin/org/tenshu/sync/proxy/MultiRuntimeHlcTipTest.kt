package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.HlcWatermark
import org.tenshu.sync.common.HlcWatermarkStore
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bug this whole track exists for, at the level it actually occurs
 * (`sync-hlc-tip-device-identity.md` §1).
 *
 * A device that runs **more than one runtime under one deviceId** — iOS route-1's per-app
 * runtimes, or two web tabs of one origin — used to publish a *non-monotone* sequence into the
 * relay's single `(ownerId, deviceId)` tip slot, because each runtime published its own
 * process clock. Observed live 2026-08-28: an iPhone tip of 15:07 followed by one of 14:51, a
 * 16-minute rewind, which pinned the desktop tray's divergence warning for ~50 minutes while sync
 * was healthy.
 *
 * ⚠⚠ The assertion is on the **local clock after a tick**, not on a mock relay's slot, and that is
 * deliberate: `mergedHlc()` seeds the clock from the merged watermark, so the clock *is* what the
 * runtime would sign and publish. Asserting the clock tests the merge; asserting a mock's slot
 * would mostly test the mock.
 *
 * ⚠ Delete `hlcWatermarkStore` from the schedulers below and both tests must fail. A multi-runtime
 * test that passes against the pre-TC-2 code is testing nothing.
 */
class MultiRuntimeHlcTipTest {

    /** One runtime: its own proxy, its own SyncManager, its own clock — over a shared watermark. */
    private class Runtime(
        val proxy: TestProxy,
        val syncManager: SyncManagerImpl,
        val scheduler: SyncScheduler,
    )

    /** [pulled]: the store has pulled its realm's history (B.22.6), as every established device's has. */
    private fun runtime(
        scope: CoroutineScope,
        watermark: HlcWatermarkStore?,
        pulled: Boolean = true,
        store: org.tenshu.core.common.proxy.db.ObjectStore = org.tenshu.core.common.proxy.db.InMemoryObjectStore(),
    ): Runtime {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = scope)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
            objectStore = store,
        )
        val scheduler = SyncScheduler(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            syncProcessor = SyncProcessor(executionContext = proxy.executionContext, syncManager = syncManager, cryptoManager = crypto, wireIdResolver = WireIdResolver(syncManager)),
            cryptoManager = crypto,
            coroutineScope = scope,
            hlcWatermarkStore = watermark,
        )
        syncManager.initialize("mock phrase")
        if (pulled) syncManager.firstPullMarker = org.tenshu.sync.common.FirstPullMarker.COMPLETED
        return Runtime(proxy, syncManager, scheduler)
    }

    @Test
    fun aLaggingRuntimeAdoptsTheDevicePositionRatherThanPublishingItsOwn() = runTest {
        val watermark = SecureStorageDelegateHlcWatermarkStore(InMemorySecureStorageDelegate())

        // Runtime A has been ingesting: its clock is at "now".
        val a = runtime(this, watermark)
        val advanced = a.syncManager.hlcManager.tick().physicalTime.get()
        assertTrue(advanced > 0L, "precondition: A's clock advanced")
        a.scheduler.tickOnce() // publishes A's position into the shared watermark

        // Runtime B is the idle sibling — a freshly launched app, clock still at zero.
        val b = runtime(this, watermark)
        assertEquals(
            0L,
            b.syncManager.hlcManager.current().physicalTime.get(),
            "precondition: B starts from an all-zero clock, as every process does",
        )

        b.scheduler.tickOnce()

        assertTrue(
            b.syncManager.hlcManager.current().physicalTime.get() >= advanced,
            "B must publish the device's position, not its own — it is the backward step from " +
                "B's own clock that peers read as a forked view",
        )
    }

    @Test
    fun aRestartedRuntimeStopsPublishingTheZeroTipThatZeroesTheGcQuorum() = runTest {
        // C3, and the half that bites every platform including desktop and Android. The published
        // tip is also the relay's tombstone-GC quorum input (`MailboxStore.acknowledgementSet`
        // takes `tip.physicalTime >= tombstone.hlc.physicalTime` for *every* device). A restarted
        // process used to publish `(0,0)` — the empty-tip guard stops it *warning*, but the relay
        // still records it, so that device's watermark resets until it ingests or writes again.
        // An idle-but-online client never does either, and can hold GC for the realm indefinitely.
        val delegate = InMemorySecureStorageDelegate()
        val watermark = SecureStorageDelegateHlcWatermarkStore(delegate)
        watermark.raiseTo(HlcWatermark(1_787_922_439_939L, 3L)) // what this device saw before

        val restarted = runtime(this, watermark)
        assertEquals(
            0L,
            restarted.syncManager.hlcManager.current().physicalTime.get(),
            "precondition: a fresh process is never seeded from the store",
        )

        restarted.scheduler.tickOnce()

        assertEquals(
            1_787_922_439_939L,
            restarted.syncManager.hlcManager.current().physicalTime.get(),
            "the durable watermark is adopted before the first tip leaves the process",
        )
    }

    @Test
    fun withoutAWatermarkStoreTheClockStaysProcessLocal() = runTest {
        // The negative control, and the reason the two tests above are not vacuous: with no store
        // wired the pre-TC-2 behaviour is intact, so a fresh runtime keeps its zero clock. If this
        // test ever fails, something else is seeding the clock and the assertions above are
        // measuring that instead of the merge.
        val fresh = runtime(this, watermark = null)
        fresh.scheduler.tickOnce()
        assertEquals(0L, fresh.syncManager.hlcManager.current().physicalTime.get())
    }

    /**
     * ⚠⚠ B.22.6 (`ios-shared-store-sqlite.md`): a store that has not pulled its realm's history
     * neither adopts nor raises the durable watermark. On iOS the watermark is a file beside the
     * store, and one an older-build process made again after the store was reset holds a stamp the
     * new store never observed: published, it would forge the tombstone-GC ack (T-T-08).
     */
    @Test
    fun aStoreStillPullingItsHistoryNeitherAdoptsNorRaisesTheWatermark() = runTest {
        listOf<Pair<String, (SyncManagerImpl) -> Unit>>(
            "never pulled" to { _ -> },
            "first pull started" to { m -> m.firstPullMarker = org.tenshu.sync.common.FirstPullMarker.STARTED },
            "backward walk mid-way, merged lane walked" to { m ->
                m.firstPullMarker = org.tenshu.sync.common.FirstPullMarker.COMPLETED
                m.backfillCursor = 4_000L
            },
            // ⚠ Fix round 2 (4): an unknown marker value with a moved cursor is not an upgraded store.
            "an unknown marker" to { m -> m.lastSyncTimestamp = 500L },
        ).forEach { (state, arrange) ->
            val watermark = SecureStorageDelegateHlcWatermarkStore(InMemorySecureStorageDelegate())
            watermark.raiseTo(HlcWatermark(4_102_444_800_000L, 3L)) // the old store's stamp
            val store = org.tenshu.core.common.proxy.db.InMemoryObjectStore()
            val fresh = runtime(this, watermark, pulled = false, store = store)
            fresh.scheduler.mergedLaneAvailable = { true }
            arrange(fresh.syncManager)
            if (state == "an unknown marker") store.storeFirstPullMarker(99L)
            assertTrue(!fresh.scheduler.storeHistoryPulled(), state)
            val own = fresh.syncManager.hlcManager.tick().physicalTime.get()
            fresh.scheduler.tickOnce()
            assertTrue(
                fresh.syncManager.hlcManager.current().physicalTime.get() < 4_102_444_800_000L,
                "$state: ⚠⚠ the old stamp is not adopted, so it is not published",
            )
            assertTrue(own < 4_102_444_800_000L, "precondition: the made-up stamp is in the future of this run")
            assertEquals(HlcWatermark(4_102_444_800_000L, 3L), watermark.read(), "$state: nor raised")
        }
        // Once the history is pulled, the watermark is the device's position again.
        val watermark = SecureStorageDelegateHlcWatermarkStore(InMemorySecureStorageDelegate())
        watermark.raiseTo(HlcWatermark(4_102_444_800_000L, 3L))
        val done = runtime(this, watermark, pulled = true)
        assertTrue(done.scheduler.storeHistoryPulled())
        done.scheduler.tickOnce()
        assertEquals(4_102_444_800_000L, done.syncManager.hlcManager.current().physicalTime.get())
    }

    /**
     * ⚠⚠ Fix round 2 (2): a backward-walk floor left by a relay that has since lost the merged lane is
     * never advanced again. Counted as unfinished, it would hold the watermark (and the realm's
     * tombstone GC) off for ever; [raiseBeforePull]'s rule counts it only while the lane is walked.
     */
    @Test
    fun aBackfillFloorLeftByALostMergedLaneDoesNotHoldTheWatermarkForEver() = runTest {
        val watermark = SecureStorageDelegateHlcWatermarkStore(InMemorySecureStorageDelegate())
        watermark.raiseTo(HlcWatermark(4_102_444_800_000L, 3L))
        val device = runtime(this, watermark, pulled = true)
        device.syncManager.backfillCursor = 4_000L // left over, never advanced again
        device.scheduler.mergedLaneAvailable = { false }
        assertTrue(device.scheduler.storeHistoryPulled(), "the floor is not walked here, so it is not pending")
        device.scheduler.tickOnce()
        assertEquals(4_102_444_800_000L, device.syncManager.hlcManager.current().physicalTime.get(), "the watermark is the device's position again")
        device.scheduler.mergedLaneAvailable = { true }
        assertTrue(!device.scheduler.storeHistoryPulled(), "while the lane is walked, the same floor is pending")
    }
}
