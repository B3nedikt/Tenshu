package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncDelta
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⭐ `content-plane-cr2-quorumless.md` §10.12 Q7: "content-bearing" is decided per row, by the
 * payload — and by the row it replaces. A save that **names** content, or that **clears** content
 * the stored row named, is refused its lane and pushed standalone with its edges; only a
 * none-over-none save may stay inside a `DeltaBatchPayload`, where the relay sees no edge at all.
 *
 * ⚠⚠ The clear is the case a per-payload rule alone gets wrong: a batched clear
 * leaves the old edge standing for ever. The decision is made on the saving thread before the write,
 * because after it the replaced row is gone.
 */
class LaneContentStagingTest {

    private lateinit var proxy: TestProxy
    private lateinit var observer: SyncObserver

    @BeforeTest
    fun setup() {
        proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply {
                registerConfig(syncServiceCommonModule)
                registerConfig(fileServiceCommonModule)
            },
        )
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("mock phrase") }
        observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
        )
        proxy.executionContext.observerRegistry.add(observer)
    }

    private fun file(path: String, contentId: String?): Entity<File> =
        Entity.create<File>(context = proxy.tContext, composite = File.create(proxy.tContext, path)) {
            if (contentId != null) set(File.ContentId, contentId)
        }

    private fun <T> array(relationId: String, type: String): List<T> =
        @Suppress("UNCHECKED_CAST")
        (proxy.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(context = proxy.tContext, type = type, relationId = relationId),
                isEntity = false,
                elementSchemaId = type,
            )
        )?.toList().orEmpty() as List<T>)

    private fun pendingBlocks(): List<SyncBlock> = array(SyncService.Collections.PENDING_BLOCKS, SyncBlock.ID)
    private fun journal(): List<SyncDelta> = array(SyncService.Collections.PENDING_LANE_DELTAS, SyncDelta.ID)

    /** The standalone blocks that carry edges — the ones a content-bearing save produces. */
    private fun blocksWithEdges() = pendingBlocks().filter {
        PendingBlockEdgeStore(proxy.executionContext).edgesOf(it.blockId.get()).isNotEmpty()
    }

    @Test
    fun aSaveThatNamesContentIsNotStaged() = runTest {
        observer.openSyncLane("lane")
        proxy.executionContext.saveData(file("/a.jpg", "cid-1"), WriteOrigin.USER, syncLaneId = "lane")
        observer.awaitEmitIdle()

        assertTrue(journal().isEmpty(), "nothing of it may sit in the lane: ${journal().size} deltas")
        val edges = PendingBlockEdgeStore(proxy.executionContext).edgesOf(blocksWithEdges().single().blockId.get())
        assertTrue(edges.any { it.contentId == "cid-1" }, "standalone, with the content it names: $edges")
        observer.closeSyncLane("lane")
    }

    @Test
    fun aSaveThatClearsContentTheStoredRowNamed_isNotStaged() = runTest {
        // The stored row names cid-1 (an ordinary, unlaned save) …
        proxy.executionContext.saveData(file("/a.jpg", "cid-1"))
        observer.awaitEmitIdle()
        val before = blocksWithEdges().size

        // … and the laned save replacing it names none.
        observer.openSyncLane("lane")
        proxy.executionContext.saveData(file("/a.jpg", contentId = null), WriteOrigin.USER, syncLaneId = "lane")
        observer.awaitEmitIdle()

        assertTrue(journal().isEmpty(), "a clear must not hide inside a batch")
        val clear = blocksWithEdges().drop(before).single()
        val edges = PendingBlockEdgeStore(proxy.executionContext).edgesOf(clear.blockId.get())
        assertTrue(edges.isNotEmpty() && edges.all { it.contentId == null }, "the clear goes out as none: $edges")
        observer.closeSyncLane("lane")
    }

    @Test
    fun aSaveNamingNoneOverARowNamingNone_isStagedAsBefore() = runTest {
        observer.openSyncLane("lane")
        proxy.executionContext.saveData(file("/a.jpg", contentId = null), WriteOrigin.USER, syncLaneId = "lane")
        observer.awaitEmitIdle()

        assertTrue(journal().isNotEmpty(), "nothing a batch could hide, so the lane keeps it")
        assertTrue(blocksWithEdges().isEmpty())
        observer.closeSyncLane("lane")
    }

    @Test
    fun aTypeThatReferencesNoContentIsStagedAsBefore() = runTest {
        observer.openSyncLane("lane")
        proxy.executionContext.saveData(
            Entity.create<SyntheticTestData>(
                context = proxy.tContext,
                composite = SyntheticTestData.Factory.create(proxy.tContext, "k", 1L),
                properties = emptyMap(),
            ),
            WriteOrigin.USER,
            syncLaneId = "lane",
        )
        observer.awaitEmitIdle()

        assertEquals(1, journal().size, "staged into the lane journal")
        observer.closeSyncLane("lane")
        assertEquals(1, pendingBlocks().size, "and sealed as one batch")
        assertEquals(null, pendingBlocks().single().targetEntityIdHash?.get(), "a batch has no target")
    }
}
