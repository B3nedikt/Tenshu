package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MomentSource
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.resolveListField
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.proxy.MomentMerger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The **`MomentMerger` sync-merge path** (I6, `timeline-ingestion.md` §7.1): cross-source
 * reconciliation elects a canonical [Moment] with a *deterministic* id, so two devices independently
 * reconciling the same real-world visit land on one key. Under `ConflictStrategy.Merge` the merger
 * **unions the `sources` provenance set** rather than last-writer-wins, so the canonical accumulates
 * every source's confirmation. Mirrors `TripMergeTest` over the same `SyncProcessor` harness with the
 * timeline registry.
 */
class MomentMergeTest {

    @Test
    fun momentSourcesAreUnionedAcrossDevices() = runTest {
        val testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        testProxy.schemaRegistry.registerConfig(timelineServiceCommonModule)

        val tContext = testProxy.tContext
        val crypto = tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            context = tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        syncManager.initialize("mock phrase")
        val keys = syncManager.getDerivedKeys()!!

        val mergerRegistry = SemanticMergerRegistry().apply { register(MomentMerger()) }
        val processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            mergerRegistry = mergerRegistry,
        )

        // Device A's canonical lunch-visit moment, seen so far only by live capture.
        val localMoment = Moment.create(
            context = tContext,
            momentId = "canon-lunch",
            timestamp = UnixInstantDto(1000L),
            kind = Moment.MomentKind.VISIT,
            title = "Lunch",
            subject = SubjectRef.none(tContext),
            sources = listOf("capture"),
        )
        testProxy.database.saveData(localMoment)

        // Device B's version of the *same* canonical (same momentId): a Google-import confirmation,
        // newer HLC.
        val incomingMoment = Moment.create(
            context = tContext,
            momentId = "canon-lunch",
            timestamp = UnixInstantDto(1000L),
            kind = Moment.MomentKind.VISIT,
            title = "Lunch",
            subject = SubjectRef.none(tContext),
            sources = listOf("google"),
        )
        val serializer = BinarySerializer()
        val payload = DataBlockPayload.create(context = tContext, propertyId = null, value = incomingMoment)
        val block = org.tenshu.sync.common.EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = tContext,
            blockId = "block-moment-2",
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(tContext, 2000L, 0, "device-remote"),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(serializer.serialize(payload), testProxy.versionStampBytes()),
            identityKey = keys.sharedIdentityKey,
        )

        processor.processSyncBlock(block)

        // The merged moment carries the UNION of both devices' sources.
        val dataId = testProxy.executionContext.getDataId(localMoment)!!
        val finalMoment = testProxy.database.getData(dataId) as Moment
        val sources = testProxy.executionContext.resolveListField(finalMoment, Moment.Fields.SOURCES)
            .filterIsInstance<MomentSource>()
            .map { it.source.get() }
            .toSet()

        assertEquals(setOf("capture", "google"), sources)
    }
}
