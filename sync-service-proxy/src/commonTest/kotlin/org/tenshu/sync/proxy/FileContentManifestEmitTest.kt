package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.collection.list.toList
import org.tenshu.core.id
import org.tenshu.core.security.AadMismatchException
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.DataBlockPayload
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression + correctness coverage for `docs/adr/0017-file-content-two-plane-channel.md`
 * Phase P1 — the two-plane bounded-memory file-content write path.
 *
 * The headline test is [multiLargeFileUpload_isBoundedMemory_andManifestOnly]:
 * it proves the cure for the confirmed Control OOM (the *whole backlog
 * of full-file blocks* was eagerly materialised every drain). With the
 * two-plane path, file bodies go to the content sink one bounded chunk
 * at a time and the ordered drain only ever carries small manifests.
 */
class FileContentManifestEmitTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var fileDatabase: InMemoryFileDatabase
    private lateinit var contentSink: InMemoryContentBlobStore
    private lateinit var observer: SyncObserver

    // Mirror of SyncObserver.chunkSizeBytes (private, intentionally).
    private val chunkSize = 2 * 1024 * 1024

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

        fileDatabase = InMemoryFileDatabase()
        contentSink = InMemoryContentBlobStore(testProxy.tContext)
        observer = SyncObserver(
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
            contentBlobStore = contentSink,
            fileDatabase = fileDatabase
        )
    }

    private fun fileEntityId(path: String): DataId =
        DataId.StringDataId.create(
            context = testProxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = path
        )

    private fun pendingBlocks(): List<Data> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID
        )
        return testProxy.executionContext.getArrayData(ref)?.toList() ?: emptyList()
    }

    private fun decryptPayload(block: SyncBlock): Data {
        val parser = BinaryParser(
            testProxy.tContext.schemaProvider(),
            testProxy.tContext.dataFactory()
        )
        val plain = EnvelopeAuth.openSyncBlock(
            testProxy.tContext.syncCryptoManager(),
            block,
            syncManager.getDerivedKeys()!!.sharedIdentityKey
        )!!
        return parser.parse<Data>(BlockPayloadEnvelope.open(plain)!!.payloadBytes)
    }

    @Test
    fun multiLargeFileUpload_isBoundedMemory_andManifestOnly() = runTest {
        // Three "large photo" files, each well over a CHUNK so each is
        // multi-chunk — the exact shape that OOM-killed Control when the
        // whole backlog of full-file blocks was drained at once.
        val fileSize = (chunkSize * 2) + 12_345 // 3 chunks, last partial
        val sumOfFiles = fileSize.toLong() * 3

        repeat(3) { i ->
            val path = "/photo-$i.jpg"
            // Distinct, non-trivial bytes so chunk hashes differ.
            val bytes = ByteArray(fileSize) { ((it * 31 + i) and 0xFF).toByte() }
            fileDatabase.writeRawByteArray(path, bytes)
            val ok = observer.emitFileContent(
                targetEntityId = fileEntityId(path),
                propertyId = "tenshu/file/file/data",
                filePath = path
            )
            assertTrue(ok, "emitFileContent should succeed for $path")
        }

        // 1) The PROOF OF THE CURE: peak per-chunk ciphertext the
        //    content sink ever saw never exceeds one CHUNK (+ small AEAD
        //    nonce/tag + AAD-frame overhead) — NOT Σ(file sizes). If the
        //    old whole-file path were still in play the sink would have
        //    seen ~6.3 MB blobs (or pendingBlocks would hold them).
        val overheadCeiling = 1024 // 24B nonce + 16B tag + AAD frame
        assertTrue(
            contentSink.maxObservedChunkBytes <= chunkSize + overheadCeiling,
            "Per-chunk ciphertext must be O(CHUNK) (~$chunkSize), was " +
                "${contentSink.maxObservedChunkBytes}; total bytes across files " +
                "= $sumOfFiles — emit path is NOT bounded if this fails"
        )
        // 3 files × 3 chunks each landed on the content plane.
        assertEquals(9, contentSink.putCount, "9 chunks expected on content plane")

        // 2) The ordered drain holds ONLY the 3 small manifests — no
        //    file body ever entered pendingBlocks.
        val blocks = pendingBlocks().filterIsInstance<SyncBlock>()
        assertEquals(3, blocks.size, "Exactly one manifest block per file")
        for (block in blocks) {
            val payload = decryptPayload(block)
            assertTrue(
                payload is FileContentManifestPayload,
                "pendingBlocks entry must be a manifest, was ${payload.id}"
            )
            // Each manifest is tiny relative to the file (refs+hashes,
            // not bytes): a hard ceiling well under one chunk.
            assertTrue(
                block.encryptedPayload.get().size < 64 * 1024,
                "Manifest block must be small (was ${block.encryptedPayload.get().size}B) — " +
                    "no file body smuggled into the ordered drain"
            )
        }

        // 3) Manifest content is correct: 3 chunks, right total size,
        //    refs point at the ciphertext we actually PUT.
        val manifest = decryptPayload(blocks.first()) as FileContentManifestPayload
        assertEquals(3, manifest.chunkCount.get(), "3 chunks for a 2-CHUNK+tail file")
        assertEquals(fileSize.toLong(), manifest.totalSize.get())
        val refs = manifest.chunks.toList().map { it.ref.get() }
        assertEquals(refs.toSet().size, refs.size, "Chunk refs are distinct")
        for (chunkRef in manifest.chunks.toList()) {
            assertNotNull(
                contentSink.chunks[chunkRef.ref.get()],
                "Manifest ref ${chunkRef.ref.get()} must exist in the content sink"
            )
            assertTrue(
                chunkRef.ref.get().startsWith("blob-"),
                "Ref must be the ciphertext-hash form (oracle-safe)"
            )
        }
    }

    @Test
    fun receiver_safelyIgnoresManifest_p1ToP2Gap() = runTest {
        // A pre-P2 / peer receiver runs SyncProcessor.processSyncBlock,
        // which parses every payload as DataBlockPayload. A manifest is
        // a *distinct* type — the `as? DataBlockPayload` cast yields
        // null and the block is dropped. Assert no crash + nothing
        // applied (the documented, intentional P1→P2 gap).
        val path = "/ignored.bin"
        fileDatabase.writeRawByteArray(path, ByteArray(10) { it.toByte() })
        observer.emitFileContent(
            targetEntityId = fileEntityId(path),
            propertyId = "tenshu/file/file/data",
            filePath = path
        )
        val block = pendingBlocks().filterIsInstance<SyncBlock>().first()

        // Sanity: a manifest is NOT a DataBlockPayload.
        val payload = decryptPayload(block)
        assertTrue(payload is FileContentManifestPayload)
        assertTrue(payload !is DataBlockPayload)

        val processor = SyncProcessor(
            wireIdResolver = WireIdResolver(syncManager),
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull)
        )
        // Must not throw and must not write the file entity locally.
        processor.processSyncBlock(block)
        val stored = testProxy.executionContext.database.getData(fileEntityId(path))
        assertEquals(null, stored, "Manifest must be a no-op on a pre-P2 receiver")
    }

    @Test
    fun emptyFile_stillProducesOneChunkAndManifest() = runTest {
        val path = "/empty.dat"
        fileDatabase.writeRawByteArray(path, ByteArray(0))
        val ok = observer.emitFileContent(
            targetEntityId = fileEntityId(path),
            propertyId = "tenshu/file/file/data",
            filePath = path
        )
        assertTrue(ok)
        assertEquals(1, contentSink.putCount, "Empty file ⇒ one (empty) chunk")
        val manifest = decryptPayload(
            pendingBlocks().filterIsInstance<SyncBlock>().first()
        ) as FileContentManifestPayload
        assertEquals(1, manifest.chunkCount.get())
        assertEquals(0L, manifest.totalSize.get())
    }

    @Test
    fun noOps_whenContentStoreOrFileMissing() = runTest {
        // No content store wired ⇒ emit no-ops (returns false), nothing
        // queued. This is the fallback that keeps non-content test
        // harnesses on the legacy single-block path.
        val noStoreObserver = SyncObserver(
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
            contentBlobStore = null,
            fileDatabase = fileDatabase
        )
        fileDatabase.writeRawByteArray("/x", ByteArray(5))
        assertFalse2(
            noStoreObserver.emitFileContent(fileEntityId("/x"), "p", "/x"),
            "No content store ⇒ no-op"
        )
        // Missing file ⇒ no-op too.
        assertFalse2(
            observer.emitFileContent(fileEntityId("/absent"), "p", "/absent"),
            "Absent file ⇒ no-op"
        )
        assertEquals(0, contentSink.putCount)
        assertTrue(pendingBlocks().isEmpty())
    }

    // ---- per-chunk AEAD + AAD negatives -----------------------------

    @Test
    fun perChunkAead_aadTamper_rejected() {
        val crypto = testProxy.tContext.syncCryptoManager()
        val key = syncManager.getDerivedKeys()!!.sharedVolumeKey
        val plain = ByteArray(4096) { it.toByte() }

        val aad = "tenshu/file-chunk/v1|fileA|0|3".encodeToByteArray()
        val cipher = crypto.encryptWithAad(plain, key, aad)

        // Round-trips with the correct AAD.
        assertTrue(crypto.decryptWithAad(cipher, key, aad).contentEquals(plain))

        // Decrypting with a *different* fileId in the AAD must fail
        // (cross-file splice resistance).
        assertFailsWith<AadMismatchException> {
            crypto.decryptWithAad(
                cipher, key, "tenshu/file-chunk/v1|fileB|0|3".encodeToByteArray()
            )
        }
    }

    @Test
    fun perChunkAead_chunkReorder_rejected() {
        val crypto = testProxy.tContext.syncCryptoManager()
        val key = syncManager.getDerivedKeys()!!.sharedVolumeKey
        val plain = ByteArray(1024) { 7 }

        val cipher0 = crypto.encryptWithAad(
            plain, key, "tenshu/file-chunk/v1|f|0|5".encodeToByteArray()
        )
        // Serving chunk-0 ciphertext as if it were chunk-3 must fail —
        // chunkIndex is bound into the AAD.
        assertFailsWith<AadMismatchException> {
            crypto.decryptWithAad(
                cipher0, key, "tenshu/file-chunk/v1|f|3|5".encodeToByteArray()
            )
        }
    }

    @Test
    fun perChunkAead_truncation_rejected() {
        val crypto = testProxy.tContext.syncCryptoManager()
        val key = syncManager.getDerivedKeys()!!.sharedVolumeKey
        val plain = ByteArray(1024) { 9 }

        // Chunk encrypted as part of a 5-chunk file.
        val cipher = crypto.encryptWithAad(
            plain, key, "tenshu/file-chunk/v1|f|4|5".encodeToByteArray()
        )
        // A relay that truncates the file to 3 chunks would have to
        // present this as chunk 4 of a 3-chunk file — chunkCount is
        // bound into the AAD, so it fails.
        assertFailsWith<AadMismatchException> {
            crypto.decryptWithAad(
                cipher, key, "tenshu/file-chunk/v1|f|4|3".encodeToByteArray()
            )
        }
    }

    // NOTE on ciphertext bit-flip detection: that property belongs to
    // the underlying AEAD primitive's Poly1305 tag (production
    // `DefaultSyncCryptoManager` / XChaCha20-Poly1305) and is already
    // covered by threat-model T-T-02 + `DefaultSyncCryptoManagerTest`.
    // This suite runs against `TestProxy`'s bootstrap XOR crypto stub,
    // which has no authentication tag, so a pure mid-ciphertext bit
    // flip is *not* detectable here — testing it would assert against
    // the stub, not P1. The P1-specific guarantee is that the
    // `(fileId, chunkIndex, chunkCount)` AAD is *bound* and tamper-
    // evident; that is exactly what the three negatives above verify,
    // and they hold even on the stub because the AAD frame travels
    // inside the (here, XOR-) "authenticated" blob and the
    // decrypt-time frame check rejects a mismatch.

    /**
     * Content-addressing Phase 1 (`file-content-addressing.md` §8.3): when the entity is
     * content-addressed (the `ExternalPropertyWriter` port resolves a `contentId`), emit
     * routes the manifest onto the **content plane** at `manifest-<contentId>` — a sibling
     * of the chunks in the same store — and adds **no** mailbox block. And it stamps the
     * manifest's `wholeFileHash` from the port's `contentHash` (computed once at upload),
     * not by re-hashing (§8.4 one-hash-pass). The AAD/routing key is the contentId, not the
     * path-hash, which is what makes a move free.
     */
    @Test
    fun contentAddressed_storesManifestOnContentPlane_withPortHash_andNoMailboxBlock() = runTest {
        val contentId = "cid-0123456789abcdef"
        val path = "/photo.jpg"
        val bytes = ByteArray(5000) { (it and 0xFF).toByte() }
        val expectedHash = wholeFileHashOf(bytes)

        val port = FakeContentAddressWriter(contentId = contentId, contentHash = expectedHash)
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) },
            additionalComponents = mapOf(ExternalPropertyWriter.ID to port),
        )
        val crypto = proxy.tContext.syncCryptoManager()
        val sm = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        ).apply { initialize("mock phrase") }
        val fdb = InMemoryFileDatabase().apply { writeRawByteArray(path, bytes) }
        val sink = InMemoryContentBlobStore(proxy.tContext)
        val obs = SyncObserver(
            executionContext = proxy.executionContext,
            syncManager = sm,
            cryptoManager = crypto,
            tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
            contentBlobStore = sink,
            fileDatabase = fdb,
        )

        val ok = obs.emitFileContent(
            targetEntityId = DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, path),
            propertyId = "tenshu/file/file/data",
            filePath = path,
        )
        assertTrue(ok)

        // The manifest is a content-plane blob at manifest-<contentId> — NOT a mailbox block.
        val manifestRef = manifestContentRef(contentId)
        val manifestCipher = sink.chunks[manifestRef]
        assertNotNull(manifestCipher, "the manifest must be stored on the content plane at $manifestRef")
        assertTrue(pendingBlocksOf(proxy).isEmpty(), "a content-addressed file adds no mailbox block")

        // The stored manifest is keyed + AAD-bound by contentId, and carries the
        // upload-computed hash verbatim (one-hash-pass), not a re-hash.
        val parser = BinaryParser(proxy.tContext.schemaProvider(), proxy.tContext.dataFactory())
        val plain = crypto.decrypt(manifestCipher, sm.getDerivedKeys()!!.sharedIdentityKey)
        val manifest = parser.parse<Data>(BlockPayloadEnvelope.open(plain)!!.payloadBytes) as FileContentManifestPayload
        assertEquals(contentId, manifest.fileId.get(), "AAD/routing fileId must be the contentId")
        assertEquals(expectedHash, manifest.wholeFileHash.get(), "manifest hash must equal the port's contentHash")
        assertTrue(sink.putCount >= 2, "chunk(s) + the manifest were both PUT to the content plane")
    }

    private fun assertFalse2(actual: Boolean, msg: String) =
        assertEquals(false, actual, msg)

    private fun pendingBlocksOf(proxy: TestProxy): List<Data> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = proxy.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID
        )
        return proxy.executionContext.getArrayData(ref)?.toList() ?: emptyList()
    }

    /** Port stub that content-addresses every property with a fixed (contentId, contentHash). */
    private class FakeContentAddressWriter(
        private val contentId: String,
        private val contentHash: String,
    ) : org.tenshu.core.common.proxy.ExternalPropertyWriter {
        override fun supports(propertyId: String): Boolean = true
        override fun write(
            executionContext: org.tenshu.core.common.proxy.ExecutionContext,
            targetEntityId: DataId,
            propertyId: String,
            value: Data,
        ) = Unit
        override fun resolveContentId(
            executionContext: org.tenshu.core.common.proxy.ExecutionContext,
            targetEntityId: DataId,
            propertyId: String,
        ): String = contentId
        override fun resolveContentHash(
            executionContext: org.tenshu.core.common.proxy.ExecutionContext,
            targetEntityId: DataId,
            propertyId: String,
        ): String = contentHash
    }

    /**
     * Locked-window hold + replay for the CONTENT plane (fix for stranded uploads-while-locked):
     * `emitFileContent` called while the realm is locked (`!isEnabled()`) must be **held**, not
     * dropped — it returns false and emits no manifest — then replayed by [SyncObserver.reemitPendingLockedWork]
     * once enabled, producing the content manifest block. The end-to-end version is
     * `LockedUploadReemitSystemTest`; this pins the content mechanism directly and fast.
     */
    @Test
    fun contentEmitWhileLocked_isHeld_thenReemittedOnEnable() = runTest {
        val path = "/held.jpg"
        fileDatabase.writeRawByteArray(path, ByteArray(10) { it.toByte() })

        // Locked: emitFileContent defers (returns false) and emits nothing.
        syncManager.lock()
        val heldResult = observer.emitFileContent(fileEntityId(path), "tenshu/file/file/data", path)
        assertEquals(false, heldResult, "emitFileContent defers (returns false) while the realm is locked")
        assertEquals(0, pendingBlocks().size, "no content manifest is emitted while the realm is locked")

        // Unlock + replay: the held content emit now produces exactly one manifest block.
        syncManager.initialize("mock phrase")
        observer.reemitPendingLockedWork()
        val blocks = pendingBlocks().map { it as SyncBlock }
        assertEquals(1, blocks.size, "the held content emit is replayed as one manifest block on enable")
        assertTrue(
            decryptPayload(blocks.first()) is FileContentManifestPayload,
            "the replayed block is the file-content manifest"
        )
    }

    /**
     * ⚠⚠ The replay drain absorbs a *failure* so one held item cannot abort the rest — but a
     * **cancellation** is not one of those, and a bare `runCatching` caught it too.
     *
     * [SyncObserver.reemitPendingLockedWork] runs on the locked→enabled edge, driven by
     * [SyncScheduler]; `emitFileContent` suspends for a whole file's chunk `PUT`s, so a scheduler
     * whose scope is going away is most likely to be cancelled right there. Swallowed, the drain
     * carried on uploading every remaining held file for a scheduler that had gone.
     *
     * The discriminator is the second file: with the bare guard the first item's cancellation is
     * folded into a `Result` and B is uploaded anyway (one manifest block, no throw); with the
     * guard rethrowing, the drain stops at A and B is left held for the next edge.
     */
    @Test
    fun aCancelledReplayStopsTheDrain_ratherThanUploadingTheRestForNobody() = runTest {
        val cancellingSink = CancelOnFirstPut(contentSink)
        val cancellingObserver = SyncObserver(
            executionContext = testProxy.executionContext,
            syncManager = syncManager,
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            tombstoneRegistry = TombstoneRegistry(
                testProxy.executionContext,
                wireIdFor = WireIdResolver(syncManager)::ofOrNull,
            ),
            contentBlobStore = cancellingSink,
            fileDatabase = fileDatabase,
        )
        fileDatabase.writeRawByteArray("/a.jpg", ByteArray(10) { it.toByte() })
        fileDatabase.writeRawByteArray("/b.jpg", ByteArray(10) { it.toByte() })

        // Both land while the realm is locked, so both are held in order.
        syncManager.lock()
        cancellingObserver.emitFileContent(fileEntityId("/a.jpg"), "tenshu/file/file/data", "/a.jpg")
        cancellingObserver.emitFileContent(fileEntityId("/b.jpg"), "tenshu/file/file/data", "/b.jpg")
        syncManager.initialize("mock phrase")

        assertFailsWith<CancellationException> { cancellingObserver.reemitPendingLockedWork() }

        assertEquals(1, cancellingSink.puts, "precondition: it was cancelled on A's first chunk PUT")
        assertEquals(
            0,
            pendingBlocks().size,
            "B must never have been replayed — the drain stops at the cancellation, it does not " +
                "absorb it and upload the rest for a caller that has gone",
        )
    }

    /**
     * Content sink that cancels the very first chunk `PUT` and then behaves, so a test can put a
     * cancellation exactly where a held replay spends its time. Delegation, not a subclass:
     * [InMemoryContentBlobStore]'s recording behaviour is wanted for every other call.
     */
    private class CancelOnFirstPut(
        private val inner: InMemoryContentBlobStore,
    ) : ContentBlobStore by inner {
        var puts = 0
            private set

        override suspend fun putChunk(ref: String, ciphertext: ByteArray): TResult<Data?> {
            puts++
            if (puts == 1) throw CancellationException("the scheduler's scope went away")
            return inner.putChunk(ref, ciphertext)
        }
    }
}
