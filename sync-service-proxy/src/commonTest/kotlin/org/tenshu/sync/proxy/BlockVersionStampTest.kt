package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * E0 block version stamp (`core-schema-evolution.md` §5): every block payload's
 * plaintext is a [BlockPayloadEnvelope] — the producing format version, then the
 * composite bytes. The receiver reads the stamp in `decryptAndParseCipher`; a
 * payload with no readable envelope (pre-E0 bytes) fails to parse under the
 * misread stamp and is dropped like any other parse failure — never applied as
 * garbage.
 */
class BlockVersionStampTest {

    private lateinit var testProxy: TestProxy
    private lateinit var processor: SyncProcessor
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var localDataId: DataId

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

        // Seed a local row the incoming blocks target (same DataId key).
        val initial = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "stamped", 100L),
            properties = emptyMap()
        )
        testProxy.database.saveData(initial)
        localDataId = testProxy.executionContext.getDataId(initial)!!
    }

    /** Serialized (unsealed) `DataBlockPayload` carrying value [value] for the seeded row. */
    private fun payloadBytesFor(value: Long): ByteArray {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "stamped", value),
            properties = emptyMap()
        )
        val payload = DataBlockPayload.create(
            context = testProxy.tContext,
            propertyId = null,
            value = data
        )
        return BinarySerializer().serialize(payload)
    }

    private fun blockCarrying(plaintext: ByteArray, blockId: String): SyncBlock {
        val crypto = testProxy.tContext.syncCryptoManager()
        // Envelope bound as AAD (sync-envelope-authentication §3.2).
        return org.tenshu.sync.common.EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = "user-1",
            hlc = HybridLogicalClock.create(
                context = testProxy.tContext,
                physicalTime = 2000L,
                logicalCounter = 0L,
                deviceId = "device-remote"
            ),
            targetEntityIdHash = null,
            plaintext = plaintext,
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    private fun storedValue(): Long =
        (testProxy.database.getData(localDataId) as SyntheticTestData).value.get()

    @Test
    fun stampedBlockAppliesAndCarriesTheCurrentFormatVersion() = runTest {
        val sealed = BlockPayloadEnvelope.seal(payloadBytesFor(200L), testProxy.versionStampBytes())

        // The stamp on the wire is the emitter's TENSHU_STORAGE_FORMAT_VERSION.
        assertEquals(
            TENSHU_STORAGE_FORMAT_VERSION,
            BlockPayloadEnvelope.open(sealed)!!.formatVersion,
        )

        processor.processSyncBlock(blockCarrying(sealed, blockId = "stamped-1"))

        assertEquals(200L, storedValue(), "the stamped block must apply")
    }

    @Test
    fun unstampedLegacyPayloadIsDropped() = runTest {
        // Pre-E0 bytes: raw composite with no envelope.
        processor.processSyncBlock(blockCarrying(payloadBytesFor(999L), blockId = "legacy-1"))

        assertEquals(100L, storedValue(), "an unstamped payload must be dropped, not applied")
    }
}
