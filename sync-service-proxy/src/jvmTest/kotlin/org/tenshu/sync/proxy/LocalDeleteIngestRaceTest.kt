package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Concurrent sibling of `SyncProcessorTombstoneTest.localDelete_isNotResurrected_byRepulledCreate`:
 * that one deletes and *then* ingests, this one has the two overlap.
 *
 * A local delete is two steps — remove the row, then record (via `TombstoneRegistry`'s
 * `onDataDeleted`) that it was deleted — and a `SyncProcessor` ingest is likewise a
 * check-then-act: consult the tombstone index, then save. They run on different coroutines
 * (the user's request vs the `SyncScheduler`'s tick) over the same entity, and the relay
 * echoes this device's own pushes back, so a create block for the row being deleted is
 * routinely in flight at exactly that moment. Interleaved, the sequential guard is useless:
 * the ingest sees no tombstone (not stamped yet) and no row (already removed), and re-saves
 * the entity — the row rises from the dead, and the next write at that key fails with
 * `AlreadyExistsError`.
 *
 * That is the user-visible "ghost file", and an intermittent
 * `DeleteAndReuploadFolderSystemTest` failure. Both halves are transactional, so the delete
 * and the ingest can only observe each other's completed state.
 *
 * jvmTest because forcing the interleaving needs a real second thread.
 */
class LocalDeleteIngestRaceTest {

    private lateinit var testProxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var syncManager: SyncManagerImpl

    /**
     * Fires from inside the delete's observer fanout — i.e. after the row is gone but
     * before [TombstoneRegistry] (registered *after* this one, so dispatched after it)
     * has stamped the short-circuit tombstone. That is the exact window the race opens.
     */
    private class InterleavingObserver(
        private val onDelete: (DataId) -> Unit
    ) : DataObserver {
        override fun onDataSaved(data: Data, origin: WriteOrigin) = Unit
        override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = onDelete(dataId)
    }

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            // Production's lock, not the default no-op: this test is *about* what the
            // database lock serialises, and a no-op lock cannot express it.
            lock = ReentrantLockDatabaseLock()
        )
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = testProxy.tContext.syncCryptoManager()
        syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate()
        )
        syncManager.initialize("mock phrase")
    }

    private fun syncBlockFor(data: Data, physicalTime: Long): SyncBlock {
        val crypto = testProxy.tContext.syncCryptoManager()
        val payload = DataBlockPayload.create(
            context = testProxy.tContext,
            propertyId = null,
            value = data
        )
        // Envelope bound as AAD (sync-envelope-authentication §3.2) — an unbound block would be
        // dropped before the race and make this test vacuous.
        return EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = testProxy.tContext,
            blockId = "sync-$physicalTime",
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(
                context = testProxy.tContext,
                physicalTime = physicalTime,
                logicalCounter = 0L,
                // The local device: this models the relay echoing back our own push.
                deviceId = syncManager.hlcManager.deviceId
            ),
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(
                BinarySerializer().serialize(payload),
                testProxy.versionStampBytes()
            ),
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    @Test
    fun ingestConcurrentWithLocalDelete_doesNotResurrectTheRow() {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "lazarus-race", 7L),
            properties = emptyMap()
        )
        val dataId = testProxy.executionContext.getDataId(data)!!
        // Stale by construction: physical time 1 is far below the device's wall-clock HLC,
        // so the tombstone the delete stamps dominates it and the ingest must drop it.
        val echoedCreate = syncBlockFor(data, physicalTime = 1L)

        var ingestThread: Thread? = null
        testProxy.executionContext.addDataObserver(
            InterleavingObserver { deleted ->
                if (deleted != dataId || ingestThread != null) return@InterleavingObserver
                ingestThread = thread { processor.processSyncBlock(echoedCreate) }
                // Give the ingest every chance to complete *inside* the delete. It can only
                // do so if the delete isn't holding the database lock across its fanout —
                // which is precisely the bug. When it is held, this times out, the delete
                // finishes, and the ingest then observes the tombstone.
                ingestThread?.join(INTERLEAVE_WINDOW_MS)
            }
        )

        // Registered after the observer above, so its stamp is dispatched after it.
        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(
                executionContext = testProxy.executionContext,
                wireIdFor = WireIdResolver(syncManager)::ofOrNull,
                localEventHlc = { syncManager.hlcManager.tick() }
            )
        )

        testProxy.executionContext.saveData(data)
        assertNotNull(testProxy.database.getData(dataId), "Sanity: the row exists before the delete")

        testProxy.executionContext.deleteData(dataId)
        ingestThread?.join(JOIN_TIMEOUT_MS)

        assertNull(
            testProxy.database.getData(dataId),
            "A block ingested concurrently with a local delete must not resurrect the row"
        )
    }

    private companion object {
        const val INTERLEAVE_WINDOW_MS = 500L
        const val JOIN_TIMEOUT_MS = 10_000L
    }
}
