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
 * D2b S1 (`docs/proposals/drive-streaming-preview.md` §6) — coverage for
 * [FileChunkDecryptor], the per-chunk fetch + cipherHash-gate + AEAD-decrypt
 * source factored out of [FileContentManifestApplier]. It is the shared unit
 * the reassemble path and the future pull-streaming read path (§3.5) both use,
 * so the contract under test is: each chunk decrypts **in isolation** (the
 * property that makes range-decrypt / seeking possible), and the per-chunk
 * authenticity gate holds on every read.
 *
 * Chunks are produced by the real [SyncObserver.emitFileContent] emit path into
 * an [InMemoryContentBlobStore], so this exercises a genuine round-trip against
 * the same manifest + AAD + cipherHash the emitter wrote.
 */
class FileChunkDecryptorTest {

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

    /** The single manifest queued by the most recent [emit]. */
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

    /** Emit [bytes] at [path] and return its manifest. */
    private suspend fun emit(path: String, bytes: ByteArray): FileContentManifestPayload {
        fileDatabase.writeRawByteArray(path, bytes)
        val ok = observer.emitFileContent(
            targetEntityId = fileEntityId(path),
            propertyId = "tenshu/file/file/data",
            filePath = path
        )
        assertTrue(ok, "emitFileContent should succeed for $path")
        return emittedManifest()
    }

    private fun decryptor(manifest: FileContentManifestPayload) = FileChunkDecryptor(
        cryptoManager = testProxy.tContext.syncCryptoManager(),
        contentBlobStore = contentSink,
        manifest = manifest,
        // Chunks are CEK-encrypted (content-addressing §3.4); unwrap the CEK from the manifest
        // (as the applier's buildDecryptor does) and decrypt with it.
        candidateChunkKeys = listOf(
            testProxy.tContext.syncCryptoManager().decryptWithAad(
                manifest.wrappedCek.get(),
                syncManager.getDerivedKeys()!!.sharedVolumeKey,
                cekWrapAad(manifest.fileId.get()),
            )
        )
    )

    @Test
    fun multiChunkFile_decryptsInOrder_roundTripsExactly() = runTest {
        // 3 chunks, last partial — distinct, non-trivial bytes per offset.
        val size = (chunkSize * 2) + 12_345
        val original = ByteArray(size) { ((it * 31 + 7) and 0xFF).toByte() }
        val manifest = emit("/movie.bin", original)

        val decryptor = decryptor(manifest)
        assertEquals(3, decryptor.chunkCount, "2-CHUNK + tail ⇒ 3 chunks")
        assertTrue(decryptor.isWellFormed)

        val reassembled = ArrayList<Byte>(size)
        for (index in 0 until decryptor.chunkCount) {
            val plain = decryptor.decryptChunk(index)
            assertTrue(plain != null, "chunk $index must decrypt")
            for (b in plain) reassembled.add(b)
        }
        assertContentEquals(
            original, reassembled.toByteArray(),
            "Decrypted chunks must concatenate to the exact original bytes"
        )
    }

    @Test
    fun chunksDecryptIndependently_outOfOrder_seekable() = runTest {
        // The property that makes a pull ReadContentRange source possible (§3.5):
        // a chunk decrypts without having read the ones before it.
        val size = (chunkSize * 2) + 1_000
        val original = ByteArray(size) { ((it * 13 + 1) and 0xFF).toByte() }
        val manifest = emit("/seek.bin", original)

        val decryptor = decryptor(manifest)

        // Read the LAST chunk first (a seek to EOF — the moov-at-tail case), then
        // the first. Both must verify; neither depends on the other.
        val tail = decryptor.decryptChunk(2)
        val head = decryptor.decryptChunk(0)
        assertTrue(tail != null && head != null, "out-of-order reads must both verify")

        assertContentEquals(
            original.copyOfRange(0, chunkSize), head,
            "chunk 0 must be the head slice"
        )
        assertContentEquals(
            original.copyOfRange(chunkSize * 2, size), tail,
            "chunk 2 must be the tail slice (the partial last chunk)"
        )
    }

    @Test
    fun cipherHashMismatch_isCleanGateFail() = runTest {
        // A relay serving a wrong/old blob under a ref: the cipherHash gate trips
        // before an AEAD decrypt is spent ⇒ null, no throw.
        val original = ByteArray(chunkSize + 500) { it.toByte() }
        val manifest = emit("/tampered.bin", original)

        val tamperedRef = manifest.chunks.toList()[1].ref.get()
        contentSink.tamperBlob(tamperedRef, ByteArray(64) { 0xEE.toByte() })

        val decryptor = decryptor(manifest)
        assertTrue(decryptor.decryptChunk(0) != null, "untouched chunk 0 still decrypts")
        assertNull(decryptor.decryptChunk(1), "tampered chunk 1 must be a clean gate fail")
    }

    @Test
    fun missingBlob_isCleanGateFail() = runTest {
        // The relay dropped / never stored a blob — a transient absence, not tamper.
        val original = ByteArray(chunkSize + 10) { (it and 0xFF).toByte() }
        val manifest = emit("/dropped.bin", original)

        val droppedRef = manifest.chunks.toList()[0].ref.get()
        contentSink.dropBlob(droppedRef)

        assertNull(decryptor(manifest).decryptChunk(0), "missing blob ⇒ null")
    }
}
