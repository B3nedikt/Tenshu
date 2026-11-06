package org.tenshu.sync.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.toTByteArray
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Coverage for the **concurrent** chunk-transfer emit path.
 *
 * Each content-plane `PUT` is a round trip to a single-region relay, so a strictly sequential
 * emit makes a large upload round-trip-bound rather than bandwidth-bound — barely visible from
 * Europe, most of the wall clock from the US or Asia. [SyncObserver] therefore keeps several
 * transfers in flight. Overlapping them is only safe because three things survive it, and each
 * one is asserted below:
 *
 *  1. the manifest's chunk refs stay in **file order** even when transfers complete out of order
 *     (a permuted manifest reassembles into a corrupt file);
 *  2. the whole-file hash-chain — order- *and* boundary-sensitive — still matches a plain
 *     in-memory fold of the same bytes, i.e. chunk *production* did not go concurrent with it;
 *  3. residency stays bounded, so the fan-out cannot walk back the O(CHUNK) guarantee that fixed
 *     the Control OOM.
 *
 * [FileContentManifestEmitTest] owns the rest of the emit contract; this file is only about what
 * concurrency could break.
 */
class FileContentEmitConcurrencyTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var fileDatabase: InMemoryFileDatabase
    private lateinit var sink: RecordingContentBlobStore

    // Mirror of SyncObserver.chunkSizeBytes (private, intentionally).
    private val chunkSize = 2 * 1024 * 1024

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        syncManager = SyncManagerImpl(
            context = testProxy.tContext,
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        syncManager.initialize("mock phrase")

        fileDatabase = InMemoryFileDatabase()
        sink = RecordingContentBlobStore(testProxy.tContext)
    }

    private fun observerWith(concurrency: Int): SyncObserver = SyncObserver(
        executionContext = testProxy.executionContext,
        syncManager = syncManager,
        cryptoManager = testProxy.tContext.syncCryptoManager(),
        tombstoneRegistry = TombstoneRegistry(testProxy.executionContext, wireIdFor = WireIdResolver(syncManager)::ofOrNull),
        contentBlobStore = sink,
        fileDatabase = fileDatabase,
        chunkTransferConcurrency = concurrency,
    )

    private fun fileEntityId(path: String): DataId =
        DataId.StringDataId.create(
            context = testProxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = path,
        )

    /** Writes a [chunks]-chunk file (last chunk partial) and returns its plaintext. */
    private fun writeFile(path: String, chunks: Int): ByteArray {
        val bytes = ByteArray((chunkSize * (chunks - 1)) + 4_321) { ((it * 31) and 0xFF).toByte() }
        fileDatabase.writeRawByteArray(path, bytes)
        return bytes
    }

    private fun manifest(): FileContentManifestPayload {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS,
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID,
        )
        val block = testProxy.executionContext.getArrayData(ref)?.toList()
            ?.filterIsInstance<SyncBlock>()?.single()
            ?: error("expected exactly one manifest block")
        val parser = BinaryParser(testProxy.tContext.schemaProvider(), testProxy.tContext.dataFactory())
        val plain = EnvelopeAuth.openSyncBlock(
            testProxy.tContext.syncCryptoManager(),
            block,
            syncManager.getDerivedKeys()!!.sharedIdentityKey,
        )!!
        return parser.parse<Data>(BlockPayloadEnvelope.open(plain)!!.payloadBytes)
            as FileContentManifestPayload
    }

    @Test
    fun chunkPutsOverlap_andTheManifestStaysInFileOrder() = runTest {
        val path = "/photo.jpg"
        val bytes = writeFile(path, chunks = 7)
        // Later chunks finish first, so completion order is the reverse of production order.
        // Deterministic under runTest's virtual clock; this is the reordering a real network
        // produces, and the condition under which a naive `chunkRefs += ...` would scramble.
        sink.delayForPut = { index -> (10 - index) * 10L }

        val ok = observerWith(concurrency = 4).emitFileContent(
            targetEntityId = fileEntityId(path),
            propertyId = "tenshu/file/file/data",
            filePath = path,
        )
        assertTrue(ok, "emit should succeed")

        // The premise: transfers really did complete out of order. Without this the ordering
        // assertion below would pass trivially on a sequential implementation.
        assertNotEquals(
            sink.startOrder,
            sink.completionOrder,
            "the fake must have reordered completions, else this test proves nothing",
        )
        assertEquals(4, sink.maxConcurrentPuts, "4 transfers should have been in flight")

        // The manifest is in PRODUCTION order — which, because production is the sequential
        // read loop, is file order.
        val refs = manifest().chunks.toList().map { it.ref.get() }
        assertEquals(sink.startOrder, refs, "manifest chunk refs must follow file order")

        // The order-sensitive whole-file anchor still matches a plain in-memory fold of the same
        // bytes: proof the hash chain was folded in order, i.e. chunk production stayed sequential.
        assertEquals(wholeFileHashOf(bytes), manifest().wholeFileHash.get())
        assertEquals(7, manifest().chunkCount.get())
        assertEquals(bytes.size.toLong(), manifest().totalSize.get())
    }

    @Test
    fun concurrencyIsBounded_soResidencyStaysIndependentOfFileSize() = runTest {
        // 20 chunks through a 3-permit window. The point is not the number but that it does not
        // scale with the file: a 200-chunk file would still peak at 3.
        val path = "/big.raw"
        writeFile(path, chunks = 20)
        sink.delayForPut = { 50L }

        val ok = observerWith(concurrency = 3).emitFileContent(
            targetEntityId = fileEntityId(path),
            propertyId = "tenshu/file/file/data",
            filePath = path,
        )

        assertTrue(ok)
        assertEquals(3, sink.maxConcurrentPuts, "never more than the configured permits in flight")
        assertEquals(20, sink.startOrder.size, "every chunk was still transferred")
    }

    @Test
    fun concurrencyOfOneRestoresTheStrictlySequentialPath() = runTest {
        // The kill-switch: if a platform ever dislikes the fan-out, `1` must be a true
        // degradation to the pre-change behaviour, not an approximation of it.
        val path = "/serial.bin"
        writeFile(path, chunks = 5)
        sink.delayForPut = { index -> (10 - index) * 10L }

        val ok = observerWith(concurrency = 1).emitFileContent(
            targetEntityId = fileEntityId(path),
            propertyId = "tenshu/file/file/data",
            filePath = path,
        )

        assertTrue(ok)
        assertEquals(1, sink.maxConcurrentPuts)
        assertEquals(
            sink.startOrder,
            sink.completionOrder,
            "at concurrency 1 nothing can complete out of order",
        )
    }

    @Test
    fun aFailedChunkAbortsTheEmitWithoutSendingTheRest() = runTest {
        val path = "/doomed.jpg"
        writeFile(path, chunks = 12)
        sink.delayForPut = { 10L }
        // Fail the third chunk. The siblings already in flight are cancelled and — the part that
        // matters — production stops, so the remaining chunks are never read or sent for a
        // manifest that can no longer be emitted.
        sink.failAtStartIndex = 2

        val ok = observerWith(concurrency = 4).emitFileContent(
            targetEntityId = fileEntityId(path),
            propertyId = "tenshu/file/file/data",
            filePath = path,
        )

        assertEquals(false, ok, "a failed chunk PUT must abort the emit")
        assertTrue(
            sink.startOrder.size < 12,
            "production must stop on failure; started ${sink.startOrder.size} of 12",
        )
    }

    /**
     * Content-plane fake that makes concurrency observable: it records how many `PUT`s are in
     * flight at once, the order they started in (= production order) and the order they finished
     * in, and can delay each one independently so completions reorder deterministically under
     * `runTest`'s virtual clock.
     *
     * Plain `Int`/`MutableList` state is safe here: `runTest` runs every coroutine on one
     * scheduler thread, so the increments cannot interleave.
     */
    private class RecordingContentBlobStore(private val context: TContext) : ContentBlobStore {

        val chunks = LinkedHashMap<String, ByteArray>()
        val startOrder = mutableListOf<String>()
        val completionOrder = mutableListOf<String>()
        var maxConcurrentPuts = 0
            private set

        /** Virtual-time delay per `PUT`, by start index. Default: instant. */
        var delayForPut: (Int) -> Long = { 0L }

        /** Start index whose `PUT` fails, standing in for a relay rejecting one chunk. */
        var failAtStartIndex: Int? = null

        private var inFlight = 0

        override suspend fun putChunk(ref: String, ciphertext: ByteArray): TResult<Data?> {
            val startIndex = startOrder.size
            startOrder += ref
            inFlight++
            if (inFlight > maxConcurrentPuts) maxConcurrentPuts = inFlight
            try {
                delay(delayForPut(startIndex))
                if (startIndex == failAtStartIndex) {
                    return TResult.Failure.create(
                        context,
                        GenericError.create(context, "content store rejected chunk $startIndex"),
                    )
                }
                chunks[ref] = ciphertext
                completionOrder += ref
                return TResult.Success.create(context, null)
            } finally {
                inFlight--
            }
        }

        override suspend fun getChunk(ref: String): TResult<TByteArray> {
            val bytes = chunks[ref]
                ?: return TResult.Failure.create(
                    context,
                    GenericError.create(context, "content blob not found: $ref"),
                )
            return TResult.Success.create(context, bytes.toTByteArray(context))
        }
    }
}
