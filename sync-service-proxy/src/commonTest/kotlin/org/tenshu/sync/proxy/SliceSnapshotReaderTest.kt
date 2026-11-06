package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertTrue

/**
 * The read half of C8. The test that matters is the round trip — what [SliceSnapshotWriter]
 * seals must be exactly what a joiner hands its producer — and after that, that every way a
 * fetch can go wrong costs the device nothing but an ordinary relation read.
 */
class SliceSnapshotReaderTest {

    private val relationId = "tenshu/file/folder/files"

    /** Records what it was asked to apply, so a test can assert the bytes survived the trip. */
    private class RecordingProducer(
        val payload: ByteArray = byteArrayOf(1, 2, 3, 4),
        val seeds: List<String> = listOf("/"),
        /** A slice's own payload, where it should differ from [payload]. */
        val payloads: Map<String, ByteArray> = emptyMap(),
    ) : SliceSnapshotProducer {
        override val relationIds: Set<String> = setOf("tenshu/file/folder/files")
        val applied = mutableListOf<Pair<String, ByteArray>>()

        override fun sliceKeys(executionContext: ExecutionContext, relationId: String) = seeds

        override fun bootstrapSliceKeys(executionContext: ExecutionContext, relationId: String) =
            if (relationId in relationIds) seeds else emptyList()

        override fun encodeSlice(
            executionContext: ExecutionContext,
            relationId: String,
            sliceKey: String,
        ): EncodedSlice? = EncodedSlice(entryCount = 2, entries = payloads[sliceKey] ?: payload)

        override fun applySlice(
            executionContext: ExecutionContext,
            relationId: String,
            sliceKey: String,
            entries: ByteArray,
            isDeleted: (DataId) -> Boolean,
        ): Int {
            applied += sliceKey to entries
            return entries.size
        }
    }

