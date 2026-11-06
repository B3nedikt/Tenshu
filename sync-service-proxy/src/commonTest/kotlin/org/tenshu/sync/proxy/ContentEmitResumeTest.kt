package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.PendingContentEmit
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `tracks/track-identity-and-reference.md` P1: an aborted emit **resumes** instead of
 * restarting.
 *
 * ⚠⚠ What this is worth, measured on a phone: restarting cost **3.00×** the file's size
 * on the relay for one 20 MiB upload over a flaky link, and left 41,945,160 B that no mechanism can
 * reach — each attempt re-keys with a fresh CEK and re-`PUT`s every chunk
 * (`content-plane-refcount.md` §9.9b).
 *
 * ⚠ `chunkTransferConcurrency = 1` throughout, so "the first `PUT` lands and the second fails" names
 * a *specific* chunk. At the production default of 4 the transfers overlap and which index survives
 * is a race, which would make every assertion below probabilistic.
 */
class ContentEmitResumeTest {

    private val path = "/big.bin"
    private val propertyId = "tenshu/file/file/data"

    /** 5 MiB against a 2 MiB chunk = three chunks, so a partial set is expressible. */
    private fun bytesOf(seed: Int) = ByteArray(5 * 1024 * 1024) { ((it + seed) % 251).toByte() }

