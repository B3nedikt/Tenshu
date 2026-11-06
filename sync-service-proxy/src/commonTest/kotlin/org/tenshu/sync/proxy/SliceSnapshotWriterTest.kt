package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.EncodedSlice
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SliceSnapshotProducer
import org.tenshu.core.common.proxy.SliceSnapshotProducerRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.RelationSliceSnapshot
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The writer's job is small; its **gate** is not. A snapshot's `asOfSeq` is a promise that
 * everything at or below it is already reflected in the entries, so publishing one from a
 * device that has not read the whole log tells a joiner to skip history nobody has.
 */
class SliceSnapshotWriterTest {

    private class FakeProducer(
        override val relationIds: Set<String> = setOf("tenshu/file/folder/files"),
        val slices: Map<String, EncodedSlice?> = mapOf("/" to EncodedSlice(2, byteArrayOf(1, 2))),
    ) : SliceSnapshotProducer {
        override fun sliceKeys(executionContext: ExecutionContext, relationId: String) =
            slices.keys.toList()

        override fun encodeSlice(
            executionContext: ExecutionContext,
            relationId: String,
            sliceKey: String,
        ): EncodedSlice? = slices[sliceKey]

        /** The writer never reads; the ingest half is [SliceSnapshotReaderTest]'s subject. */
        override fun applySlice(
            executionContext: ExecutionContext,
            relationId: String,
            sliceKey: String,
            entries: ByteArray,
            isDeleted: (DataId) -> Boolean,
        ): Int = 0
    }