    private class Fixture(
        val reader: SliceSnapshotReader,
        val writer: SliceSnapshotWriter,
        val producer: RecordingProducer,
        val store: InMemoryContentBlobStore,
        val syncManager: SyncManagerImpl,
        val crypto: org.tenshu.core.security.SyncCryptoManager,
    )

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        producer: RecordingProducer = RecordingProducer(),
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
            reader = SliceSnapshotReader(proxy.executionContext, syncManager, crypto, store, isDeleted = { false }),
            writer = SliceSnapshotWriter(proxy.executionContext, syncManager, crypto, store, maxEncodedBytes),
            producer = producer,
            store = store,
            syncManager = syncManager,
            crypto = crypto,
        )
    }

    /** A device mid-catch-up: it has read part of the log and is still walking backwards. */
    private fun SyncManagerImpl.markCatchingUp() {
        backfillCursor = 400
        backfillTarget = 0
        lastSyncTimestamp = 500
        lastTombstoneSyncTimestamp = 500
    }

    private fun SyncManagerImpl.markCaughtUp(atSeq: Long) {
        backfillCursor = 0L
        lastSyncTimestamp = atSeq
        lastTombstoneSyncTimestamp = atSeq
    }

    @Test
    fun readsBackExactlyWhatTheWriterSealed() = runTest {
        // The round trip through real sealing and a real ref: if the address, the key or either
        // codec disagreed between the two halves, this is where it shows.
        val seal = fixture(backgroundScope)
        seal.syncManager.markCaughtUp(atSeq = 500)
        assertEquals(1, seal.writer.writeAll())

        val join = fixture(backgroundScope)
        join.syncManager.markCatchingUp()
        // Same realm phrase ⇒ same derived keys and same ownerId, which is what makes one
        // device's blob readable by the other.
        seal.store.chunks.forEach { (ref, bytes) -> join.store.chunks[ref] = bytes }

        assertTrue(join.reader.bootstrapOnce() > 0)
        val (sliceKey, entries) = join.producer.applied.single()
        assertEquals("/", sliceKey)
        assertContentEquals(seal.producer.payload, entries, "the entries must survive byte for byte")
    }

    @Test
    fun aJoinerSeesNoSliceForOneOverTheBudget_andEveryOtherSliceWhole() = runTest {
        // The bounded slice from the joiner's side (§11.4.4): the writer skipped "/big", so the
        // joiner finds nothing at its address and falls back to the relation read; "/" arrives
        // whole. Never a part of "/big".
        val seeds = listOf("/", "/big")
        val payloads = mapOf("/big" to ByteArray(4_096) { 9 })
        val seal = fixture(backgroundScope, RecordingProducer(seeds = seeds, payloads = payloads), maxEncodedBytes = 1_024)
        seal.syncManager.markCaughtUp(atSeq = 500)
        assertEquals(1, seal.writer.writeAll())

        val join = fixture(backgroundScope, RecordingProducer(seeds = seeds))
        join.syncManager.markCatchingUp()
        seal.store.chunks.forEach { (ref, bytes) -> join.store.chunks[ref] = bytes }

        join.reader.bootstrapOnce()
        val (sliceKey, entries) = join.producer.applied.single()
        assertEquals("/", sliceKey)
        assertContentEquals(seal.producer.payload, entries)
    }

    @Test
    fun aSliceOverTheBudgetFromAnOlderPeerIsStillApplied() = runTest {
        // A peer on a build before the budget seals any size the relay takes (its cap is 8 MiB).
        // The slice is honest, only big, and applying it writes one ordinary row per entry, so
        // the reader takes it rather than refuse it.
        val big = ByteArray(RelationSliceSnapshot.MAX_ENCODED_BYTES + 1) { 5 }
        val older = fixture(backgroundScope, RecordingProducer(payload = big), maxEncodedBytes = Int.MAX_VALUE)
        older.syncManager.markCaughtUp(atSeq = 500)
        assertEquals(1, older.writer.writeAll())

        val join = fixture(backgroundScope)
        join.syncManager.markCatchingUp()
        older.store.chunks.forEach { (ref, bytes) -> join.store.chunks[ref] = bytes }

        assertEquals(big.size, join.reader.bootstrapOnce())
        assertContentEquals(big, join.producer.applied.single().second)
    }

    @Test
    fun aMissingSliceAppliesNothing() = runTest {
        // The common case on a realm no device has ever snapshotted, and it must be silent:
        // the joiner simply replays the log as it always did.
        val f = fixture(backgroundScope)
        f.syncManager.markCatchingUp()

        assertEquals(0, f.reader.bootstrapOnce())
        assertTrue(f.producer.applied.isEmpty())
    }

    @Test
    fun aBlobItCannotDecryptAppliesNothing() = runTest {
        // A slice sealed under a key this device no longer holds (GR-7 rotation) is not an
        // error — the log carries the same rows.
        val f = fixture(backgroundScope)
        f.syncManager.markCatchingUp()
        f.store.chunks[RelationSliceSnapshot.ref(realmId(f), relationId, "/")] = ByteArray(64) { 3 }

        assertEquals(0, f.reader.bootstrapOnce())
        assertTrue(f.producer.applied.isEmpty())
    }

    @Test
    fun anEnvelopeForADifferentSliceIsNeverAppliedUnderThisOne() = runTest {
        // ⚠ The address is a hash, so this cannot happen by collision — but a blob that decodes
        // into *some other* slice must not be handed to a producer as this one, or a folder
        // would render another folder's children.
        val f = fixture(backgroundScope)
        f.syncManager.markCatchingUp()
        val realm = assertNotNull(f.syncManager.currentRealmContext())
        val impostor = RelationSliceSnapshot(
            asOfSeq = 10,
            relationId = relationId,
            sliceKey = "/somewhere-else",
            entryCount = 1,
            entries = byteArrayOf(7),
        )
        f.store.chunks[RelationSliceSnapshot.ref(realm.ownerId, relationId, "/")] =
            f.crypto.encrypt(
                RelationSliceSnapshot.encode(impostor),
                realm.derivedKeys.sharedIdentityKey,
            )

        assertEquals(0, f.reader.bootstrapOnce())
        assertTrue(f.producer.applied.isEmpty())
    }

    @Test
    fun aDeviceThatHasFinishedCatchingUpDoesNotFetchAtAll() = runTest {
        // A snapshot can only tell a caught-up device things it already has, and the applier is
        // create-only — so the fetch would be pure cost.
        val f = fixture(backgroundScope)
        f.syncManager.markCaughtUp(atSeq = 900)
        f.writer.writeAll() // a slice IS available…

        assertEquals(0, f.reader.bootstrapOnce()) // …and is deliberately not read
        assertTrue(f.producer.applied.isEmpty())
    }

    @Test
    fun theBootstrapIsOneShot() = runTest {
        val seal = fixture(backgroundScope)
        seal.syncManager.markCaughtUp(atSeq = 500)
        seal.writer.writeAll()

        val join = fixture(backgroundScope)
        join.syncManager.markCatchingUp()
        seal.store.chunks.forEach { (ref, bytes) -> join.store.chunks[ref] = bytes }

        assertTrue(join.reader.bootstrapOnce() > 0)
        assertEquals(0, join.reader.bootstrapOnce(), "a second tick must not re-fetch")
        assertEquals(1, join.producer.applied.size)
    }

    @Test
    fun theCursorsAreLeftEntirelyToC2() = runTest {
        // ⚠⚠ The correction to §11.4. Raising `lastSyncTimestamp` to the snapshot's `asOfSeq`
        // would make `backfillOnce` capture THAT as its `backfillTarget` at handover and stop
        // the backward walk there — declaring everything below it applied, when a per-slice
        // snapshot covered one slice. Rows outside the snapshot would be unreachable forever.
        val seal = fixture(backgroundScope)
        seal.syncManager.markCaughtUp(atSeq = 900)
        seal.writer.writeAll()

        val join = fixture(backgroundScope)
        join.syncManager.markCatchingUp()
        seal.store.chunks.forEach { (ref, bytes) -> join.store.chunks[ref] = bytes }

        assertTrue(join.reader.bootstrapOnce() > 0)

        assertEquals(500, join.syncManager.lastSyncTimestamp, "the forward cursor is C2's alone")
        assertEquals(500, join.syncManager.lastTombstoneSyncTimestamp)
        assertEquals(400, join.syncManager.backfillCursor, "the backward walk keeps its floor")
        assertEquals(0, join.syncManager.backfillTarget, "and its stopping point")
    }

    private fun realmId(f: Fixture): String =
        assertNotNull(f.syncManager.currentRealmContext()).ownerId
}
