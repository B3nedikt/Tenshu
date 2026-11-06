package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncProcessorTombstoneTest {

    private lateinit var testProxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var tombstoneRegistry: TombstoneRegistry

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

        tombstoneRegistry = TombstoneRegistry(
            executionContext = testProxy.executionContext,
            wireIdFor = WireIdResolver(syncManager)::ofOrNull,
            localEventHlc = { syncManager.hlcManager.tick() }
        )
        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = tombstoneRegistry
        )
    }

    private fun hlc(physicalTime: Long, deviceId: String = "device-remote") =
        HybridLogicalClock.create(
            context = testProxy.tContext,
            physicalTime = physicalTime,
            logicalCounter = 0L,
            deviceId = deviceId
        )

    /** The local device's HLC author id — used to model a re-pulled *own* block (relay echo). */
    private fun localDeviceId(): String = syncManager.hlcManager.deviceId

    private fun tombstoneFor(data: Data, physicalTime: Long): DeleteBlock {
        val dataId = testProxy.executionContext.getDataId(data)!!
        val untagged = DeleteBlock.create(
            context = testProxy.tContext,
            blockId = "delete-${physicalTime}",
            ownerId = "user-1",
            hlc = hlc(physicalTime),
            targetEntityIdHash = dataId.wireIdIn(syncManager),
            targetEntityType = SyntheticTestData.ID
        )
        // Tagged under the realm identity key (sync-envelope-authentication §3.1).
        return EnvelopeAuth.tagTombstone(
            testProxy.tContext,
            untagged,
            syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    private fun syncBlockFor(
        data: Data,
        physicalTime: Long,
        deviceId: String = "device-remote"
    ): SyncBlock {
        val serializer = BinarySerializer()
        val crypto = testProxy.tContext.syncCryptoManager()
        val payload = org.tenshu.sync.DataBlockPayload.create(
            context = testProxy.tContext,
            propertyId = null,
            value = data
        )
        // Envelope bound as AAD (sync-envelope-authentication §3.2).
        return EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = testProxy.tContext,
            blockId = "sync-${physicalTime}",
            ownerId = "user-1",
            hlc = hlc(physicalTime, deviceId),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(serializer.serialize(payload), testProxy.versionStampBytes()),
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    /**
     * ⚠ The tombstone's HLC is stamped **above the local save's**, deliberately. This test used
     * to pass `physicalTime = 100L` against a locally-saved row, which is the "ghost file"
     * scenario itself — it only passed because the guard could not see local writes at all
     * (`LocalWriteMetadata`'s KDoc). The invariant it exists for — an ingested tombstone deletes
     * a locally known entity — is unchanged; the ordering it depends on is now written down
     * rather than accidental.
     */
    @Test
    fun handlesDeleteBlock_removesEntityLocally() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "doomed", 1L),
            properties = emptyMap()
        )
        testProxy.executionContext.saveData(data)
        val dataId = testProxy.executionContext.getDataId(data)!!
        assertNotNull(
            testProxy.database.getData(dataId),
            "Sanity: entity should exist before tombstone"
        )

        val afterLocalWrite = syncManager.hlcManager.current().physicalTime.get()
        processor.processDeleteBlock(tombstoneFor(data, physicalTime = afterLocalWrite + 1_000L))

        assertNull(
            testProxy.database.getData(dataId),
            "Entity should be deleted locally after DeleteBlock ingest"
        )
        assertNotNull(
            tombstoneRegistry.tombstoneHlcFor(dataId.wireIdIn(syncManager)),
            "Tombstone should be recorded in the registry"
        )
    }

    @Test
    fun shortCircuitsStaleSyncBlock_afterTombstone() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "stale-target", 1L),
            properties = emptyMap()
        )
        // Entity has never been saved locally; receiving the tombstone records it but leaves
        // the DB unchanged. A late SyncBlock at hlc < tombstone.hlc must NOT revive the entity.
        processor.processDeleteBlock(tombstoneFor(data, physicalTime = 200L))

        processor.processSyncBlock(syncBlockFor(data, physicalTime = 100L))

        val dataId = testProxy.executionContext.getDataId(data)!!
        assertNull(
            testProxy.database.getData(dataId),
            "SyncBlock at HLC < tombstone.hlc should be short-circuited"
        )
    }

    @Test
    fun localDelete_isNotResurrected_byRepulledCreate() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "lazarus", 7L),
            properties = emptyMap()
        )
        val dataId = testProxy.executionContext.getDataId(data)!!

        // 1. The entity is created by a block we previously pushed and are now ingesting
        //    (relay echoes a device its own blocks). Use the *local* device id so this models
        //    an own-block echo, the shape the PendingGrant-accept / cascade-child races hit.
        processor.processSyncBlock(
            syncBlockFor(data, physicalTime = 100L, deviceId = localDeviceId())
        )
        assertNotNull(
            testProxy.database.getData(dataId),
            "Sanity: the ingested create exists locally"
        )

        // 2. A local op deletes the row WITHOUT emitting a tombstone — e.g.
        //    `SharedRealmGrantManager.deletePendingGrant` or `DeleteFileImplementation`'s
        //    recursive child deletes. This goes through `deleteData`, firing `onDataDeleted`.
        testProxy.executionContext.deleteData(dataId)
        assertNull(
            testProxy.database.getData(dataId),
            "Sanity: the row is gone after the local delete"
        )

        // 3. The same create block is pulled back from the relay (echo of our earlier push).
        processor.processSyncBlock(
            syncBlockFor(data, physicalTime = 100L, deviceId = localDeviceId())
        )

        // 4. It must stay deleted — the local delete should have populated the tombstone
        //    short-circuit index so the re-pulled create is dropped.
        assertNull(
            testProxy.database.getData(dataId),
            "A re-pulled create after a local delete must not resurrect the row"
        )
    }

    @Test
    fun acceptsRecreate_withHlcAfterTombstone() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "phoenix", 42L),
            properties = emptyMap()
        )
        // Tombstone first, then a SyncBlock with hlc > tombstone.hlc should be accepted as
        // a recreate (standard CRDT semantics).
        processor.processDeleteBlock(tombstoneFor(data, physicalTime = 100L))
        processor.processSyncBlock(syncBlockFor(data, physicalTime = 200L))

        val dataId = testProxy.executionContext.getDataId(data)!!
        val recreated = testProxy.database.getData(dataId) as? SyntheticTestData
        assertNotNull(recreated, "SyncBlock at HLC > tombstone.hlc should recreate the entity")
        assertEquals(42L, recreated.value.get())
    }

    /**
     * L1a (`sync-lane-cursor-split.md`) — the mirror of the recreate case above, and the
     * precondition for per-lane cursors.
     *
     * The block path already tolerates "tombstone first, newer recreate second". This is the
     * other order: a newer block lands, *then* an older tombstone arrives. It must NOT delete,
     * because the entity the tombstone describes is not the entity that is there now.
     *
     * ⚠ Not hypothetical. Every tick applies all of its blocks before any of its tombstones,
     * whatever the `relay_seq` order, and with per-lane cursors the lanes may drift arbitrarily
     * far apart — so this ordering is normal, not a corner case.
     */
    @Test
    fun anOlderTombstoneDoesNotDeleteANewerRecreate() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "survivor", 1L),
            properties = emptyMap()
        )
        val dataId = testProxy.executionContext.getDataId(data)!!

        // A remote write at HLC 500 lands first (the lane that ran ahead).
        processor.processSyncBlock(syncBlockFor(data, physicalTime = 500L))
        assertNotNull(testProxy.database.getData(dataId), "Sanity: the block should have applied")

        // The tombstone at HLC 100 — older — arrives afterwards, from the lane that lagged.
        processor.processDeleteBlock(tombstoneFor(data, physicalTime = 100L))

        assertNotNull(
            testProxy.database.getData(dataId),
            "An older tombstone must not delete a newer entity — applying the lanes out of " +
                "order would otherwise destroy live data"
        )
    }

    /**
     * The guard must not neuter the ordinary case: a tombstone *newer* than the entity's last
     * applied write still deletes.
     */
    @Test
    fun aNewerTombstoneStillDeletesAnIngestedEntity() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "doomed-ordered", 1L),
            properties = emptyMap()
        )
        val dataId = testProxy.executionContext.getDataId(data)!!

        processor.processSyncBlock(syncBlockFor(data, physicalTime = 100L))
        assertNotNull(testProxy.database.getData(dataId), "Sanity: the block should have applied")

        processor.processDeleteBlock(tombstoneFor(data, physicalTime = 500L))

        assertNull(
            testProxy.database.getData(dataId),
            "A tombstone newer than the entity's last write must still delete it"
        )
    }

    /**
     * ⚠⚠ **The "ghost file" data loss, pinned deterministically.**
     *
     * [anOlderTombstoneDoesNotDeleteANewerRecreate] above pins the same invariant for a recreate
     * that arrived **over the wire**, which holds without the local-write record — because
     * `processSyncBlock` writes the [org.tenshu.sync.common.SyncMetadata] row the guard reads.
     * This is its twin for the case that record cannot see: a recreate written **locally**, by the
     * user, which stamps no `SyncMetadata` at all. Without
     * [org.tenshu.sync.common.LocalWriteMetadata] the guard finds no HLC to compare and deletes a
     * row strictly newer than the tombstone.
     *
     * ⚠ No loop and no sleep: the ordering that has to be won in a live system — *recreate
     * lands, tombstone arrives after* — is simply written down here in the order the two calls
     * are made. That is the point of pinning it at this level; a `repeat(40)` against a live
     * scheduler is a lottery, not a test.
     */
    @Test
    fun aTombstoneDoesNotDeleteALocallyRecreatedEntity() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "recreated", 1L),
            properties = emptyMap()
        )
        val dataId = testProxy.executionContext.getDataId(data)!!

        // The user recreates the row locally — the `createFolder("/photos")` of the field
        // report. A local write, so no SyncMetadata: this is the entity's ONLY existence.
        testProxy.executionContext.saveData(data)
        assertNotNull(
            testProxy.database.getData(dataId),
            "Sanity: the local recreate must be in the store before the tombstone arrives",
        )

        // The tombstone for the id this row *used* to occupy. ⚠ `DataId` is path-derived, so
        // the recreated row carries the identical id hash as the deleted one — which is why a
        // stale tombstone can reach a live row at all.
        processor.processDeleteBlock(tombstoneFor(data, physicalTime = 100L))

        assertNotNull(
            testProxy.database.getData(dataId),
            "A tombstone must not delete a row the user recreated locally afterwards. A " +
                "guard reading SyncMetadata alone, which only the ingest paths write, cannot " +
                "see a local recreate and destroys it — silently, and with its children " +
                "orphaned when the row is a folder.",
        )
    }

    /**
     * ⚠⚠ The second half, and the one that needs no relay echo. Same defect reached the way it
     * happens between two real devices: this device ingested the row at some point (so it *does*
     * have a [org.tenshu.sync.common.SyncMetadata] row), the user then recreated it locally, and
     * a peer's tombstone arrives afterwards. The metadata still holds the HLC of that old ingest
     * — 195 ms older than the tombstone in the measured case — so the comparison was made
     * against a write that is not the entity's latest.
     *
     * Deterministic by construction: ingest at 100, recreate locally, tombstone at 200. The
     * local write sits *between* them in real order and was invisible to both numbers.
     */
    @Test
    fun aTombstoneDoesNotDeleteALocalRecreateThatFollowedAnIngest() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "resurrected", 1L),
            properties = emptyMap()
        )
        val dataId = testProxy.executionContext.getDataId(data)!!

        // 1. The row arrives over the wire at HLC 100 — this is what writes SyncMetadata.
        processor.processSyncBlock(syncBlockFor(data, physicalTime = 100L))
        assertNotNull(testProxy.database.getData(dataId), "Sanity: the ingest should have applied")

        // 2. The row is deleted and the user recreates it locally. The recreate is the newest
        //    write to this id by any clock — and updates no SyncMetadata.
        testProxy.executionContext.deleteData(dataId)
        testProxy.executionContext.saveData(data)

        // 3. A peer's tombstone lands afterwards, newer than the stale metadata at step 1.
        processor.processDeleteBlock(tombstoneFor(data, physicalTime = 200L))

        assertNotNull(
            testProxy.database.getData(dataId),
            "The guard compared the tombstone against the last INGESTED write (HLC 100), not " +
                "the local recreate that followed it, so it read a live row as stale and " +
                "deleted it. No self-echo is involved here: this is peer B's delete racing " +
                "device A's recreate.",
        )
    }

    /**
     * ⚠⚠ **No recreate is involved at all** — which is why the record is "last local write"
     * rather than "last resurrection". An ordinary local edit after an ingest is equally
     * invisible to a guard that reads only [org.tenshu.sync.common.SyncMetadata], so a peer
     * tombstone older than the edit would destroy it.
     */
    @Test
    fun aTombstoneDoesNotDeleteALocallyEditedEntity() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "edited", 1L),
            properties = emptyMap()
        )
        val dataId = testProxy.executionContext.getDataId(data)!!

        // 1. Ingested over the wire at HLC 100 — writes SyncMetadata.
        processor.processSyncBlock(syncBlockFor(data, physicalTime = 100L))
        assertNotNull(testProxy.database.getData(dataId), "Sanity: the ingest should have applied")

        // 2. The user edits it locally. No delete, no recreate — just a newer local write.
        val edited = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "edited", 2L),
            properties = emptyMap()
        )
        testProxy.executionContext.saveData(edited)

        // 3. A peer's tombstone at HLC 200 — newer than the stale metadata, older than the edit.
        processor.processDeleteBlock(tombstoneFor(data, physicalTime = 200L))

        assertNotNull(
            testProxy.database.getData(dataId),
            "The guard compares against the last INGESTED write, so any local write after an " +
                "ingest is invisible to it — a recreate is not required to lose data.",
        )
    }

    /**
     * ⚠ The guard must not become "local always wins". A peer's delete that is genuinely newer
     * than this device's last local write still deletes: the local write is stamped at the
     * device's current HLC, and this tombstone is stamped above it.
     */
    @Test
    fun aTombstoneNewerThanTheLocalWriteStillDeletes() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "doomed-local", 1L),
            properties = emptyMap()
        )
        val dataId = testProxy.executionContext.getDataId(data)!!

        testProxy.executionContext.saveData(data)
        val afterLocalWrite = syncManager.hlcManager.current().physicalTime.get()

        processor.processDeleteBlock(tombstoneFor(data, physicalTime = afterLocalWrite + 1_000L))

        assertNull(
            testProxy.database.getData(dataId),
            "A tombstone newer than the local write is an ordinary delete and must apply — " +
                "the guard bounds the comparison, it does not privilege local writes.",
        )
    }

    /**
     * The control for [aLocalWriteToANonSyncableRow_stampsNothing] below.
     *
     * ⚠ The pair is the test. A negative assertion on its own passes just as happily when the
     * record is never written at all, so the gate is only pinned by showing the same code path
     * writing for one input and declining for the other.
     */
    @Test
    fun aLocalWriteToASyncableEntity_stampsTheRecord() = runTest {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "syncable", 1L),
            properties = emptyMap()
        )
        testProxy.executionContext.saveData(data)

        val idHash = testProxy.executionContext.getDataId(data)!!.wireIdIn(syncManager)
        assertNotNull(
            tombstoneRegistry.localWriteHlc(idHash),
            "A local write to a Syncable entity is exactly what the tombstone guard has to see.",
        )
    }

    /**
     * ⚠⚠ A non-syncable row must NOT get a [org.tenshu.sync.common.LocalWriteMetadata] row.
     *
     * A `DeleteBlock` addresses an entity, and every emit site targets a syncable entity type,
     * so a local-only row's last-write HLC has no reader — writing one would cost a store row
     * per local write of every kind, on the write path, bought for nobody.
     *
     * `SyncMetadata` is the honest fixture rather than a synthetic: it is a real production
     * local-only composite with a `DataIdProvider` and no `Syncable`, and it is not one of the
     * registry's own `SIDECAR_TYPES`, so it reaches the gate the same way an app's local row does.
     */
    @Test
    fun aLocalWriteToANonSyncableRow_stampsNothing() = runTest {
        val metadata = org.tenshu.sync.common.SyncMetadata.create(
            context = testProxy.tContext,
            hlc = hlc(physicalTime = 100L),
            targetDataIdHash = "some-entity-hash",
        )
        testProxy.executionContext.saveData(metadata)

        val idHash = testProxy.executionContext.getDataId(metadata)!!.wireIdIn(syncManager)
        assertNull(
            tombstoneRegistry.localWriteHlc(idHash),
            "A row nothing can tombstone must not be stamped — the record's population tracks " +
                "synced entities, not every local write.",
        )
    }

}
