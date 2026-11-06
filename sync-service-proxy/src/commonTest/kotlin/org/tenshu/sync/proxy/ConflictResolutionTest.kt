package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TResult
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictResolutionTest {

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

    @Test
    fun testLastWriterWins() = runTest {
        // 1. Initial State
        val initialData = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "conflict", 100L),
            properties = emptyMap()
        )
        testProxy.database.saveData(initialData)

        // 2. Incoming SyncBlock with newer HLC
        val newerHlc = HybridLogicalClock.create(
            context = testProxy.tContext,
            physicalTime = 2000L,
            logicalCounter = 0L,
            deviceId = "device-remote"
        )
        val updatedData = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "conflict", 200L),
            properties = emptyMap()
        )

        val serializer = BinarySerializer()
        val crypto = testProxy.tContext.syncCryptoManager()
        // Wrap in DataBlockPayload (zero-trust: propertyId lives inside
        // the encrypted payload, not on the wire — see SyncBlock).
        val payload = org.tenshu.sync.DataBlockPayload.create(
            context = testProxy.tContext,
            propertyId = null,
            value = updatedData
        )
        // Envelope bound as AAD (sync-envelope-authentication §3.2).
        val block = org.tenshu.sync.common.EnvelopeAuth.sealSyncBlock(
            crypto = crypto,
            context = testProxy.tContext,
            blockId = "block-1",
            ownerId = "user-1",
            hlc = newerHlc,
            targetEntityIdHash = null,
            plaintext = BlockPayloadEnvelope.seal(serializer.serialize(payload), testProxy.versionStampBytes()),
            identityKey = syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )

        processor.processSyncBlock(block)

        val dataId = testProxy.executionContext.getDataId(initialData)!!
        val finalData = testProxy.database.getData(dataId) as SyntheticTestData

        assertEquals(
            expected = 200L,
            actual = finalData.value.get(),
            message = "LWW should have updated the value"
        )
    }
}
