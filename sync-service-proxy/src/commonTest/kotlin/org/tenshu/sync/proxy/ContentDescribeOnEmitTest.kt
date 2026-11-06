package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The emit's describe: once its chunks and manifest have landed, an emit tells the relay which refs
 * the content is made of (`content-plane-refcount.md` §6e; `content-plane-cr2-quorumless.md` §10.12 Q3).
 *
 * ⭐ CR-2 B3 removed the edge half of this request. The holder's reference to the content reaches the
 * relay with the holder's own push (`content-plane-cr2-b3-wire-spec.md` §3.1), so an overwrite
 * orphans its predecessor through the relay's `(holder, property)` register — pinned where edges are
 * built (`EdgesOnPushTest`), not here. (Was `ContentEdgeDeclarationTest`; its three edge-only cases
 * pinned the removed behaviour and are gone.)
 */
class ContentDescribeOnEmitTest {

    private class FixedContentAddressWriter(
        var contentId: String,
        private val contentHash: String,
    ) : ExternalPropertyWriter {
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

    private class Harness(
        val proxy: TestProxy,
        val sink: InMemoryContentBlobStore,
        val observer: SyncObserver,
        val port: FixedContentAddressWriter,
        /** EI-1: tests that assert a WIRE name resolve it through this, never by recomputing. */
        val syncManager: SyncManagerImpl,
    )

    private fun harness(contentId: String, bytes: ByteArray, path: String): Harness {
        val port = FixedContentAddressWriter(contentId, wholeFileHashOf(bytes))
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
            ),
            port = port,
            syncManager = sm,
        )
    }

    private fun entityId(proxy: TestProxy, path: String): DataId =
        DataId.StringDataId.create(proxy.tContext, SyntheticTestData.ID, path)

    @Test
    fun anEmitDescribesItsContent() = runTest {
        val path = "/photo.jpg"
        val h = harness("cid-1", ByteArray(4000) { it.toByte() }, path)
        val id = entityId(h.proxy, path)

        assertTrue(h.observer.emitFileContent(id, "tenshu/file/file/data", path))

        assertEquals("cid-1", h.sink.describedContents.single().contentId)
    }

    @Test
    fun theDescriptionCarriesEveryRefTheContentIsMadeOf_chunksAndItsManifest() = runTest {
        // ⭐ CR-4b. A content id alone lets the relay derive exactly ONE ref —
        // the manifest's, `sha256("manifest:" + contentId)` — because that address is a pure
        // function. Every chunk ref is `sha256(ciphertext)` and lives inside the AEAD-encrypted
        // manifest it may not read (HR #1). So without these refs the keep set is unanswerable and
        // ~every blob an owner holds is unclassifiable.
        val path = "/photo.jpg"
        // Three chunks: 2 MiB each plus a remainder, so the assertion is about a SET and not one
        // ref that could be the manifest's by accident.
        val bytes = ByteArray(5 * 1024 * 1024 + 7) { it.toByte() }
        val h = harness("cid-1", bytes, path)
        val id = entityId(h.proxy, path)

        assertTrue(h.observer.emitFileContent(id, "tenshu/file/file/data", path))

        val edge = h.sink.describedContents.single()
        val manifestRef = manifestContentRef("cid-1")
        assertTrue(
            manifestRef in edge.refs,
            "the manifest is a blob of this content too — freeing the chunks and leaving it behind " +
                "just moves the orphan",
        )
        assertEquals(
            h.sink.chunks.keys,
            edge.refs.toSet(),
            "every ref that was PUT must be described, and nothing that was not",
        )
        assertTrue(edge.refs.size >= 4, "3 chunks + a manifest; got ${edge.refs.size}")
    }

    @Test
    fun aReEmitDescribesTheNewRefSet_notTheUnionWithTheOld() = runTest {
        // ⚠⚠ A union would permanently protect exactly the blob this table exists to reclaim: the
        // overwritten content's chunks would stay in the keep set for ever. The relay replaces the
        // set per contentId, and each emit mints a fresh CEK, so the two sets are disjoint anyway —
        // this pins that the client does not accumulate them on its side either.
        val path = "/photo.jpg"
        val h = harness("cid-1", ByteArray(3000) { it.toByte() }, path)
        val id = entityId(h.proxy, path)

        h.observer.emitFileContent(id, "tenshu/file/file/data", path)
        val first = h.sink.describedContents.single().refs.toSet()
        h.port.contentId = "cid-2"
        h.observer.emitFileContent(id, "tenshu/file/file/data", path)
        val second = h.sink.describedContents.last().refs.toSet()

        assertEquals(
            emptySet(),
            first intersect second,
            "a fresh CEK per emit means new ciphertext, new refs — the sets must not overlap",
        )
    }

    @Test
    fun aFailedEmitDescribesNothing() = runTest {
        // ⚠⚠ A ref set is the product of the upload: a ref is `sha256(ciphertext)`, so describing
        // must sit AFTER the content is on the plane. An emit whose manifest never landed has no
        // content object to describe.
        val path = "/photo.jpg"
        val h = harness("cid-1", ByteArray(1000) { it.toByte() }, path)
        // ⚠ The MANIFEST ref alone, not `failPuts`: failing every PUT aborts the emit at the first
        // chunk, so it never reaches the branch this test is about and would pass either way.
        h.sink.failPutsForRefs += manifestContentRef("cid-1")

        assertEquals(
            false,
            h.observer.emitFileContent(entityId(h.proxy, path), "tenshu/file/file/data", path),
        )
        assertTrue(
            h.sink.chunks.isNotEmpty(),
            "precondition: the chunks DID land — it is only the manifest that failed",
        )
        assertTrue(
            h.sink.describedContents.isEmpty(),
            "an emit whose manifest never landed must describe nothing; got ${h.sink.describedContents}",
        )
    }

    @Test
    fun aFailedDescribeDoesNotFailTheEmit() = runTest {
        // ⚠⚠ Deliberate. A `false` is a refused request or a transport failure, and failing the emit
        // for it would make every upload retry for ever. The cost is a live blob with no description,
        // which the backfill heals — and undescribed content is never a candidate (§9.5).
        val path = "/photo.jpg"
        val h = harness("cid-1", ByteArray(1000) { it.toByte() }, path)
        h.sink.failDeclarations = true

        assertTrue(
            h.observer.emitFileContent(entityId(h.proxy, path), "tenshu/file/file/data", path),
            "the emit succeeded — the bytes and the manifest are both on the content plane",
        )
        assertTrue(h.sink.describedContents.isEmpty())
    }
}
