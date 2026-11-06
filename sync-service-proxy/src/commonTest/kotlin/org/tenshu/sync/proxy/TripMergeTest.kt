package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.timeline.Trip
import org.tenshu.core.timeline.TripMember
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.resolveListField
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.timeline.common.timelineServiceCommonModule
import org.tenshu.timeline.proxy.TripMerger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The **`TripMerger` sync-merge path** (I5, `timeline-services.md` §5.2): two devices' versions of the
 * same `Trip` (same `tripId`) land on one key, and `ConflictStrategy.Merge` dispatches to `TripMerger`,
 * which **unions the membership** rather than last-writer-wins. Mirrors `FolderMergeTest` over the same
 * `SyncProcessor` harness, with the timeline registry.
 */
class TripMergeTest {

    @Test
    fun tripMembershipIsUnionedAcrossDevices() = runTest {
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

        val mergerRegistry = SemanticMergerRegistry().apply { register(TripMerger()) }
        val processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            mergerRegistry = mergerRegistry,
        )

        // Device A's Lisbon trip: members m1, m2.
        val localTrip = Trip.create(tContext, tripId = "lisbon", title = "Lisbon", membership = listOf("m1", "m2"))
        testProxy.database.saveData(localTrip)

        // Device B's version of the same trip (same tripId): members m2, m3 — a newer HLC.
        val incomingTrip = Trip.create(tContext, tripId = "lisbon", title = "Lisbon", membership = listOf("m2", "m3"))
        val serializer = BinarySerializer()
        val payload = DataBlockPayload.create(context = tContext, propertyId = null, value = incomingTrip)
        val block = org.tenshu.sync.common.EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = tContext,
            blockId = "block-trip-2",
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(tContext, 2000L, 0, "device-remote"),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(serializer.serialize(payload), testProxy.versionStampBytes()),
            identityKey = keys.sharedIdentityKey,
        )

        processor.processSyncBlock(block)

        // The merged trip carries the UNION of both devices' members.
        val dataId = testProxy.executionContext.getDataId(localTrip)!!
        val finalTrip = testProxy.database.getData(dataId) as Trip
        val members = testProxy.executionContext.resolveListField(finalTrip, Trip.Fields.MEMBERSHIP)
            .filterIsInstance<TripMember>()
            .map { it.momentId.get() }
            .toSet()

        assertEquals(setOf("m1", "m2", "m3"), members)
    }
}