    private class Fixture(
        val writer: SliceSnapshotWriter,
        val crypto: org.tenshu.core.security.SyncCryptoManager,
        val store: InMemoryContentBlobStore,
        val syncManager: SyncManagerImpl,
    ) {
        /** The decoded slice at [sliceKey]'s address, or null when there is none or it is declined. */
        fun opened(sliceKey: String): RelationSliceSnapshot? {
            val realm = syncManager.currentRealmContext() ?: return null
            val blob = store.chunks[RelationSliceSnapshot.ref(realm.ownerId, "tenshu/file/folder/files", sliceKey)] ?: return null
            return crypto.decrypt(blob, realm.derivedKeys.sharedIdentityKey)?.let { RelationSliceSnapshot.decode(it) }
        }
    }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        producer: SliceSnapshotProducer = FakeProducer(),
        maxEncodedBytes: Int = RelationSliceSnapshot.MAX_ENCODED_BYTES,
    ): Fixture {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = scope,
            additionalComponents = mapOf(
                SliceSnapshotProducer.ID to SliceSnapshotProducerRegistry(listOf(producer)),
            ),
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("mock phrase")

        val store = InMemoryContentBlobStore(proxy.tContext)
        return Fixture(
            crypto = crypto,
            writer = SliceSnapshotWriter(proxy.executionContext, syncManager, crypto, store, maxEncodedBytes),
            store = store,
            syncManager = syncManager,
        )
    }

    /** The state a device is in once C2's backward walk has finished. */
    private fun SyncManagerImpl.markCaughtUp(atSeq: Long) {
        backfillCursor = 0L
        lastSyncTimestamp = atSeq
        lastTombstoneSyncTimestamp = atSeq
    }

    @Test
    fun writesOneSealedBlobPerSlice() = runTest {
        val f = fixture(backgroundScope)
        f.syncManager.markCaughtUp(atSeq = 500)

        assertEquals(1, f.writer.writeAll())
        assertEquals(1, f.store.chunks.size)
        assertTrue(
            f.store.chunks.keys.single().matches(Regex("^blob-[0-9a-f]{64}$")),
            "the blob ref must match the relay's `blob-<64 hex>` shape, or the relay 400-rejects it",
        )
    }

    @Test
    fun aSliceOverTheBudgetIsNotSealed_andTheOthersStillAre() = runTest {
        // §11.4.4, for every producer at once: the writer measures the encoded envelope, so no
        // service can publish a slice over the budget, whatever its entries hold.
        val f = fixture(
            backgroundScope,
            FakeProducer(
                slices = mapOf(
                    "/" to EncodedSlice(2, ByteArray(100) { 1 }),
                    "/big" to EncodedSlice(40, ByteArray(2_000) { 2 }),
                ),
            ),
            maxEncodedBytes = 1_024,
        )
        f.syncManager.markCaughtUp(atSeq = 500)

        assertEquals(1, f.writer.writeAll())
        assertEquals(2, f.opened("/")?.entryCount, "the slice under the budget is sealed whole")
        assertNull(f.opened("/big"), "the over-budget slice must not reach the relay, cut or whole — only the marker")
    }

    @Test
    fun aSliceExactlyAtTheBudgetIsSealed_andOneByteOverIsNot() = runTest {
        // The boundary: the budget is the largest envelope sealed, so `>`, never `>=`.
        val entries = ByteArray(100) { 1 }
        val atBudget = RelationSliceSnapshot.encode(
            RelationSliceSnapshot(500, "tenshu/file/folder/files", "/a", 2, entries),
        ).size
        val f = fixture(
            backgroundScope,
            FakeProducer(
                slices = mapOf(
                    "/a" to EncodedSlice(2, entries),
                    // Same key length, one entry byte more, so the envelope is exactly one byte longer.
                    "/b" to EncodedSlice(2, ByteArray(101) { 1 }),
                ),
            ),
            maxEncodedBytes = atBudget,
        )
        f.syncManager.markCaughtUp(atSeq = 500)

        assertEquals(1, f.writer.writeAll())
        assertNotNull(f.opened("/a"), "an envelope exactly at the budget is sealed")
        assertNull(f.opened("/b"), "one byte over is not")
    }

    @Test
    fun aSliceThatGrowsOverTheBudgetWithdrawsItsEarlierBlob() = runTest {
        // ⚠⚠ Without the marker the earlier, smaller seal would stay at the address for good, and a
        // joiner would keep seeding from it while it grew older (§11.4.4).
        val slices = mutableMapOf<String, EncodedSlice?>("/" to EncodedSlice(2, ByteArray(100) { 1 }))
        val f = fixture(backgroundScope, FakeProducer(slices = slices), maxEncodedBytes = 1_024)
        f.syncManager.markCaughtUp(atSeq = 500)
        assertEquals(1, f.writer.writeAll())
        assertNotNull(f.opened("/"), "precondition: the first pass sealed it")

        slices["/"] = EncodedSlice(40, ByteArray(2_000) { 2 })
        f.syncManager.markCaughtUp(atSeq = 600)
        assertEquals(0, f.writer.writeAll())

        val realmId = assertNotNull(f.syncManager.currentRealmContext()).ownerId
        val blob = assertNotNull(f.store.chunks[RelationSliceSnapshot.ref(realmId, "tenshu/file/folder/files", "/")])
        val plaintext = assertNotNull(f.crypto.decrypt(blob, f.syncManager.getDerivedKeys()!!.sharedIdentityKey))
        assertContentEquals(RelationSliceSnapshot.withdrawn(), plaintext, "the address holds the withdrawn marker")
        assertNull(RelationSliceSnapshot.decode(plaintext), "which every reader declines")
    }

    @Test
    fun theBudgetIsUnderTheRelaysBlobCap() {
        // The relay refuses a content blob over 8 MiB with 413 (`ContentRouting.MAX_CONTENT_BLOB_BYTES`,
        // which this module cannot see); a budget above it would turn the skip into a PUT that fails
        // on every pass.
        assertTrue(RelationSliceSnapshot.MAX_ENCODED_BYTES + 1_024 < 8 * 1024 * 1024)
    }

    @Test
    fun theBlobIsSealed_notPlaintext() = runTest {
        // HR #1: the relay stores ciphertext. A snapshot carries file names, so shipping it in
        // the clear would hand the relay exactly the tree the encrypted manifest exists to hide.
        val entries = "SECRET-FOLDER-NAME".encodeToByteArray()
        val f = fixture(backgroundScope, FakeProducer(slices = mapOf("/" to EncodedSlice(1, entries))))
        f.syncManager.markCaughtUp(atSeq = 500)
        f.writer.writeAll()

        val stored = f.store.chunks.values.single()
        assertTrue(
            stored.toList().windowed(entries.size).none { it == entries.toList() },
            "the sealed blob must not contain the plaintext entries",
        )
    }

    @Test
    fun refusesToPublishWhileItsOwnBackfillIsIncomplete() = runTest {
        // ⚠⚠ THE gate. A device still walking C2's backward pass has NOT applied everything
        // below its forward cursor — that cursor is the live tail, not a high-water mark. A
        // snapshot published now would tell a joiner to skip history nobody has read, and the
        // joiner would silently miss rows it can never learn about.
        val f = fixture(backgroundScope)
        f.syncManager.lastSyncTimestamp = 500
        f.syncManager.lastTombstoneSyncTimestamp = 500
        f.syncManager.backfillCursor = 400 // still descending

        assertEquals(0, f.writer.writeAll(), "a mid-backfill device must publish nothing")
        assertTrue(f.store.chunks.isEmpty())
    }

    @Test
    fun refusesToPublishBeforeTheBackwardWalkHasEverRun() = runTest {
        // ⚠ `null` is "never walked", distinct from `0L` = "complete". Collapsing them is the
        // mistake C2's own notes warn about, and here it would publish from a device that has
        // read nothing.
        val f = fixture(backgroundScope)
        f.syncManager.lastSyncTimestamp = 500
        f.syncManager.lastTombstoneSyncTimestamp = 500
        f.syncManager.backfillCursor = null

        assertEquals(0, f.writer.writeAll())
    }

    @Test
    fun claimsTheMergedCursor_neverTheBlockCursorAlone() = runTest {
        // ⚠ A snapshot claiming a seq past the applied TOMBSTONES would let a joiner skip a
        // delete and render a file its owner removed — the resurrection class this track has
        // already paid for once. So the claim is the min of the two lanes.
        val f = fixture(backgroundScope)
        f.syncManager.backfillCursor = 0L
        f.syncManager.lastSyncTimestamp = 900
        f.syncManager.lastTombstoneSyncTimestamp = 300

        f.writer.writeAll()

        val sealed = f.store.chunks.values.single()
        val key = assertNotNull(f.syncManager.currentRealmContext()?.derivedKeys?.sharedIdentityKey)
        val plain = assertNotNull(f.crypto.decrypt(sealed, key))
        assertEquals(300, RelationSliceSnapshot.decode(plain)?.asOfSeq)
    }

    @Test
    fun aProducerThatCannotEncodeASliceIsSkipped_notFatal() = runTest {
        // A null slice means "I cannot produce this right now" — the writer skips it and the
        // reader falls back to a relation read. One unproducible folder must not cost the rest.
        val f = fixture(
            backgroundScope,
            FakeProducer(
                slices = linkedMapOf(
                    "/" to EncodedSlice(1, byteArrayOf(1)),
                    "/broken" to null,
                    "/ok" to EncodedSlice(1, byteArrayOf(2)),
                )
            ),
        )
        f.syncManager.markCaughtUp(atSeq = 10)

        assertEquals(2, f.writer.writeAll(), "the unproducible slice is skipped, the others written")
    }

    @Test
    fun aSliceThatCanNoLongerBeProducedWithdrawsItsEarlierBlob() = runTest {
        // A day the window now cuts, a folder that will not read: `null` from the producer. Without
        // the marker the last pass's seal would stay at the address for good (§11.4.4).
        val slices = mutableMapOf<String, EncodedSlice?>("/" to EncodedSlice(2, ByteArray(100) { 1 }))
        val f = fixture(backgroundScope, FakeProducer(slices = slices))
        f.syncManager.markCaughtUp(atSeq = 500)
        assertEquals(1, f.writer.writeAll())
        assertNotNull(f.opened("/"), "precondition: the first pass sealed it")

        slices["/"] = null
        f.syncManager.markCaughtUp(atSeq = 600)
        assertEquals(0, f.writer.writeAll())

        val realmId = assertNotNull(f.syncManager.currentRealmContext()).ownerId
        val blob = assertNotNull(f.store.chunks[RelationSliceSnapshot.ref(realmId, "tenshu/file/folder/files", "/")])
        assertContentEquals(
            RelationSliceSnapshot.withdrawn(),
            f.crypto.decrypt(blob, f.syncManager.getDerivedKeys()!!.sharedIdentityKey),
            "the address holds the withdrawn marker",
        )
    }

    @Test
    fun resealingOverwritesInPlace_ratherThanOrphaningABlob() = runTest {
        // The stable-ref decision, asserted end to end: a second pass at a later seq must land
        // on the SAME address. Otherwise every pass strands its predecessor as garbage no GC
        // collects, and a parent slice's link to a child would have to be rewritten too.
        val f = fixture(backgroundScope)
        f.syncManager.markCaughtUp(atSeq = 10)
        f.writer.writeAll()
        val firstRef = f.store.chunks.keys.single()

        f.syncManager.markCaughtUp(atSeq = 20)
        f.writer.writeAll()

        assertEquals(1, f.store.chunks.size, "a re-seal must overwrite, not add a second blob")
        assertEquals(firstRef, f.store.chunks.keys.single())
    }

}
