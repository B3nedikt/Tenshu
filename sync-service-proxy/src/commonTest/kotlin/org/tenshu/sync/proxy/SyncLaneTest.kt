package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.DeltaBatchPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncDelta
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TS-2 sender-lane tests (`core-background-processing.md` §10.3): lane-tagged
 * saves stage into a `DeltaBatchPayload` instead of per-save blocks —
 * coalescing last-wins, checkpoint caps, the durable journal (D6), crash
 * replay, and the block-count collapse the lane exists for. Everything runs
 * through the real `ExecutionContext.saveData(data, origin, syncLaneId)` →
 * `LaneAwareDataObserver` → emit-consumer path.
 */
class SyncLaneTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var observer: SyncObserver

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = testProxy.tContext.syncCryptoManager()
        syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate()
        )
        syncManager.initialize("mock phrase")

        observer = SyncObserver(
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
        )
        testProxy.executionContext.observerRegistry.add(observer)
    }

    // ---- helpers -----------------------------------------------------------

    private fun entity(key: String, value: Long): Entity<SyntheticTestData> = Entity.create(
        context = testProxy.tContext,
        composite = SyntheticTestData.Factory.create(testProxy.tContext, key, value),
        properties = emptyMap()
    )

    private fun save(key: String, value: Long, laneId: String?) =
        testProxy.executionContext.saveData(entity(key, value), WriteOrigin.USER, syncLaneId = laneId)

    private fun arrayRef(relationId: String, elementType: String) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = testProxy.tContext,
            type = elementType,
            relationId = relationId
        ),
        isEntity = false,
        elementSchemaId = elementType
    )

    private fun pendingBlocks(): List<SyncBlock> =
        testProxy.executionContext.database
            .getArrayData(arrayRef(SyncService.Collections.PENDING_BLOCKS, SyncBlock.ID))
            ?.toList()?.filterIsInstance<SyncBlock>() ?: emptyList()

    private fun journalDeltas(): List<SyncDelta> =
        testProxy.executionContext.database
            .getArrayData(arrayRef(SyncService.Collections.PENDING_LANE_DELTAS, SyncDelta.ID))
            ?.toList()?.filterIsInstance<SyncDelta>() ?: emptyList()

    private fun decryptBatch(block: SyncBlock): DeltaBatchPayload {
        val crypto = testProxy.tContext.syncCryptoManager()
        val plain = EnvelopeAuth.openSyncBlock(
            crypto,
            block,
            syncManager.getDerivedKeys()!!.sharedIdentityKey
        )!!
        val parser = BinaryParser(
            testProxy.executionContext.schemaProvider,
            testProxy.tContext.dataFactory()
        )
        return parser.parse(BlockPayloadEnvelope.open(plain)!!.payloadBytes)
    }

    // ---- tests -------------------------------------------------------------

    @Test
    fun emittedBlocksCarryTheFullVersionVectorStamp() = runTest {
        observer.openSyncLane("lane-stamp")
        save("stamp-a", 1L, laneId = "lane-stamp")
        observer.awaitEmitIdle()
        observer.closeSyncLane("lane-stamp")

        val block = pendingBlocks().single()
        val crypto = testProxy.tContext.syncCryptoManager()
        val plain = EnvelopeAuth.openSyncBlock(
            crypto,
            block,
            syncManager.getDerivedKeys()!!.sharedIdentityKey
        )!!
        val opened = BlockPayloadEnvelope.open(plain)!!
        assertEquals(TENSHU_STORAGE_FORMAT_VERSION, opened.formatVersion)

        val parser = BinaryParser(
            testProxy.executionContext.schemaProvider,
            testProxy.tContext.dataFactory()
        )
        val stamp = parser.parse<VersionVector>(opened.stampBytes)
        val services = stamp.toMap()
        assertTrue(
            services.containsKey(SyncService.ID),
            "the stamp must carry the registered sync service, got: ${services.keys}"
        )
    }

    @Test
    fun laneSaves_stage_thenCheckpointSealsOneBatchBlock() = runTest {
        observer.openSyncLane("lane-1")
        save("a", 1L, laneId = "lane-1")
        save("b", 2L, laneId = "lane-1")
        save("c", 3L, laneId = "lane-1")
        observer.awaitEmitIdle()

        assertEquals(0, pendingBlocks().size, "staged saves must not emit per-save blocks")
        assertEquals(3, journalDeltas().size, "each staged delta must be journaled durably (D6)")

        observer.checkpointSyncLane("lane-1")

        val blocks = pendingBlocks()
        assertEquals(1, blocks.size, "one checkpoint == one batch block")
        assertEquals(null, blocks.single().targetEntityIdHash, "D2: containers carry no wire routing hash")
        val batch = decryptBatch(blocks.single())
        assertEquals(3, batch.deltas.toList().size)
        assertEquals(0, journalDeltas().size, "checkpoint must clear the journal")
    }

    @Test
    fun repeatedSavesOfOneEntity_coalesceLastWins() = runTest {
        observer.openSyncLane("lane-1")
        repeat(10) { save("hot", it.toLong(), laneId = "lane-1") }
        observer.awaitEmitIdle()
        observer.closeSyncLane("lane-1")

        val batch = decryptBatch(pendingBlocks().single())
        val deltas = batch.deltas.toList()
        assertEquals(1, deltas.size, "n saves of one entity must coalesce to one delta")
        val survivor = deltas.single().value as Entity<*>
        assertEquals(
            9L,
            survivor.getFieldValue<org.tenshu.core.number.TInteger>(SyntheticTestData.Companion.Field.VALUE).get(),
            "the LAST save must win the coalesce"
        )
    }

    @Test
    fun bulkSaves_collapseToCeilOfCapBlocks() = runTest {
        observer.openSyncLane("lane-1")
        // 1_200 distinct entities → auto-checkpoints at the 500-delta cap:
        // 2 full blocks while staging + 1 remainder on close = ceil(1200/500).
        repeat(1_200) { save("bulk-$it", it.toLong(), laneId = "lane-1") }
        observer.awaitEmitIdle()
        observer.closeSyncLane("lane-1")

        val blocks = pendingBlocks()
        assertEquals(3, blocks.size, "N saves must collapse to ceil(N/500) batch blocks")
        val total = blocks.sumOf { decryptBatch(it).deltas.toList().size }
        assertEquals(1_200, total, "every save must be represented exactly once")
        assertEquals(0, journalDeltas().size)
    }

    @Test
    fun organicSavesInterleaved_keepThePerSavePath() = runTest {
        observer.openSyncLane("lane-1")
        save("laned", 1L, laneId = "lane-1")
        save("organic", 2L, laneId = null)
        observer.awaitEmitIdle()

        val blocks = pendingBlocks()
        assertEquals(1, blocks.size, "the organic save must emit immediately, the laned one must not")
        observer.closeSyncLane("lane-1")
        assertEquals(2, pendingBlocks().size)
    }

    @Test
    fun crashBeforeCheckpoint_journalReplaysAsFinalBatch() = runTest {
        observer.openSyncLane("lane-1")
        save("x", 1L, laneId = "lane-1")
        save("y", 2L, laneId = "lane-1")
        observer.awaitEmitIdle()
        assertEquals(0, pendingBlocks().size)
        assertEquals(2, journalDeltas().size)

        // "Crash": a fresh observer over the same store (lane memory gone).
        val revived = SyncObserver(
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
        )
        revived.replaySyncLaneJournal()

        val blocks = pendingBlocks()
        assertEquals(1, blocks.size, "lingering journal deltas must seal as a final batch (D6)")
        assertEquals(2, decryptBatch(blocks.single()).deltas.toList().size)
        assertEquals(0, journalDeltas().size, "replay must clear the journal")
    }

    @Test
    fun sealedLaneBlock_roundTripsThroughSyncProcessorIngest() = runTest {
        observer.openSyncLane("lane-1")
        save("rt", 42L, laneId = "lane-1")
        observer.awaitEmitIdle()
        observer.closeSyncLane("lane-1")
        val block = pendingBlocks().single()

        // Fresh receiver-side proxy: ingest the sealed block, entity appears.
        val receiver = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        receiver.schemaRegistry.registerConfig(syncServiceCommonModule)
        val receiverManager = SyncManagerImpl(
            context = receiver.tContext,
            cryptoManager = receiver.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate()
        )
        // Same phrase ⇒ same derived keys, so the receiver can decrypt.
        receiverManager.initialize("mock phrase")
        val processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = receiver.executionContext,
            syncManager = receiverManager,
            cryptoManager = receiver.tContext.syncCryptoManager()
        )
        // Re-create the block in the receiver's context from serialized wire bytes.
        val wire = org.tenshu.core.common.parser.BinarySerializer().serialize(block)
        val parsed = BinaryParser(receiver.executionContext.schemaProvider, receiver.tContext.dataFactory())
            .parse<SyncBlock>(wire)
        processor.processSyncBlock(parsed)

        val dataId = receiver.executionContext.getDataId(
            Entity.create<SyntheticTestData>(
                context = receiver.tContext,
                composite = SyntheticTestData.Factory.create(receiver.tContext, "rt", 0L),
                properties = emptyMap()
            )
        )!!
        val landed = receiver.database.getData(dataId) as? SyntheticTestData
        assertEquals(42L, landed?.value?.get(), "lane-sealed block must ingest like any batch block")
    }

    @Test
    fun emptyLaneCheckpointAndClose_emitNothing() = runTest {
        observer.openSyncLane("lane-1")
        observer.checkpointSyncLane("lane-1")
        observer.closeSyncLane("lane-1")
        assertTrue(pendingBlocks().isEmpty())
        assertTrue(journalDeltas().isEmpty())
    }

    @Test
    fun saveTaggedWithUnopenedLane_fallsBackToPerSavePath() = runTest {
        // TS-3 explicit-open gate: ProxyGateway threads EVERY cancellable
        // call's taskId as a lane id, so an ordinary L2 background op's saves
        // reach the observer lane-tagged — without RegisterTask having opened
        // the lane. They must emit per-save, not stage into a phantom lane.
        save("untagged-lane", 7L, laneId = "never-opened")
        observer.awaitEmitIdle()

        assertEquals(1, pendingBlocks().size, "unopened lane id must keep the ordinary per-save path")
        assertEquals(0, journalDeltas().size, "nothing may stage under an unopened lane")
    }
}
