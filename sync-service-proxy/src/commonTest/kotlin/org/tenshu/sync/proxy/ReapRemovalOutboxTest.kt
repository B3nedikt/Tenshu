package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.util.toStamp
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.ReapRemoval
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⭐ CR-2 B3d, reaps on the wire (`content-plane-cr2-b3-wire-spec.md` §3.3/§5): every reap enqueues a
 * durable `PendingReapRemoval (wire name, T)` in the same transaction as its watermark, and the drain
 * sends them to `POST /sync/content/reap` — at most 1,000 per request, `hlc` as an `HlcStamp` string —
 * retrying until the relay answers 2xx.
 *
 * ⚠⚠ A reap happens once. A removal lost before it reached the relay is an edge that stands for ever,
 * so every assertion here is about the outbox keeping what the relay has not accepted.
 */
class ReapRemovalOutboxTest {

    private class Rig(
        val proxy: TestProxy,
        val syncManager: SyncManagerImpl,
        val sink: InMemoryContentBlobStore,
        val components: SyncProxyComponents,
    ) {
        val outbox get() = ReapRemovalOutbox(proxy.executionContext)
        fun fileAt(path: String) = DataId.StringDataId.create(proxy.tContext, "tenshu/file/system/file", path)
        fun nameOf(path: String) = WireIdResolver(syncManager).of(fileAt(path))
        fun hlc(physical: Long) = HybridLogicalClock.create(proxy.tContext, physical, 0L, "device-t")
    }

    private fun rig(store: ((InMemoryContentBlobStore) -> ContentBlobStore)? = null): Rig {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) })
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        ).apply { initialize("mock phrase") }
        val sink = InMemoryContentBlobStore(proxy.tContext)
        val components = proxy.executionContext.initSyncService(
            syncManager, crypto, startBackgroundLoops = false, contentBlobStore = store?.invoke(sink) ?: sink,
            platformScheduler = NoOpPlatformScheduler,
        )
        return Rig(proxy, syncManager, sink, components)
    }

    @Test
    fun aReapOwesTheRelayItsRemovalAtT() = runTest {
        val r = rig()
        val t = r.hlc(1_000)

        r.components.tombstoneRegistry.recordReap(r.fileAt("/f/a.jpg"), t)

        assertEquals(listOf(r.nameOf("/f/a.jpg") to t.toStamp()), r.outbox.pending().map { it.holder to it.stamp })
    }

    @Test
    fun theDrainSendsEachRemovalAsAnHlcStamp_andForgetsWhatTheRelayAccepted() = runTest {
        val r = rig()
        val t = r.hlc(1_000)
        r.components.tombstoneRegistry.recordReap(r.fileAt("/f/a.jpg"), t)

        r.components.scheduler.drainReapRemovals()

        assertEquals(
            listOf(listOf(ReapRemoval(holder = r.nameOf("/f/a.jpg"), hlc = t.toStamp().encode()))),
            r.sink.reapedRemovals,
        )
        assertTrue(r.outbox.pending().isEmpty(), "accepted, so no longer owed")
    }

    @Test
    fun aRefusedDrainKeepsEveryRemoval_andTheNextDrainRetriesIt() = runTest {
        val r = rig()
        r.components.tombstoneRegistry.recordReap(r.fileAt("/f/a.jpg"), r.hlc(1_000))
        r.sink.failReaps = true

        r.components.scheduler.drainReapRemovals()
        assertEquals(1, r.outbox.pending().size, "refused, so still owed")

        r.sink.failReaps = false
        r.components.scheduler.drainReapRemovals()

        assertEquals(1, r.sink.reapedRemovals.flatten().size)
        assertTrue(r.outbox.pending().isEmpty())
    }

    @Test
    fun aLargeSubtreeGoesOutInBatchesOfAtMostOneThousand() = runTest {
        val r = rig()
        (1..2_500).forEach { r.components.tombstoneRegistry.recordReap(r.fileAt("/big/$it.jpg"), r.hlc(1_000)) }

        r.components.scheduler.drainReapRemovals()

        assertTrue(r.sink.reapedRemovals.all { it.size <= ContentBlobStore.MAX_REAP_BATCH }, "${r.sink.reapedRemovals.map { it.size }}")
        assertEquals(2_500, r.sink.reapedRemovals.flatten().map { it.holder }.toSet().size)
        assertTrue(r.outbox.pending().isEmpty())
    }

    @Test
    fun aLaterReapReplacesTheOwedRemoval_andAnOlderOneDoesNot() = runTest {
        val r = rig()
        val holder = r.fileAt("/f/a.jpg")
        r.components.tombstoneRegistry.recordReap(holder, r.hlc(1_000))
        r.components.tombstoneRegistry.recordReap(holder, r.hlc(2_000))
        r.components.tombstoneRegistry.recordReap(holder, r.hlc(1_500))

        assertEquals(listOf(r.hlc(2_000).toStamp()), r.outbox.pending().map { it.stamp })
    }

    @Test
    fun aReapReplacedWhileItsRequestWasInFlight_staysOwed() = runTest {
        // ⚠⚠ The drain must not delete a removal the relay has not seen: a later reap of the same holder
        // landing while the earlier one is on the wire replaces the row, and that one is still owed.
        lateinit var reg: TombstoneRegistry
        lateinit var holder: DataId
        lateinit var later: HybridLogicalClock
        val r = rig { sink ->
            object : ContentBlobStore by sink {
                override suspend fun reapRemovals(removals: List<ReapRemoval>): Boolean {
                    reg.recordReap(holder, later)
                    return sink.reapRemovals(removals)
                }
            }
        }
        reg = r.components.tombstoneRegistry
        holder = r.fileAt("/f/a.jpg")
        later = r.hlc(2_000)
        reg.recordReap(holder, r.hlc(1_000))

        r.components.scheduler.drainReapRemovals()

        assertEquals(listOf(r.hlc(2_000).toStamp()), r.outbox.pending().map { it.stamp }, "the newer T is still owed")
    }

    @Test
    fun aRecursiveDeletesOwnCascadeIsOwedToo() = runTest {
        // The deleter's own descendants ride its tombstone's cascade, and are ALSO recorded as reaps at the
        // same T (`SyncObserver.enqueueTombstone` → `recordReapPoint`): redundant, idempotent at the relay.
        val r = rig()

        r.components.tombstoneEmitter.emitWithCascade(r.fileAt("/f"), "tenshu/file/system/file", listOf(r.fileAt("/f/a.jpg")))

        assertEquals(listOf(r.nameOf("/f/a.jpg")), r.outbox.pending().map { it.holder })
    }

    @Test
    fun theOutboxIsDurable_soARestartDoesNotLoseARemoval() = runTest {
        val r = rig()
        r.components.tombstoneRegistry.recordReap(r.fileAt("/f/a.jpg"), r.hlc(1_000))

        // "After a restart": a fresh outbox over the same store, nothing in memory.
        assertEquals(1, ReapRemovalOutbox(r.proxy.executionContext).pending().size)
    }
}