    /** Supplies the contentId + whole-file hash the emit reads back through the port. */
    private class ResumeWriter(
        var contentId: String,
        var contentHash: String,
    ) : ExternalPropertyWriter {
        override fun supports(propertyId: String): Boolean = true
        override fun write(
            executionContext: org.tenshu.core.common.proxy.ExecutionContext,
            targetEntityId: DataId,
            propertyId: String,
            value: org.tenshu.core.Data,
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

    private class Harness(
        val proxy: TestProxy,
        val sink: InMemoryContentBlobStore,
        val observer: SyncObserver,
        val fdb: InMemoryFileDatabase,
        val port: ResumeWriter,
    )

    private fun harness(bytes: ByteArray): Harness {
        val port = ResumeWriter("cid-resume", wholeFileHashOf(bytes))
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
        return Harness(
            proxy = proxy,
            sink = sink,
            observer = SyncObserver(
                executionContext = proxy.executionContext,
                syncManager = sm,
                cryptoManager = crypto,
                tombstoneRegistry = TombstoneRegistry(proxy.executionContext, wireIdFor = localTestNaming),
                contentBlobStore = sink,
                fileDatabase = fdb,
                chunkTransferConcurrency = 1,
            ),
            fdb = fdb,
            port = port,
        )
    }

    private fun entityId(proxy: TestProxy): DataId =
        DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, path)

    private fun parked(h: Harness, id: DataId): PendingContentEmit? =
        h.proxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = h.proxy.tContext,
                type = PendingContentEmit.ID,
                // ⚠ Keyed on the content, not on the row.
                keyValue = PendingContentEmit.contentIdKeyOf(h.port.contentId, propertyId),
            )
        ) as? PendingContentEmit

    @Test
    fun anAbortedEmitParksWhatLandedAndTheRetrySendsOnlyTheRest() = runTest {
        val h = harness(bytesOf(0))
        val id = entityId(h.proxy)

        // One chunk lands, the link drops.
        h.sink.failPutsAfterCount = 1
        assertFalse(h.observer.emitFileContent(id, propertyId, path))
        val firstAttempt = h.sink.attemptedRefs.toList()
        val landedRef = h.sink.chunks.keys.single()

        val entry = parked(h, id)
        assertTrue(entry != null, "the aborted emit parks its intent")
        val hashes = entry!!.landedChunkHashes.get().split(",")
        assertEquals(3, hashes.size, "one segment per chunk")
        assertTrue(hashes[0].isNotBlank(), "chunk 0 landed and is recorded")
        assertTrue(hashes[1].isBlank() && hashes[2].isBlank(), "chunks 1 and 2 did not")
        assertTrue(entry.wrappedCek.get().isNotBlank(), "the CEK its chunk landed under is parked")

        // The link comes back.
        h.sink.failPutsAfterCount = null
        h.sink.attemptedRefs.clear()
        assertTrue(h.observer.emitFileContent(id, propertyId, path))

        assertFalse(
            h.sink.attemptedRefs.contains(landedRef),
            "the chunk that already landed is NOT sent again — the whole point of resuming",
        )
        assertEquals(
            3,
            h.sink.attemptedRefs.size,
            "two remaining chunks + the manifest; a restart would have sent four. " +
                "first attempt was $firstAttempt, second ${h.sink.attemptedRefs}",
        )
        assertTrue(parked(h, id) == null, "a completed emit clears its intent")
    }

    /**
     * ⚠⚠ The guard that makes index-based skipping sound. The drain re-reads *today's* bytes, so a
     * file edited while parked must re-chunk from scratch — resuming across an edit would splice two
     * files' chunks under one manifest, and the AAD would not catch it because the chunk index and
     * count still line up.
     */
    @Test
    fun aFileEditedWhileParkedRestartsFromScratch() = runTest {
        val h = harness(bytesOf(0))
        val id = entityId(h.proxy)

        h.sink.failPutsAfterCount = 1
        assertFalse(h.observer.emitFileContent(id, propertyId, path))
        assertTrue(parked(h, id)!!.landedChunkHashes.get().split(",")[0].isNotBlank())

        // The file changes while the intent is parked — new bytes, new whole-file hash.
        val edited = bytesOf(7)
        h.fdb.writeRawByteArray(path, edited)
        h.port.contentHash = wholeFileHashOf(edited)

        h.sink.failPutsAfterCount = null
        h.sink.attemptedRefs.clear()
        assertTrue(h.observer.emitFileContent(id, propertyId, path))

        assertEquals(
            4,
            h.sink.attemptedRefs.size,
            "all three chunks are re-sent plus the manifest: the parked set describes bytes that no " +
                "longer exist, so none of it may be reused",
        )
    }

    /**
     * ⭐ The case the parking is worth the most in: every chunk landed and only the manifest did
     * not, so a restart would re-encrypt and re-send the whole file to redo one small `PUT`.
     */
    @Test
    fun aManifestFailureParksEveryChunkThatLanded() = runTest {
        val h = harness(bytesOf(0))
        val id = entityId(h.proxy)

        h.sink.failPutsForRefs += manifestContentRef("cid-resume")
        assertFalse(h.observer.emitFileContent(id, propertyId, path))

        val hashes = parked(h, id)!!.landedChunkHashes.get().split(",")
        assertEquals(3, hashes.size)
        assertTrue(hashes.none { it.isBlank() }, "all three chunks landed and are recorded: $hashes")

        h.sink.failPutsForRefs.clear()
        h.sink.attemptedRefs.clear()
        assertTrue(h.observer.emitFileContent(id, propertyId, path))

        assertEquals(
            listOf(manifestContentRef("cid-resume")),
            h.sink.attemptedRefs,
            "only the manifest is re-sent",
        )
    }

    /**
     * ⚠⚠ Wrapped under the volume key, byte-identical to what the manifest carries — never the raw
     * CEK. Raw would add no *practical* exposure (this device already holds the plaintext and the
     * volume key), but it would make the journal entry useful on its own, and
     * `PendingContentEmit`'s contract is that it never is.
     */
    @Test
    fun theParkedCekIsWrappedNotRaw() = runTest {
        val h = harness(bytesOf(0))
        val id = entityId(h.proxy)

        h.sink.failPutsAfterCount = 1
        assertFalse(h.observer.emitFileContent(id, propertyId, path))

        val wrapped = parked(h, id)!!.wrappedCek.get()
        assertTrue(wrapped.isNotBlank(), "a resumable emit parks its key")
        assertTrue(
            wrapped.length > 64,
            "a raw 32-byte CEK is 64 hex chars; the parked value carries the AEAD nonce and tag too, " +
                "so anything that short would mean the key went in unwrapped (was ${wrapped.length})",
        )
    }
}
