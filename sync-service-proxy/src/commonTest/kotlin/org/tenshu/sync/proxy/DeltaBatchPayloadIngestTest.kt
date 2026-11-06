package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.SyncDelta
import org.tenshu.sync.DeltaBatchPayload
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TS-1 ingest tests for the task-scoped sync lane's wire primitive
 * (`core-background-processing.md` §10.2): a [DeltaBatchPayload] container is
 * applied delta-by-delta through the same per-entity HLC/merge cycle as
 * standalone blocks — with each delta's OWN inner HLC, in both conflict
 * directions against organic [DataBlockPayload] blocks. The payload travels
 * the real serialize → encrypt → decrypt → parse path, so these also pin the
 * wire registration (`syncServiceCommonModule`) end-to-end.
 */
class DeltaBatchPayloadIngestTest {

    private lateinit var testProxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var syncManager: SyncManagerImpl

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

        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun hlc(physicalTime: Long) = HybridLogicalClock.create(
        context = testProxy.tContext,
        physicalTime = physicalTime,
        logicalCounter = 0L,
        deviceId = "device-remote"
    )

    private fun entity(key: String, value: Long): Entity<SyntheticTestData> = Entity.create(
        context = testProxy.tContext,
        composite = SyntheticTestData.Factory.create(testProxy.tContext, key, value),
        properties = emptyMap()
    )

    /** Seal [payload] into a block with its envelope bound as AAD (sync-envelope-authentication §3.2). */
    private fun sealBlock(
        blockId: String,
        atHlc: Long,
        targetEntityIdHash: String?,
        payload: org.tenshu.core.Data,
    ): SyncBlock = EnvelopeAuth.sealSyncBlock(
        crypto = testProxy.tContext.syncCryptoManager(),
        context = testProxy.tContext,
        blockId = blockId,
        ownerId = "user-1",
        hlc = hlc(atHlc),
        targetEntityIdHash = targetEntityIdHash,
        plaintext = BlockPayloadEnvelope.seal(BinarySerializer().serialize(payload), testProxy.versionStampBytes()),
        identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
    )

    private fun deltaBatchBlock(blockId: String, outerHlc: Long, deltas: List<SyncDelta>): SyncBlock =
        sealBlock(
            blockId = blockId,
            atHlc = outerHlc,
            // §10.2 D2: a container has N targets — the wire hash stays null.
            targetEntityIdHash = null,
            payload = DeltaBatchPayload.create(context = testProxy.tContext, deltas = deltas),
        )

    private fun organicBlock(blockId: String, atHlc: Long, data: Entity<SyntheticTestData>): SyncBlock =
        sealBlock(
            blockId = blockId,
            atHlc = atHlc,
            targetEntityIdHash = null,
            payload = DataBlockPayload.create(context = testProxy.tContext, propertyId = null, value = data),
        )

    private fun readValue(key: String): Long? {
        val dataId = testProxy.executionContext.getDataId(entity(key, 0)) ?: return null
        val data = testProxy.database.getData(dataId) as? SyntheticTestData ?: return null
        return data.value.get()
    }

    // ---- tests -------------------------------------------------------------

    @Test
    fun containerWithMultipleEntityDeltas_appliesEachThroughTheMergeCycle() = runTest {
        val block = deltaBatchBlock(
            blockId = "cp-1",
            outerHlc = 5_000L,
            deltas = listOf(
                SyncDelta.create(testProxy.tContext, hlc(1_000L), entity("a", 100L), deltaId = "d1"),
                SyncDelta.create(testProxy.tContext, hlc(2_000L), entity("b", 200L), deltaId = "d2"),
                SyncDelta.create(testProxy.tContext, hlc(3_000L), entity("c", 300L), deltaId = "d3"),
            )
        )

        processor.processSyncBlock(block)

        assertEquals(100L, readValue("a"))
        assertEquals(200L, readValue("b"))
        assertEquals(300L, readValue("c"))
    }

    @Test
    fun staleContainerDelta_losesToNewerOrganicBlock_freshSiblingStillApplies() = runTest {
        // Organic standalone block for "a" at HLC 2000 arrives first.
        processor.processSyncBlock(organicBlock("org-1", atHlc = 2_000L, data = entity("a", 999L)))
        assertEquals(999L, readValue("a"))

        // A checkpoint whose OUTER HLC is newer (9000) but whose inner delta
        // for "a" is OLDER (1000) must lose on "a" — the inner HLC drives the
        // per-entity merge, not the checkpoint marker — while the fresh
        // sibling delta "b" applies normally.
        val block = deltaBatchBlock(
            blockId = "cp-1",
            outerHlc = 9_000L,
            deltas = listOf(
                SyncDelta.create(testProxy.tContext, hlc(1_000L), entity("a", 111L), deltaId = "d4"),
                SyncDelta.create(testProxy.tContext, hlc(3_000L), entity("b", 222L), deltaId = "d5"),
            )
        )
        processor.processSyncBlock(block)

        assertEquals(999L, readValue("a"), "stale inner delta must not overwrite the newer organic write")
        assertEquals(222L, readValue("b"), "fresh sibling delta must still apply")
    }

    @Test
    fun newerOrganicBlockAfterContainer_winsOverTheContainerDelta() = runTest {
        processor.processSyncBlock(
            deltaBatchBlock(
                blockId = "cp-1",
                outerHlc = 1_500L,
                deltas = listOf(SyncDelta.create(testProxy.tContext, hlc(1_000L), entity("a", 100L), deltaId = "d6"))
            )
        )
        assertEquals(100L, readValue("a"))

        // The container delta wrote SyncMetadata at ITS inner HLC (1000), so a
        // later organic block at 2000 must be seen as newer and win.
        processor.processSyncBlock(organicBlock("org-1", atHlc = 2_000L, data = entity("a", 200L)))
        assertEquals(200L, readValue("a"), "organic block newer than the inner delta HLC must win")
    }

    @Test
    fun innerDeltaHlcs_advanceTheLocalClock() = runTest {
        // Far-future inner HLC so the assertion is meaningful against wall clock.
        val farFuture = 4_102_444_800_000L // 2100-01-01
        processor.processSyncBlock(
            deltaBatchBlock(
                blockId = "cp-1",
                outerHlc = 1_000L,
                deltas = listOf(SyncDelta.create(testProxy.tContext, hlc(farFuture), entity("a", 1L), deltaId = "d7"))
            )
        )

        assertTrue(
            processor.currentRealmHlc().physicalTime.get() >= farFuture,
            "receiver clock must advance through inner delta HLCs, not only the outer block HLC"
        )
    }

    @Test
    fun propertyDeltaWithoutTargetHash_isDroppedWithoutAffectingSiblings() = runTest {
        val block = deltaBatchBlock(
            blockId = "cp-1",
            outerHlc = 5_000L,
            deltas = listOf(
                // Malformed: a property delta must carry the in-ciphertext
                // target hash (the container block's wire hash is null).
                SyncDelta.create(
                    testProxy.tContext,
                    hlc(1_000L),
                    entity("orphan", 1L),
                    targetEntityIdHash = null,
                    propertyId = "some/property",
                    deltaId = "d-orphan"
                ),
                SyncDelta.create(testProxy.tContext, hlc(2_000L), entity("b", 200L), deltaId = "d8"),
            )
        )

        processor.processSyncBlock(block)

        assertNull(readValue("orphan"), "hash-less property delta must be dropped, not misapplied as an entity")
        assertEquals(200L, readValue("b"), "sibling delta must be unaffected by the dropped one")
    }
}
