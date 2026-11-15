package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.value
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A device pulls its own pushed blocks back from the relay (the echo is not filtered, by design:
 * a device that wipes its store rehydrates from them). A last-writer-wins row must not be walked
 * back by the echo of one of its OWN older writes.
 */
class SyncProcessorOwnEchoTest {

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
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        syncManager.initialize("mock phrase")
        tombstoneRegistry = TombstoneRegistry(
            executionContext = testProxy.executionContext,
            wireIdFor = WireIdResolver(syncManager)::ofOrNull,
            localEventHlc = { syncManager.hlcManager.tick() },
        )
        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = tombstoneRegistry,
        )
    }

    private fun row(value: Long) = Entity.create<SyntheticTestData>(
        context = testProxy.tContext,
        composite = SyntheticTestData.Factory.create(testProxy.tContext, "invite", value),
        properties = emptyMap(),
    )

    private fun storedValue(data: Data): Long? {
        val stored = testProxy.database.getData(testProxy.executionContext.getDataId(data)!!) ?: return null
        @Suppress("UNCHECKED_CAST")
        return when (stored) {
            is Entity<*> -> (stored as Entity<SyntheticTestData>).value.get()
            is SyntheticTestData -> stored.value.get()
            else -> error("unexpected stored shape ${stored::class}")
        }
    }

    /** A data block for [data] stamped at [hlc] — the shape of a block this device pushed. */
    private fun blockFor(data: Data, hlc: HybridLogicalClock): SyncBlock {
        val payload = org.tenshu.sync.DataBlockPayload.create(context = testProxy.tContext, propertyId = null, value = data)
        return EnvelopeAuth.sealSyncBlock(
            crypto = testProxy.tContext.syncCryptoManager(),
            context = testProxy.tContext,
            blockId = "own-${hlc.physicalTime.get()}-${hlc.logicalCounter.get()}",
            ownerId = "user-1",
            hlc = hlc,
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(BinarySerializer().serialize(payload), testProxy.versionStampBytes()),
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    /**
     * The `ContactHandshakeSystemTest` red (`expected:<COMPLETED> but was:<REDEEMED>`), reduced: the
     * device writes REDEEMED (2) then COMPLETED (3), and its own REDEEMED block comes back from the
     * relay after the COMPLETED write. Nothing was ever ingested for the row, so `SyncMetadata` is
     * empty and the echo used to read as "newer".
     */
    @Test
    fun theEchoOfAnOlderOwnWrite_doesNotWalkBackANewerLocalWrite() = runTest {
        testProxy.executionContext.saveData(row(2))
        // The HLC the device's emit path stamps on write 2's block: a local tick after the write.
        val write2BlockHlc = syncManager.hlcManager.tick()
        testProxy.executionContext.saveData(row(3))
        assertEquals(3L, storedValue(row(3)), "sanity: the newer local write is stored")

        processor.processSyncBlock(blockFor(row(2), write2BlockHlc))

        assertEquals(3L, storedValue(row(3)), "the device's own older write came back and replaced its newer one")
    }

    /** The same, after an earlier ingest put an older `SyncMetadata` HLC on the row. */
    @Test
    fun theEchoOfAnOlderOwnWrite_doesNotWalkBack_afterAnEarlierIngest() = runTest {
        processor.processSyncBlock(blockFor(row(1), syncManager.hlcManager.tick()))
        testProxy.executionContext.saveData(row(2))
        val write2BlockHlc = syncManager.hlcManager.tick()
        testProxy.executionContext.saveData(row(3))
        assertEquals(3L, storedValue(row(3)), "sanity: the newer local write is stored")

        processor.processSyncBlock(blockFor(row(2), write2BlockHlc))

        assertEquals(3L, storedValue(row(3)))
    }

    /**
     * ⚠⚠ The shape load makes: the emit consumer lags, so write 2's block is stamped **after** write
     * 3's on-save tick, and its echo arrives before write 3's block is stamped. The on-save record
     * alone is then older than the echo. What stops it is `SyncObserver` recording the HLC it put on
     * the block ([TombstoneRegistry.recordEmittedWrite]). Without that call this test is red.
     */
    @Test
    fun theEchoOfABlockStampedAfterTheNextWrite_stillLoses() = runTest {
        testProxy.executionContext.saveData(row(2))
        testProxy.executionContext.saveData(row(3))
        // The lagging consumer stamps write 2's block now, as SyncObserver does, and records it.
        val write2BlockHlc = syncManager.hlcManager.tick()
        tombstoneRegistry.recordEmittedWrite(row(2), write2BlockHlc)

        processor.processSyncBlock(blockFor(row(2), write2BlockHlc))

        assertEquals(3L, storedValue(row(3)), "an own block stamped late came back and replaced a newer write")
    }

    /** ⚠ Not "local always wins": a peer's write stamped after this device's last write still lands. */
    @Test
    fun aPeerWriteNewerThanTheLastLocalWrite_stillApplies() = runTest {
        testProxy.executionContext.saveData(row(3))
        val later = syncManager.hlcManager.tick()
        val peer = HybridLogicalClock.create(
            context = testProxy.tContext,
            physicalTime = later.physicalTime.get() + 1,
            logicalCounter = 0L,
            deviceId = "device-peer",
        )

        processor.processSyncBlock(blockFor(row(4), peer))

        assertEquals(4L, storedValue(row(4)))
    }

    /**
     * ⚠⚠ The emitted-HLC stamp must not outrank an ingest that already replaced the queued value.
     * The device saves 2, then ingests a peer's newer 5 before its emit consumer stamps 2's block.
     * That block is stamped above 5 (ingest moved the clock), so every peer takes 2 as newest. The
     * device must too, from 2's echo. Recording the stamp unconditionally refused that echo and
     * left the device on 5 for good: divergence after every block was delivered.
     */
    @Test
    fun aQueuedWriteOverwrittenByAnIngest_stillConvergesFromItsEcho() = runTest {
        testProxy.executionContext.saveData(row(2))
        val peerHlc = HybridLogicalClock.create(
            context = testProxy.tContext,
            physicalTime = syncManager.hlcManager.tick().physicalTime.get() + 1_000,
            logicalCounter = 0L,
            deviceId = "device-peer",
        )
        processor.processSyncBlock(blockFor(row(5), peerHlc))
        assertEquals(5L, storedValue(row(5)), "sanity: the newer peer write landed")

        // The lagging consumer stamps write 2's block only now, above the peer's HLC.
        val write2BlockHlc = syncManager.hlcManager.tick()
        tombstoneRegistry.recordEmittedWrite(row(2), write2BlockHlc)
        processor.processSyncBlock(blockFor(row(2), write2BlockHlc))

        assertEquals(2L, storedValue(row(2)), "peers hold 2 (its block outranks 5); this device must converge on it")
    }
}
