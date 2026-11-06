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
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D2b S2 (`docs/proposals/drive-streaming-preview.md` §3.5) — coverage for the bounded-memory +
 * seek core: [StreamingContentSession.readRange] assembles arbitrary byte ranges over a
 * [FileChunkDecryptor] while holding at most `maxCachedChunks` decrypted chunks, and
 * [StreamingContentRegistry] tracks open sessions by handle.
 *
 * Chunks come from the real [SyncObserver.emitFileContent] emit path, so ranges are validated
 * against genuine AEAD'd 2 MiB chunks + manifest (not a hand-rolled fixture).
 */
class StreamingContentSessionTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var fileDatabase: InMemoryFileDatabase
    private lateinit var contentSink: InMemoryContentBlobStore
    private lateinit var observer: SyncObserver

    private val chunkSize = FileContentChunkSizeBytes

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

    private fun emittedManifest(): FileContentManifestPayload {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID
        )
        val blocks = testProxy.executionContext.getArrayData(ref)
            ?.toList()?.filterIsInstance<SyncBlock>() ?: emptyList()
        val parser = BinaryParser(
            testProxy.tContext.schemaProvider(),
            testProxy.tContext.dataFactory()
        )
        val plain = EnvelopeAuth.openSyncBlock(
            testProxy.tContext.syncCryptoManager(),
            blocks.first(),
            syncManager.getDerivedKeys()!!.sharedIdentityKey
        )!!
        return parser.parse<Data>(BlockPayloadEnvelope.open(plain)!!.payloadBytes) as FileContentManifestPayload
    }

    private suspend fun emit(path: String, bytes: ByteArray): FileContentManifestPayload {
        fileDatabase.writeRawByteArray(path, bytes)
        assertTrue(
            observer.emitFileContent(fileEntityId(path), "tenshu/file/file/data", path),
            "emitFileContent should succeed for $path"
        )
        return emittedManifest()
    }

    private fun session(
        manifest: FileContentManifestPayload,
        maxCachedChunks: Int = 4,
    ) = StreamingContentSession(
        decryptor = FileChunkDecryptor(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            contentBlobStore = contentSink,
            manifest = manifest,
            // Chunks are CEK-encrypted (content-addressing §3.4); unwrap the CEK from the manifest.
            candidateChunkKeys = listOf(
                testProxy.tContext.syncCryptoManager().decryptWithAad(
                    manifest.wrappedCek.get(),
                    syncManager.getDerivedKeys()!!.sharedVolumeKey,
                    cekWrapAad(manifest.fileId.get()),
                )
            )
        ),
        totalSize = manifest.totalSize.get(),
        maxCachedChunks = maxCachedChunks
    )

    @Test
    fun readRange_acrossBoundaries_matchesOriginal() = runTest {
        val size = (chunkSize * 2) + 12_345 // 3 chunks, partial last
        val original = ByteArray(size) { ((it * 31 + 7) and 0xFF).toByte() }
        val s = session(emit("/movie.bin", original))

        // Whole file in one (test-sized) read.
        assertContentEquals(original, s.readRange(0, size), "whole-file range")

        // Sub-range entirely within chunk 0.
        assertContentEquals(
            original.copyOfRange(1_000, 1_000 + 4_096),
            s.readRange(1_000, 4_096),
            "intra-chunk range"
        )

        // Range straddling the chunk-0 / chunk-1 boundary.
        val bStart = chunkSize - 100L
        assertContentEquals(
            original.copyOfRange(bStart.toInt(), bStart.toInt() + 300),
            s.readRange(bStart, 300),
            "range spanning a chunk boundary"
        )

        // Range straddling chunk-1 / chunk-2 (into the partial last chunk).
        val cStart = chunkSize.toLong() * 2 - 50
        assertContentEquals(
            original.copyOfRange(cStart.toInt(), cStart.toInt() + 200),
            s.readRange(cStart, 200),
            "range spanning into the partial last chunk"
        )
    }

    @Test
    fun readRange_clampsAtEof() = runTest {
        val size = chunkSize + 500
        val original = ByteArray(size) { (it and 0xFF).toByte() }
        val s = session(emit("/eof.bin", original))

        // Ask for more than exists past the tail → only the existing bytes.
        assertContentEquals(
            original.copyOfRange(size - 200, size),
            s.readRange((size - 200).toLong(), 4_096),
            "read overlapping EOF returns only existing bytes"
        )
        // Entirely past EOF → empty.
        assertEquals(0, s.readRange(size.toLong(), 1_024)?.size, "read at/after EOF is empty")
        assertEquals(0, s.readRange(size + 10_000L, 1_024)?.size, "read well past EOF is empty")
    }

    @Test
    fun readRange_seekBackwards_independentOfOrder() = runTest {
        val size = (chunkSize * 2) + 1_000
        val original = ByteArray(size) { ((it * 13 + 1) and 0xFF).toByte() }
        val s = session(emit("/seek.bin", original))

        // Tail first (moov-at-end), then head — both correct, order-independent.
        val tailStart = chunkSize.toLong() * 2
        assertContentEquals(original.copyOfRange(tailStart.toInt(), size), s.readRange(tailStart, 1_000))
        assertContentEquals(original.copyOfRange(0, 256), s.readRange(0, 256))
    }

    @Test
    fun cache_isBounded_byMaxCachedChunks() = runTest {
        val size = (chunkSize * 5) + 777 // 6 chunks
        val original = ByteArray(size) { ((it xor 0x5A) and 0xFF).toByte() }
        val s = session(emit("/big.bin", original), maxCachedChunks = 2)

        // Touch every chunk in order; the LRU must never exceed the cap.
        for (i in 0 until 6) {
            assertTrue(s.readRange(i.toLong() * chunkSize, 16) != null)
            assertTrue(
                s.cachedChunkCount <= 2,
                "LRU held ${s.cachedChunkCount} chunks after touching chunk $i (cap 2)"
            )
        }
    }

    @Test
    fun readRange_overTamperedChunk_isNull() = runTest {
        val size = (chunkSize * 2) + 10
        val original = ByteArray(size) { it.toByte() }
        val manifest = emit("/tamper.bin", original)
        contentSink.tamperBlob(manifest.chunks.toList()[1].ref.get(), ByteArray(32) { 0xEE.toByte() })

        val s = session(manifest)
        // A range in the clean chunk 0 still reads.
        assertContentEquals(original.copyOfRange(0, 128), s.readRange(0, 128))
        // A range covering the tampered chunk 1 fails closed.
        assertNull(s.readRange(chunkSize.toLong(), 128), "range over a tampered chunk must be null")
    }

    @Test
    fun applier_openStreamingSession_usesApplierKeys_andReadsLazily() = runTest {
        // The applier→session seam (S2b): a session built via the applier reuses its exact key
        // wiring (here the device's personal volume key) + content store, with no reassemble to
        // disk. Proves OpenContentStream's impl can build a session from a manifest.
        val size = (chunkSize * 2) + 999
        val original = ByteArray(size) { ((it * 7 + 3) and 0xFF).toByte() }
        val manifest = emit("/lazy.mov", original)

        val applier = FileContentManifestApplier(
            syncManager = syncManager,
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            fileDatabase = fileDatabase,
            contentBlobStore = contentSink
        )
        val s = applier.openStreamingSession(manifest)
        assertTrue(s != null, "session opens under the device's derived keys")
        assertEquals(size.toLong(), s.totalSize)
        assertContentEquals(original, s.readRange(0, size), "lazy read round-trips")
        assertContentEquals(
            original.copyOfRange(chunkSize - 10, chunkSize + 10),
            s.readRange((chunkSize - 10).toLong(), 20),
            "boundary-spanning lazy read"
        )
    }

    @Test
    fun registry_open_get_close_clear() {
        val reg = StreamingContentRegistry()
        // A bare session (no reads) is enough to exercise handle lifecycle.
        val dummy = StreamingContentSession(
            decryptor = FileChunkDecryptor(
                cryptoManager = testProxy.tContext.syncCryptoManager(),
                contentBlobStore = contentSink,
                manifest = FileContentManifestPayload.create(
                    context = testProxy.tContext,
                    fileId = "f",
                    propertyId = "p",
                    chunkCount = 1,
                    totalSize = 0L,
                    wholeFileHash = "",
                    chunks = emptyList(),
                    wrappedCek = ByteArray(0),
                ),
                candidateChunkKeys = listOf(syncManager.getDerivedKeys()!!.sharedVolumeKey)
            ),
            totalSize = 0L
        )
        val h1 = reg.open(dummy)
        val h2 = reg.open(dummy)
        assertTrue(h1 != h2, "handles are distinct")
        assertEquals(2, reg.activeCount)
        assertTrue(reg.get(h1) === dummy)
        assertTrue(reg.close(h1))
        assertNull(reg.get(h1))
        assertEquals(false, reg.close(h1), "double close is idempotent-false")
        reg.clear()
        assertEquals(0, reg.activeCount)
    }
}
