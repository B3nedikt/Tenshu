package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.Device
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ `sync-envelope-authentication.md` §3.4 — what an active relay (or TLS middlebox) can no longer do:
 *
 * - forge a tombstone (untagged, or tagged under a key it guessed) — the row survives, the clock and
 *   the realm epoch do not move, nothing is recorded;
 * - alter any field of a genuine tombstone, keeping its tag;
 * - re-stamp a genuine `SyncBlock` (new HLC, target or blockId over the same ciphertext).
 *
 * The genuine paths are pinned alongside, so a green here cannot mean "ingest drops everything".
 */
class SyncProcessorEnvelopeAuthTest {

    private lateinit var proxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var registry: TombstoneRegistry

    /** Far ahead of any local clock: a forgery's HLC would otherwise win every guard. */
    private val future = 4_000_000_000_000L

    @BeforeTest
    fun setup() {
        proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        syncManager.initialize("mock phrase")
        registry = TombstoneRegistry(
            executionContext = proxy.executionContext,
            wireIdFor = WireIdResolver(syncManager)::ofOrNull,
            localEventHlc = { syncManager.hlcManager.tick() },
        )
        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = registry,
        )
    }

    private val key get() = syncManager.getDerivedKeys()!!.sharedIdentityKey

    private fun hlc(phys: Long, device: String = "device-remote") =
        HybridLogicalClock.create(proxy.tContext, phys, 0L, device)

    private fun savedEntity(name: String): Data {
        val data = Entity.create<SyntheticTestData>(
            context = proxy.tContext,
            composite = SyntheticTestData.Factory.create(proxy.tContext, name, 1L),
            properties = emptyMap(),
        )
        proxy.executionContext.saveData(data)
        return data
    }

    private fun untagged(
        target: String,
        phys: Long = future,
        type: String = TombstoneWireTypeNonDevice,
        revoked: String? = null,
        blockId: String = "t-$phys",
    ) = DeleteBlock.create(proxy.tContext, blockId, "user-1", hlc(phys), target, type, revoked)

    private fun wireIdOf(data: Data) = proxy.executionContext.getDataId(data)!!.wireIdIn(syncManager)

    private fun clock() = syncManager.hlcManager.current().physicalTime.get()

    // --- tombstones ------------------------------------------------------------------------------

    @Test
    fun anUntaggedTombstone_deletesNothing_andMovesNoClock() = runTest {
        val data = savedEntity("kept")
        val id = proxy.executionContext.getDataId(data)!!
        val before = clock()
        val rejectionsBefore = EnvelopeRejections.tombstoneCount

        processor.processDeleteBlock(untagged(wireIdOf(data)))

        assertNotNull(proxy.database.getData(id), "a forged tombstone must not delete the row")
        assertNull(registry.tombstoneHlcFor(wireIdOf(data)), "nor be recorded")
        assertTrue(clock() < future, "nor drag the clock to its HLC (before=$before, now=${clock()})")
        assertEquals(rejectionsBefore + 1, EnvelopeRejections.tombstoneCount)
    }

    @Test
    fun aTombstoneTaggedUnderAnotherKey_isRejected() = runTest {
        val data = savedEntity("kept")
        val forged = EnvelopeAuth.tagTombstone(proxy.tContext, untagged(wireIdOf(data)), ByteArray(32) { 9 })

        processor.processDeleteBlock(forged)

        assertNotNull(proxy.database.getData(proxy.executionContext.getDataId(data)!!))
    }

    /** ⭐ A forged Device tombstone must not move the realm epoch (the Device-tombstone count). */
    @Test
    fun aForgedDeviceTombstone_doesNotMoveTheRealmEpoch() = runTest {
        processor.processDeleteBlock(untagged("device-wire-id", type = Device.ID, revoked = "device-b"))
        assertEquals(0, registry.tombstoneCountForType(Device.ID))

        // ...while a genuine one does, so the assertion above is not vacuous.
        val genuine = EnvelopeAuth.tagTombstone(
            proxy.tContext, untagged("device-wire-id", type = Device.ID, revoked = "device-b"), key,
        )
        processor.processDeleteBlock(genuine)
        assertEquals(1, registry.tombstoneCountForType(Device.ID))
    }

    @Test
    fun alteringAnyFieldOfAGenuineTombstone_isRejected() = runTest {
        val victim = savedEntity("victim")
        val genuine = EnvelopeAuth.tagTombstone(
            proxy.tContext, untagged("other-wire-id", phys = 1_000L, blockId = "g"), key,
        )
        val tag = genuine.authTag!!.get()
        fun withTag(block: DeleteBlock) = DeleteBlock.create(
            proxy.tContext, block.blockId.get(), block.ownerId.get(), block.hlc,
            block.targetEntityIdHash.get(), block.targetEntityType.get(), block.revokedDeviceId?.get(), tag,
        )

        listOf(
            untagged(wireIdOf(victim), phys = 1_000L, blockId = "g"),                    // re-targeted
            untagged("other-wire-id", phys = future, blockId = "g"),                     // re-stamped
            untagged("other-wire-id", phys = 1_000L, blockId = "g", type = Device.ID),   // re-typed
            untagged("other-wire-id", phys = 1_000L, blockId = "g", revoked = "device-b"),
        ).forEach { processor.processDeleteBlock(withTag(it)) }

        assertNotNull(proxy.database.getData(proxy.executionContext.getDataId(victim)!!))
        assertEquals(0, registry.tombstoneCountForType(Device.ID))
        assertNull(registry.tombstoneHlcFor("other-wire-id"))
    }

    @Test
    fun aGenuineTombstone_stillDeletes() = runTest {
        val data = savedEntity("doomed")
        val id = proxy.executionContext.getDataId(data)!!
        processor.processDeleteBlock(
            EnvelopeAuth.tagTombstone(proxy.tContext, untagged(wireIdOf(data), phys = clock() + 1_000L), key)
        )
        assertNull(proxy.database.getData(id))
    }

    // --- blocks ----------------------------------------------------------------------------------

    private fun genuineBlock(data: Data, phys: Long): SyncBlock = EnvelopeAuth.sealSyncBlock(
        crypto = proxy.tContext.syncCryptoManager(),
        context = proxy.tContext,
        blockId = "b-$phys",
        ownerId = "user-1",
        hlc = hlc(phys),
        targetEntityIdHash = null,
        plaintext = BlockPayloadEnvelope.seal(
            BinarySerializer().serialize(DataBlockPayload.create(proxy.tContext, propertyId = null, value = data)),
            proxy.versionStampBytes(),
        ),
        identityKey = key,
    )

    /** ⭐ T-K-05 rollback: an old genuine ciphertext re-stamped with a fresh HLC must not merge. */
    @Test
    fun aRestampedBlock_isNotMerged_andMovesNoClock() = runTest {
        val old = Entity.create<SyntheticTestData>(
            context = proxy.tContext,
            composite = SyntheticTestData.Factory.create(proxy.tContext, "row", 1L),
            properties = emptyMap(),
        )
        val genuine = genuineBlock(old, phys = 1_000L)
        val restamped = SyncBlock.create(
            proxy.tContext, genuine.blockId.get(), genuine.ownerId.get(), hlc(future),
            genuine.encryptedPayload.get(), genuine.targetEntityIdHash?.get(),
        )

        processor.processSyncBlock(restamped)

        assertNull(proxy.database.getData(proxy.executionContext.getDataId(old)!!), "not merged")
        assertTrue(clock() < future, "clock not dragged by an unverified envelope")

        // The same block, un-tampered, applies — so the drop above is the binding, not the fixture.
        processor.processSyncBlock(genuine)
        assertNotNull(proxy.database.getData(proxy.executionContext.getDataId(old)!!))
    }

    private companion object {
        const val TombstoneWireTypeNonDevice = TombstoneWireType.NON_DEVICE
    }
}
