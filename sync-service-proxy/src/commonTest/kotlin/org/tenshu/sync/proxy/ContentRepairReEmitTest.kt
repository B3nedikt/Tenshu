package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.collection.list.toList
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Content repair, demonstrated rather than reasoned** (`drive-upload-stop-and-reclamation.md`
 * §4.18).
 *
 * That section argues a repair is an ordinary re-emit from three facts: `contentId` is a stable
 * value on the entity, `manifestContentRef` is a pure function of it, and the refs are free because
 * they were reclaimed. This shows it.
 *
 * The scenario is the one reclamation creates: content emitted normally, then its blobs dropped
 * from the relay while a live row still points at them.
 *
 * ⚠ What this does *not* cover: a peer's decrypt of the rebuilt manifest, which `FileChunkDecryptor`
 * and `FileContentManifestEmitTest` already pin. What was unproven — and is proven here — is that
 * the rebuilt manifest lands at **the address every other device is already asking for**, and that
 * everything it names is present.
 *
 * ⚠⚠ **And one property deliberately has no test here, because it cannot have an honest one.**
 * §4.18 records that a repair is *not* a byte-identical restore — the emit mints a fresh CEK
 * (`generateSharedRealmMasterKey`), so the ciphertext and therefore the chunk refs are new, leaving
 * the old blobs orphaned. That is true in production and **unobservable in this harness**: the stub
 * crypto manager returns a *deterministic* "random" key, so every emit here reproduces byte-identical
 * ciphertext and reuses the refs. A test asserting either direction would be pinning the stub, so
 * there is none — the claim is stated in the doc and left to the real crypto.
 *
 * ⭐ Worth knowing before writing the obvious test: it fails, and it fails for a reason that has
 * nothing to do with the code under test.
 */
class ContentRepairReEmitTest {

    private val contentId = "cid-repair-0123456789"
    private val path = "/photo.jpg"
    private val bytes = ByteArray(5_000) { (it and 0xFF).toByte() }

    /**
     * ⚠ The real property id, not a hand-written one. A literal such as `"tenshu/file/file/data"`
     * is fine in `FileContentManifestEmitTest` (its entities are synthetic) and wrong here, because
     * this test exists to demonstrate the PRODUCTION path, and the repair only acts on whitelisted
     * properties.
     */
    private val dataPropertyId: String = REPAIRABLE_PROPERTY_IDS.first()

    private class FixedContentAddress(
        private val contentId: String,
        private val contentHash: String,
    ) : ExternalPropertyWriter {
        override fun supports(propertyId: String): Boolean = true
        override fun write(
            executionContext: ExecutionContext,
            targetEntityId: DataId,
            propertyId: String,
            value: Data,
        ) = Unit

        override fun resolveContentId(
            executionContext: ExecutionContext,
            targetEntityId: DataId,
            propertyId: String,
        ): String = contentId

        override fun resolveContentHash(
            executionContext: ExecutionContext,
            targetEntityId: DataId,
            propertyId: String,
        ): String = contentHash
    }

    private class Fixture {
        lateinit var proxy: TestProxy
        lateinit var sink: InMemoryContentBlobStore
        lateinit var fileDatabase: InMemoryFileDatabase
        lateinit var observer: SyncObserver
        lateinit var syncManager: SyncManagerImpl
        lateinit var entityId: DataId
    }

