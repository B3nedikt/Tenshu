package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.FileChunkRef
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip + integrity coverage for `docs/adr/0017-file-content-two-plane-channel.md`
 * Phase **P2** — the two-plane bounded-memory file-content *read* path.
 *
 * Completes the cross-device round-trip P1 left open: P1 emits a
 * manifest + chunk ciphertexts; P2 receives the manifest, fetches
 * **only that file's** chunks by ref, decrypts + integrity-gates them,
 * and reassembles the file byte-identically into `FileDatabase` with
 * `O(CHUNK)` peak memory on the read side too. The relay content store
 * is the in-memory `ContentBlobStore` (P1's fake, serving back what was
 * `PUT`) — a self-contained desktop proof; the real relay store + GC is
 * P3.
 *
 * The headline test [roundTrip_multiFile_byteIdentical_boundedAndSelective]
 * proves: byte-identical reassembly, bounded read peak, and
 * selective-fetch (opening file A never `getChunk`s file B's refs).
 * The negatives prove a partial / tampered set never surfaces.
 */
class FileContentReadPathTest {

    // ---- shared "realm" (sender + receiver share the recovery phrase
    // ---- so derived identity/volume keys match — a paired realm) ----
    private val phrase = "mock phrase"
    private val chunkSize = 2 * 1024 * 1024 // mirror SyncObserver.chunkSizeBytes
    private val propertyId = "tenshu/file/file/data"

    private lateinit var contentSink: InMemoryContentBlobStore

    // sender
    private lateinit var sender: TestProxy
    private lateinit var senderSync: SyncManagerImpl
    /** EI-1: this harness HAS realms, so its registries must use wire naming, not [localTestNaming]. */
    private lateinit var senderWireIds: WireIdResolver
    private lateinit var senderFiles: InMemoryFileDatabase
    private lateinit var observer: SyncObserver

    // receiver (fresh proxy/db — models a different device)
    private lateinit var receiver: TestProxy
    private lateinit var receiverSync: SyncManagerImpl
    private lateinit var receiverWireIds: WireIdResolver
    private lateinit var receiverFiles: InMemoryFileDatabase
    private lateinit var receiverTombstones: TombstoneRegistry
    private lateinit var processor: SyncProcessor
    private lateinit var pathWriter: StubPathWriter

    /**
     * Stand-in for `:file-service-proxy`'s `FileBytesPropertyWriter`
     * (the P2 applier resolves the destination path through the
     * `:core-common` `ExternalPropertyWriter` port so it stays
     * decoupled from `:file-service`'s `File` type — this test
     * exercises that exact seam). Maps a saved entity's DataId-hash →
     * its FileDatabase path.
     */
    private class StubPathWriter(private val supported: String) : ExternalPropertyWriter, TComponent {
        val paths = mutableMapOf<String, String>() // entityIdHash -> path
        override fun supports(propertyId: String): Boolean = propertyId == supported
        override fun write(
            executionContext: ExecutionContext,
            targetEntityId: DataId,
            propertyId: String,
            value: Data
        ) { /* not used by the P2 manifest path */ }

        override fun resolveExternalPath(
            executionContext: ExecutionContext,
            targetEntityId: DataId,
            propertyId: String
        ): String? = paths[targetEntityId.getDataIdHash().toString()]
    }

    @BeforeTest
    fun setup() {
        sender = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        sender.schemaRegistry.registerConfig(syncServiceCommonModule)
        senderSync = SyncManagerImpl(
            context = sender.tContext,
            cryptoManager = sender.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate()
        )
        senderSync.initialize(phrase)
        senderWireIds = WireIdResolver(senderSync)
        senderFiles = InMemoryFileDatabase()
        contentSink = InMemoryContentBlobStore(sender.tContext)
        observer = SyncObserver(
            executionContext = sender.executionContext,
            syncManager = senderSync,
            cryptoManager = sender.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(sender.executionContext, wireIdFor = senderWireIds::ofOrNull),
            contentBlobStore = contentSink,
            fileDatabase = senderFiles
        )

        pathWriter = StubPathWriter(propertyId)
        receiver = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            additionalComponents = mapOf(ExternalPropertyWriter.ID to pathWriter)
        )
        receiver.schemaRegistry.registerConfig(syncServiceCommonModule)
        receiverSync = SyncManagerImpl(
            context = receiver.tContext,
            cryptoManager = receiver.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate()
        )
        receiverSync.initialize(phrase)
        receiverWireIds = WireIdResolver(receiverSync)
        receiverFiles = InMemoryFileDatabase()
        receiverTombstones = TombstoneRegistry(receiver.executionContext, wireIdFor = receiverWireIds::ofOrNull)
        processor = SyncProcessor(
            wireIdResolver = WireIdResolver(senderSync),
            executionContext = receiver.executionContext,
            syncManager = receiverSync,
            cryptoManager = receiver.tContext.syncCryptoManager(),
            tombstoneRegistry = receiverTombstones,
            manifestApplier = FileContentManifestApplier(
                syncManager = receiverSync,
                cryptoManager = receiver.tContext.syncCryptoManager(),
                fileDatabase = receiverFiles,
                contentBlobStore = contentSink // shared = the "relay" content store
            )
        )
    }

    private fun entityId(name: String): DataId =
        DataId.StringDataId.create(
            context = sender.tContext,
            type = SyntheticTestData.ID,
            keyValue = name
        )

    /** Emit a file through the P1 path; return its (sender) entity id. */
    private suspend fun emitFile(name: String, bytes: ByteArray): DataId {
        senderFiles.writeRawByteArray(name, bytes)
        val id = entityId(name)
        val ok = observer.emitFileContent(
            targetEntityId = id,
            propertyId = propertyId,
            filePath = name
        )
        assertTrue(ok, "P1 emit should succeed for $name")
        return id
    }

    private fun pendingManifestBlocks(): List<SyncBlock> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = sender.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID
        )
        return sender.executionContext.getArrayData(ref)?.toList()
            ?.filterIsInstance<SyncBlock>() ?: emptyList()
    }

    private fun decodeManifest(block: SyncBlock): FileContentManifestPayload {
        val parser = BinaryParser(
            receiver.tContext.schemaProvider(),
            receiver.tContext.dataFactory()
        )
        val plain = EnvelopeAuth.openSyncBlock(
            receiver.tContext.syncCryptoManager(),
            block,
            receiverSync.getDerivedKeys()!!.sharedIdentityKey
        )!!
        return parser.parse<Data>(BlockPayloadEnvelope.open(plain)!!.payloadBytes) as FileContentManifestPayload
    }

    /**
     * Register the file's entity on the receiver (so the tombstone
     * registry's hash→DataId reverse index resolves and the stub path
     * writer can map it), then re-encrypt the manifest under the
     * receiver's identity key into a fresh SyncBlock the processor
     * ingests — i.e. the realm's metadata-plane delivery of the
     * manifest. Returns the receiver-side entity id.
     */
    private fun deliverEntityAndManifest(
        name: String,
        manifest: FileContentManifestPayload
    ): SyncBlock {
        val entity = Entity.create<SyntheticTestData>(
            context = receiver.tContext,
            composite = SyntheticTestData.Factory.create(receiver.tContext, name, 1L),
            properties = emptyMap()
        )
        receiver.executionContext.saveData(entity)
        val rid = receiver.executionContext.getDataId(entity)!!
        pathWriter.paths[rid.getDataIdHash().toString()] = name

        return EnvelopeAuth.sealSyncBlock(
            crypto = receiver.tContext.syncCryptoManager(),
            context = receiver.tContext,
            blockId = "manifest-$name",
            ownerId = "user-1",
            hlc = receiverSync.hlcManager.tick(),
            targetEntityIdHash = rid.wireIdIn(receiverSync),
            plaintext = BlockPayloadEnvelope.seal(BinarySerializer().serialize(manifest), receiver.versionStampBytes()),
            identityKey = receiverSync.getDerivedKeys()!!.sharedIdentityKey,
        )
    }

    // -----------------------------------------------------------------

    @Test
    fun roundTrip_multiFile_byteIdentical_boundedAndSelective() = runTest {
        // Two multi-chunk "photo" files (3 chunks, last partial).
        val sizeA = (chunkSize * 2) + 4_321
        val sizeB = (chunkSize * 2) + 9_876
        val bytesA = ByteArray(sizeA) { ((it * 31 + 7) and 0xFF).toByte() }
        val bytesB = ByteArray(sizeB) { ((it * 17 + 3) and 0xFF).toByte() }

        emitFile("/a.jpg", bytesA)
        emitFile("/b.jpg", bytesB)

        val blocks = pendingManifestBlocks()
        assertEquals(2, blocks.size, "one manifest per file on the ordered drain")
        // ⚠ Hoisted out of the predicate: resolving inside `first {}` re-derives `K_entity` once
        // per block, which is pure waste in a loop that is only looking for a match.
        val wireA = entityId("/a.jpg").wireIdIn(senderSync)
        val wireB = entityId("/b.jpg").wireIdIn(senderSync)
        val manifestA = blocks.map { decodeManifest(it) }.first { it.fileId.get() == wireA }
        val manifestB = blocks.map { decodeManifest(it) }.first { it.fileId.get() == wireB }
        val refsA = manifestA.chunks.toList().map { it.ref.get() }.toSet()
        val refsB = manifestB.chunks.toList().map { it.ref.get() }.toSet()

        // Deliver BOTH entities + manifests, but only RESOLVE (apply) A.
        deliverEntityAndManifest("/b.jpg", manifestB) // entity present, not applied
        val manifestBlockA = deliverEntityAndManifest("/a.jpg", manifestA)

        processor.processSyncBlockAsync(manifestBlockA)

        // 1) Byte-identical reassembly into the receiver's FileDatabase.
        val got = receiverFiles.readBytes("/a.jpg")?.get()
        assertContentEquals(bytesA, got, "file A reassembled byte-identical")

        // 2) Selective fetch: ONLY A's refs were ever fetched.
        assertTrue(
            contentSink.fetchedRefs.toSet().containsAll(refsA),
            "all of A's chunk refs were fetched"
        )
        assertTrue(
            contentSink.fetchedRefs.none { it in refsB },
            "NO file-B chunk ref was fetched — opening A must not pull B " +
                "(fetched=${contentSink.fetchedRefs}, B=$refsB)"
        )
        assertEquals(
            manifestA.chunkCount.get().toInt(), contentSink.getCount,
            "exactly A's chunk count fetched, nothing prefetched"
        )

        // 3) Bounded read peak: never more than one CHUNK (+ AEAD/AAD
        //    frame overhead) resident from the content store.
        assertTrue(
            contentSink.maxObservedGetBytes <= chunkSize + 1024,
            "read peak must be O(CHUNK) (~$chunkSize), was " +
                "${contentSink.maxObservedGetBytes}"
        )

        // 4) B never surfaced (its manifest was delivered but not resolved).
        assertNull(receiverFiles.readBytes("/b.jpg"), "B must not surface unopened")
        // No partial temp left behind.
        assertNull(receiverFiles.readBytes("/a.jpg.tenshu-partial"))
    }

    @Test
    fun emptyFile_roundTrips() = runTest {
        val id = emitFile("/empty.bin", ByteArray(0))
        val manifest = pendingManifestBlocks().map { decodeManifest(it) }
            .first { it.fileId.get() == id.wireIdIn(senderSync) }
        processor.processSyncBlockAsync(deliverEntityAndManifest("/empty.bin", manifest))
        val got = receiverFiles.readBytes("/empty.bin")?.get()
        assertContentEquals(ByteArray(0), got, "empty file round-trips")
    }

    // ---- integrity negatives: a partial/tampered set never surfaces --

    @Test
    fun missingChunk_fileDoesNotSurface() = runTest {
        val bytes = ByteArray(chunkSize + 1234) { (it and 0xFF).toByte() }
        val id = emitFile("/m.jpg", bytes)
        val manifest = pendingManifestBlocks().map { decodeManifest(it) }
            .first { it.fileId.get() == id.wireIdIn(senderSync) }
        // Relay dropped the last chunk.
        val lastRef = manifest.chunks.toList().last().ref.get()
        contentSink.dropBlob(lastRef)

        val ok = processor.runCatchingApply("/m.jpg", manifest)
        assertFalse(ok, "missing chunk ⇒ apply fails cleanly")
        assertNull(receiverFiles.readBytes("/m.jpg"), "no file surfaces")
        assertNull(receiverFiles.readBytes("/m.jpg.tenshu-partial"), "no partial left")
    }

    @Test
    fun tamperedCiphertext_fileDoesNotSurface() = runTest {
        val bytes = ByteArray(chunkSize + 555) { (it and 0xFF).toByte() }
        val id = emitFile("/t.jpg", bytes)
        val manifest = pendingManifestBlocks().map { decodeManifest(it) }
            .first { it.fileId.get() == id.wireIdIn(senderSync) }
        val firstRef = manifest.chunks.toList().first().ref.get()
        // Relay serves a different blob under that ref (cipherHash gate
        // catches it before an AEAD decrypt is even spent).
        contentSink.tamperBlob(firstRef, ByteArray(64) { 0x5A })

        val ok = processor.runCatchingApply("/t.jpg", manifest)
        assertFalse(ok, "tampered ciphertext ⇒ apply fails cleanly")
        assertNull(receiverFiles.readBytes("/t.jpg"))
        assertNull(receiverFiles.readBytes("/t.jpg.tenshu-partial"))
    }

    @Test
    fun aadMismatch_wrongFileId_fileDoesNotSurface() = runTest {
        val bytes = ByteArray(chunkSize + 99) { (it and 0xFF).toByte() }
        val id = emitFile("/aad.jpg", bytes)
        val good = pendingManifestBlocks().map { decodeManifest(it) }
            .first { it.fileId.get() == id.wireIdIn(senderSync) }
        // Forge a manifest claiming a DIFFERENT fileId but pointing at
        // the same chunk refs. The applier reconstructs the AAD from
        // manifest.fileId, so decryptWithAad sees a context the chunks
        // were NOT encrypted under → AadMismatchException → clean fail.
        val forged = FileContentManifestPayload.create(
            context = receiver.tContext,
            fileId = "not-the-real-file-id",
            propertyId = propertyId,
            chunkCount = good.chunkCount.get().toInt(),
            totalSize = good.totalSize.get(),
            wholeFileHash = good.wholeFileHash.get(),
            chunks = good.chunks.toList(),
            wrappedCek = good.wrappedCek.get(),
        )
        val ok = processor.runCatchingApply("/aad.jpg", forged)
        assertFalse(ok, "AAD (fileId) mismatch ⇒ apply fails cleanly")
        assertNull(receiverFiles.readBytes("/aad.jpg"))
        assertNull(receiverFiles.readBytes("/aad.jpg.tenshu-partial"))
    }

    @Test
    fun wrongWholeFileHash_fileDoesNotSurface() = runTest {
        val bytes = ByteArray(chunkSize + 42) { (it and 0xFF).toByte() }
        val id = emitFile("/w.jpg", bytes)
        val good = pendingManifestBlocks().map { decodeManifest(it) }
            .first { it.fileId.get() == id.wireIdIn(senderSync) }
        // Every chunk verifies (cipherHash + AEAD + AAD all pass) but
        // the whole-file anchor is wrong — a relay that swapped which
        // (validly-encrypted) chunks back which refs would be caught
        // here even though each chunk authenticates.
        val forged = FileContentManifestPayload.create(
            context = receiver.tContext,
            fileId = good.fileId.get(),
            propertyId = propertyId,
            chunkCount = good.chunkCount.get().toInt(),
            totalSize = good.totalSize.get(),
            wholeFileHash = "deadbeef".repeat(8),
            chunks = good.chunks.toList(),
            wrappedCek = good.wrappedCek.get(),
        )
        val ok = processor.runCatchingApply("/w.jpg", forged)
        assertFalse(ok, "wrong whole-file hash ⇒ apply fails cleanly")
        assertNull(receiverFiles.readBytes("/w.jpg"))
        assertNull(receiverFiles.readBytes("/w.jpg.tenshu-partial"))
    }

    /**
     * Deliver the entity + the (possibly forged) manifest and apply it
     * directly via the applier, returning whether the file surfaced.
     * Uses the same wiring `processSyncBlockAsync` would, but lets the
     * negatives assert the boolean outcome precisely.
     */
    private suspend fun SyncProcessor.runCatchingApply(
        name: String,
        manifest: FileContentManifestPayload
    ): Boolean {
        val block = deliverEntityAndManifest(name, manifest)
        processSyncBlockAsync(block)
        // Surfaced iff real bytes are present (the applier promotes the
        // temp onto the real path only when the integrity gate passes).
        return receiverFiles.readBytes(name) != null
    }

    // -----------------------------------------------------------------
    // Phase 3b.2b — the applier decrypts chunks under EXPLICIT realm keys
    // (drive-shared-realm-file-model.md §6 Phase 3b.2). The per-realm
    // SyncProcessor wires an applier with the realm's content store + keys;
    // these prove the `realmKeys` param selects the decrypt key (vs the
    // device's personal keys) and is load-bearing.
    // -----------------------------------------------------------------

    private fun processorWithRealmKeys(realmKeys: SyncCryptoManager.DerivedKeys?): SyncProcessor =
        SyncProcessor(
            wireIdResolver = WireIdResolver(senderSync),
            executionContext = receiver.executionContext,
            syncManager = receiverSync,
            cryptoManager = receiver.tContext.syncCryptoManager(),
            tombstoneRegistry = receiverTombstones,
            manifestApplier = FileContentManifestApplier(
                syncManager = receiverSync,
                cryptoManager = receiver.tContext.syncCryptoManager(),
                fileDatabase = receiverFiles,
                contentBlobStore = contentSink,
                realmKeys = realmKeys,
            ),
        )

    @Test
    fun applierDecryptsUnderExplicitRealmKeys() = runTest {
        val bytes = ByteArray(chunkSize + 321) { ((it * 7 + 1) and 0xFF).toByte() }
        emitFile("/r.jpg", bytes)
        val manifest = pendingManifestBlocks().map { decodeManifest(it) }
            .first { it.fileId.get() == entityId("/r.jpg").wireIdIn(senderSync) }

        // Applier wired with EXPLICIT realm keys (here == the keys the chunks were
        // emitted under); the shared-realm processor path uses exactly this shape.
        val surfaced = processorWithRealmKeys(receiverSync.getDerivedKeys())
            .runCatchingApply("/r.jpg", manifest)

        assertTrue(surfaced, "explicit realm-keyed applier reassembles the file")
        assertContentEquals(bytes, receiverFiles.readBytes("/r.jpg")?.get())
    }

    @Test
    fun wrongRealmKeysFailTheChunkDecryptGate() = runTest {
        val bytes = ByteArray(chunkSize + 99) { ((it * 5 + 2) and 0xFF).toByte() }
        emitFile("/w.jpg", bytes)
        val manifest = pendingManifestBlocks().map { decodeManifest(it) }
            .first { it.fileId.get() == entityId("/w.jpg").wireIdIn(senderSync) }

        // A *different* realm's keys: the manifest block still decrypts (receiver
        // identity key), but the chunks — AEAD'd under the emitting realm's volume
        // key — must fail the applier's authenticated-decrypt gate, so nothing surfaces.
        val otherKeys = SyncManagerImpl(
            context = receiver.tContext,
            cryptoManager = receiver.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("a different realm phrase") }.getDerivedKeys()

        val surfaced = processorWithRealmKeys(otherKeys).runCatchingApply("/w.jpg", manifest)

        assertFalse(surfaced, "wrong realm keys → AEAD gate fails → no bytes surface")
        assertNull(receiverFiles.readBytes("/w.jpg"))
    }
}
