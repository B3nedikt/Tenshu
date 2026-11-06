package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.content.ContentDeclarations
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.sync.PushBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⭐ CR-2 B3d, the emit half (`content-plane-cr2-b3-wire-spec.md` §3.1/§5): a standalone entity block of
 * a content-referencing type carries `contentEdgesOf(payload)` — the **complete** set, `none` included —
 * on its own push, and every other block carries none.
 *
 * Driven through `initSyncService`, the real observer, the real drain and the real `PushBlock`
 * implementation, over a relay that records what each push carried. The expected edges are computed
 * with the B3a declaration from the row the test saved, so the assertion is "what the payload names is
 * what goes on the wire", not a list restated here.
 */
class EdgesOnPushTest {

    private class Rig(
        val proxy: TestProxy,
        val relay: MockSyncRelay,
        val components: SyncProxyComponents,
        val syncManager: SyncManagerImpl,
    ) {
        val registry get() = proxy.executionContext.schemaProvider as SchemaRegistry

        fun edgesOf(row: org.tenshu.core.Data): List<ContentEdge> = ContentDeclarations.of(registry).edgesOf(row)!!

        fun pendingBlocks(): List<SyncBlock> = proxy.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = proxy.tContext,
                    type = SyncBlock.ID,
                    relationId = SyncService.Collections.PENDING_BLOCKS,
                ),
                isEntity = false,
                elementSchemaId = SyncBlock.ID,
            )
        )?.toList()?.filterIsInstance<SyncBlock>().orEmpty()

        /** Every pushed block that carried edges, with them. */
        fun pushedWithEdges(): List<Pair<SyncBlock, List<ContentEdge>>> =
            relay.pushedBlocks().map { it to relay.pushedEdgesOf(it.blockId.get()).orEmpty() }.filter { it.second.isNotEmpty() }
    }

    private fun rig(): Rig {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply {
                registerConfig(syncServiceCommonModule)
                registerConfig(fileServiceCommonModule)
            },
        )
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        ).apply { initialize("mock phrase") }
        val relay = MockSyncRelay(proxy.tContext)
        // ⚠ The REAL `PushBlock`: reading the edge sidecar at push time is part of what is under test.
        proxy.additionalImplementations[PushBlock.ID] =
            requireNotNull(syncProxyImplementationFactory(PushBlock.ID, relay, syncManager, crypto))
        val components = proxy.executionContext.initSyncService(syncManager, crypto, startBackgroundLoops = false, platformScheduler = NoOpPlatformScheduler)
        return Rig(proxy, relay, components, syncManager)
    }

    private fun Rig.file(path: String, contentId: String?): Entity<File> =
        Entity.create<File>(context = proxy.tContext, composite = File.create(proxy.tContext, path)) {
            if (contentId != null) set(File.ContentId, contentId)
        }

    @Test
    fun aFileSavePushesTheCompleteEdgeSetItsPayloadNames() = runTest {
        val r = rig()
        val row = r.file("/a.jpg", "cid-1")

        r.proxy.executionContext.saveData(row)
        r.components.syncObserver.awaitEmitIdle()
        r.components.scheduler.drainNow()

        val expected = r.edgesOf(row)
        assertEquals(6, expected.size, "precondition: File declares its content and five derived rungs")
        assertTrue(expected.any { it.contentId == "cid-1" && !it.regenerable }, "precondition: the primary edge")
        val (block, edges) = r.pushedWithEdges().single()
        // ⚠ Under keyed property tags (B3 wire spec §11.1): the schema ids never reach the relay.
        assertEquals(
            ContentPropertyTagger(r.syncManager).wireEdges(block, expected),
            edges,
            "the wire carries exactly contentEdgesOf(payload), tagged",
        )
        assertTrue(edges.none { it.propertyId.startsWith("tenshu/") }, "no schema property id on the wire: $edges")
        assertTrue(block.targetEntityIdHash != null, "only a standalone block — the holder is its target")
    }

    @Test
    fun aClearingSavePushesNoneForEveryProperty_notAnEmptySet() = runTest {
        // ⚠⚠ G1's "removals are explicit": a row whose content went away still names every property, with
        // none. An empty set would leave the relay's previous registers standing for ever.
        val r = rig()
        val row = r.file("/a.jpg", contentId = null)

        r.proxy.executionContext.saveData(row)
        r.components.syncObserver.awaitEmitIdle()
        r.components.scheduler.drainNow()

        val edges = r.pushedWithEdges().single().second
        assertEquals(6, edges.size)
        assertTrue(edges.all { it.contentId == null }, "every property named, as none: $edges")
    }

    @Test
    fun theEdgesComeFromThePayloadInHand_notFromTheStore() = runTest {
        // ⚠⚠ §10.11's as-built warning: the block is built later than the save, on the emit consumer, and
        // the stored row may already be a later write. Edges read off the store would name its content.
        val r = rig()
        val saved = r.file("/a.jpg", "cid-1")

        r.proxy.executionContext.saveData(saved)
        // A later write lands in the store before the consumer has built the first save's block.
        r.proxy.executionContext.database.saveData(r.file("/a.jpg", "cid-LATER"))
        r.components.syncObserver.awaitEmitIdle()
        r.components.scheduler.drainNow()

        val primary = r.pushedWithEdges().single().second.single { !it.regenerable }
        assertEquals("cid-1", primary.contentId, "the payload's content, never the store's")
    }

    @Test
    fun aTypeThatReferencesNoContentPushesNoEdges() = runTest {
        val r = rig()
        r.proxy.executionContext.saveData(SyntheticTestData.Factory.create(r.proxy.tContext, "k", 1L))
        r.components.syncObserver.awaitEmitIdle()
        r.components.scheduler.drainNow()

        assertTrue(r.relay.pushedBlocks().isNotEmpty(), "precondition: something was pushed")
        assertTrue(r.pushedWithEdges().isEmpty(), "no edges for a type with no content-bearing property")
    }

    @Test
    fun theEdgesAreDurableUntilThePush_andGoneAfterIt() = runTest {
        // The sidecar is written before the block is queued, so a push after a restart still names them,
        // and it is dropped with the block once the push lands.
        val r = rig()
        r.proxy.executionContext.saveData(r.file("/a.jpg", "cid-1"))
        r.components.syncObserver.awaitEmitIdle()

        val queued = r.pendingBlocks().single { block ->
            PendingBlockEdgeStore(r.proxy.executionContext).edgesOf(block.blockId.get()).isNotEmpty()
        }
        val blockId = queued.blockId.get()
        // "After a restart": a fresh store over the same database.
        assertEquals(6, PendingBlockEdgeStore(r.proxy.executionContext).edgesOf(blockId).size)

        r.components.scheduler.drainNow()

        assertEquals(6, r.relay.pushedEdgesOf(blockId)?.size)
        assertTrue(
            PendingBlockEdgeStore(r.proxy.executionContext).edgesOf(blockId).isEmpty(),
            "a retired block's edges are gone with it",
        )
    }
}