    private fun fixture(): Fixture = Fixture().apply {
        val port = FixedContentAddress(contentId, wholeFileHashOf(bytes))
        proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply {
                registerConfig(syncServiceCommonModule)
            },
            additionalComponents = mapOf(ExternalPropertyWriter.ID to port),
        )
        val crypto = proxy.tContext.syncCryptoManager()
        syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("mock phrase") }
        fileDatabase = InMemoryFileDatabase().apply { writeRawByteArray(path, bytes) }
        sink = InMemoryContentBlobStore(proxy.tContext)
        observer = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = localTestNaming),
            contentBlobStore = sink,
            fileDatabase = fileDatabase,
        )
        entityId = DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, path)
    }

    /** Chunk refs the manifest currently at [manifestContentRef] names. */
    private fun chunkRefsOf(f: Fixture): List<String> {
        val cipher = f.sink.chunks[manifestContentRef(contentId)] ?: return emptyList()
        val parser = BinaryParser(f.proxy.tContext.schemaProvider(), f.proxy.tContext.dataFactory())
        val plain = f.proxy.tContext.syncCryptoManager()
            .decrypt(cipher, f.syncManager.getDerivedKeys()!!.sharedIdentityKey)
        val manifest = parser.parse<Data>(BlockPayloadEnvelope.open(plain)!!.payloadBytes)
            as FileContentManifestPayload
        return manifest.chunks.toList().map { it.ref.get() }
    }

    private fun repairFor(f: Fixture) = localBytesContentRepair(
        readFilePath = { path },
        hasLocalBytes = { f.fileDatabase.fileSize(it) != null },
        // Mirrors production (`SyncProxyInitialization`): the FORCED re-emit. ⚠ With the plain
        // `emitFileContent` this repair is skipped as "already published", so this line must follow
        // production.
        emit = f.observer::repairFileContent,
    )

    /**
     * ⭐ The claim §4.18 rests on: after a reclaim takes the blobs, a device that still holds the
     * plaintext puts the content back **at the same address**, with every chunk it names present.
     */
    @Test
    fun afterAReclaim_theRepairRestoresTheManifestAtTheSameRef() = runTest {
        val f = fixture()
        assertTrue(f.observer.emitFileContent(f.entityId, dataPropertyId, path))

        val manifestRef = manifestContentRef(contentId)
        val originalChunks = chunkRefsOf(f)
        assertTrue(originalChunks.isNotEmpty(), "precondition: the first emit stored chunks")

        // Reclamation: the manifest and every chunk it named are dropped, exactly as
        // a free deletes them (the relay sweep).
        (originalChunks + manifestRef).forEach { f.sink.chunks.remove(it) }
        assertNull(f.sink.chunks[manifestRef], "precondition: the content is gone from the relay")

        assertTrue(repairFor(f).repair(f.entityId, dataPropertyId, contentId))

        // ⚠⚠ Asserted through the READ path a peer actually uses, not through this test's own call
        // to `manifestContentRef`. Checking the map at a ref the test computed itself would only
        // prove that emit and the test agree — both sides would move together if the addressing
        // changed. `fetchManifestCiphertext` is what `SyncProcessor.resolveContentManifest` calls,
        // so this is the reader's question, asked the reader's way.
        val reader = FileContentManifestApplier(
            syncManager = f.syncManager,
            cryptoManager = f.proxy.tContext.syncCryptoManager(),
            fileDatabase = f.fileDatabase,
            contentBlobStore = f.sink,
        )
        assertNotNull(
            reader.fetchManifestCiphertext(contentId),
            "after the repair a reader resolving by contentId must find a manifest",
        )
        assertNotNull(f.sink.chunks[manifestRef])
        val rebuiltChunks = chunkRefsOf(f)
        assertTrue(rebuiltChunks.isNotEmpty())
        for (ref in rebuiltChunks) {
            assertNotNull(f.sink.chunks[ref], "the rebuilt manifest names a chunk that is missing: $ref")
        }
    }

    /**
     * ⚠⚠ The `contentId` must survive the repair untouched. If it did not, every peer's row would
     * still point at the old id and the repair would have healed nothing while looking successful.
     */
    @Test
    fun theContentIdIsUnchanged_soPeerRowsStillResolve() = runTest {
        val f = fixture()
        f.observer.emitFileContent(f.entityId, dataPropertyId, path)
        (chunkRefsOf(f) + manifestContentRef(contentId)).forEach { f.sink.chunks.remove(it) }
        repairFor(f).repair(f.entityId, dataPropertyId, contentId)

        val cipher = assertNotNull(f.sink.chunks[manifestContentRef(contentId)])
        val parser = BinaryParser(f.proxy.tContext.schemaProvider(), f.proxy.tContext.dataFactory())
        val plain = f.proxy.tContext.syncCryptoManager()
            .decrypt(cipher, f.syncManager.getDerivedKeys()!!.sharedIdentityKey)
        val manifest = parser.parse<Data>(BlockPayloadEnvelope.open(plain)!!.payloadBytes)
            as FileContentManifestPayload
        assertEquals(contentId, manifest.fileId.get(), "the repair must not mint a new content id")
        assertEquals(
            wholeFileHashOf(bytes),
            manifest.wholeFileHash.get(),
            "same plaintext ⇒ same whole-file hash across the re-emit",
        )
    }

    /** A device with no local plaintext cannot help, and must not pretend to. */
    @Test
    fun withoutLocalBytes_nothingIsRestored() = runTest {
        val f = fixture()
        f.observer.emitFileContent(f.entityId, dataPropertyId, path)
        (chunkRefsOf(f) + manifestContentRef(contentId)).forEach { f.sink.chunks.remove(it) }
        f.fileDatabase.deleteFile(path)

        assertTrue(!repairFor(f).repair(f.entityId, dataPropertyId, contentId))
        assertNull(
            f.sink.chunks[manifestContentRef(contentId)],
            "a device that cannot help must leave the hole alone",
        )
    }
}
