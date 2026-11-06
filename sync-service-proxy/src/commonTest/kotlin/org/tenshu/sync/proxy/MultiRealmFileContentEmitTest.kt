package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.RealmRegistry.Companion.NAMESPACE_SEP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SR-3c-prereq: `emitFileContent` is realm-routed. A file whose target DataId carries a
 * shared realm's `"$realmId::"` prefix has its chunks `PUT` to **that realm's** content
 * store (not the personal one), encrypted under the shared keys, with its manifest block
 * queued to the shared realm's outbound queue. A plain (personal) target is byte-identical
 * to before. Constructs the `SyncObserver` directly (no scheduler) for determinism.
 */
class MultiRealmFileContentEmitTest {

    private val sharedKeys = SyncCryptoManager.DerivedKeys(
        sharedIdentityKey = ByteArray(32) { 0x33 },
        sharedVolumeKey = ByteArray(32) { 0x44 },
    )

    private class Fixture(
        val proxy: TestProxy,
        val observer: SyncObserver,
        val personalSink: InMemoryContentBlobStore,
        val sharedSink: InMemoryContentBlobStore,
        val fileDatabase: InMemoryFileDatabase,
    )

    private fun fixture(): Fixture {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        syncManager.initialize("personal phrase placeholder words go here now")

        val personalSink = InMemoryContentBlobStore(proxy.tContext)
        val sharedSink = InMemoryContentBlobStore(proxy.tContext)
        val fileDatabase = InMemoryFileDatabase()

        // Registry whose content-store factory hands the shared realm its own sink.
        val registry = RealmRegistry(
            syncManager = syncManager,
            contentBlobStoreFactory = { _ -> sharedSink },
        )
        registry.register(
            RealmContext(
                ownerId = "shared-1",
                deviceId = syncManager.currentDeviceId,
                derivedKeys = sharedKeys,
                deviceSigningKeyPair = crypto.generateDeviceSigningKeyPair(),
                masterKeyPair = null,
            )
        )

        val observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
            contentBlobStore = personalSink,
            fileDatabase = fileDatabase,
            realmRegistry = registry,
        )

        return Fixture(proxy, observer, personalSink, sharedSink, fileDatabase)
    }

    @Test
    fun namespacedTarget_emitsToTheSharedRealmsContentStoreAndQueue() = runTest {
        val f = fixture()
        f.fileDatabase.writeRawByteArray("/photo.jpg", ByteArray(4096) { it.toByte() })

        val ok = f.observer.emitFileContent(
            targetEntityId = DataId.StringDataId.create(
                context = f.proxy.tContext,
                type = SyntheticTestData.ID,
                keyValue = "shared-1$NAMESPACE_SEP/photo.jpg",
            ),
            propertyId = "tenshu/file/file/data",
            filePath = "/photo.jpg",
        )
        assertTrue(ok, "emit should succeed")

        assertEquals(1, f.sharedSink.putCount, "chunk PUT to the shared realm's content store")
        assertEquals(0, f.personalSink.putCount, "personal content store untouched")

        assertTrue(personalQueue(f).isEmpty(), "manifest must not be in the personal queue")
        val sharedBlocks = sharedQueue(f, "shared-1")
        assertEquals(1, sharedBlocks.size, "one manifest in the shared realm's queue")
        assertEquals("shared-1", sharedBlocks.single().ownerId.get())
        val payload = decrypt(f, sharedBlocks.single(), sharedKeys.sharedIdentityKey)
        assertTrue(payload is FileContentManifestPayload, "shared manifest decrypts under the shared identity key")
    }

    @Test
    fun plainTarget_emitsToThePersonalRealm_unchanged() = runTest {
        val f = fixture()
        f.fileDatabase.writeRawByteArray("/personal.jpg", ByteArray(2048) { it.toByte() })

        val ok = f.observer.emitFileContent(
            targetEntityId = DataId.StringDataId.create(
                context = f.proxy.tContext,
                type = SyntheticTestData.ID,
                keyValue = "/personal.jpg",
            ),
            propertyId = "tenshu/file/file/data",
            filePath = "/personal.jpg",
        )
        assertTrue(ok)

        assertEquals(1, f.personalSink.putCount, "personal target → personal content store")
        assertEquals(0, f.sharedSink.putCount, "shared content store untouched for a personal file")
        assertEquals(1, personalQueue(f).size, "manifest in the personal queue")
        assertTrue(sharedQueue(f, "shared-1").isEmpty())
    }

    private fun personalQueue(f: Fixture) = queue(f, SyncService.Collections.PENDING_BLOCKS)
    private fun sharedQueue(f: Fixture, ownerId: String) =
        queue(f, SyncService.Collections.PENDING_BLOCKS + NAMESPACE_SEP + ownerId)

    private fun queue(f: Fixture, relationId: String): List<SyncBlock> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(f.proxy.tContext, SyncBlock.ID, relationId),
            isEntity = false,
            elementSchemaId = SyncBlock.ID,
        )
        return f.proxy.executionContext.getArrayData(ref)?.toList()?.filterIsInstance<SyncBlock>() ?: emptyList()
    }

    private fun decrypt(f: Fixture, block: SyncBlock, key: ByteArray): Data {
        val parser = BinaryParser(f.proxy.tContext.schemaProvider(), f.proxy.tContext.dataFactory())
        val plain = assertNotNull(
            org.tenshu.sync.common.EnvelopeAuth.openSyncBlock(f.proxy.tContext.syncCryptoManager(), block, key),
            "the block must open (envelope-bound) under the given key",
        )
        return parser.parse(BlockPayloadEnvelope.open(plain)!!.payloadBytes)
    }
}
