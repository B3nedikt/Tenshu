package org.tenshu.sync.relay.storage

import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [BlobStore] SPI contract, written once and run against every backend
 * (`sync-relay-persistence.md` §8 Phase 3 / Phase 4; the S3 split is
 * `hosting-provider-economics.md` D10).
 *
 * Until now [FileBlobStore] was only ever exercised *through* its callers
 * (`ContentBlobPersistenceTest`, `ShareStoreTest`), so the SPI itself had no suite an
 * alternative backend could be dropped into — and the properties those callers depend on
 * were implicit. This pins them, so `S3BlobStore` becomes "make this suite pass against
 * MinIO" rather than "re-read every caller and hope".
 *
 * ⚠ Semantics that look like trivia and are not:
 *  - **A zero-length blob is stored, not absent.** [BlobStore.get] distinguishes "no such key"
 *    (null) from "a key holding no bytes" (empty array); a backend that conflates them turns an
 *    empty chunk into a 404 at `GET /content/{ref}`.
 *  - **Keys contain `/`** (`<ownerId>/<ref>`, and `share/<shareId>/<idx>`). A backend may encode
 *    that however it likes, but distinct keys must never collide — including the prefix case,
 *    where one key is a proper prefix of another.
 *  - **[BlobStore.delete] is idempotent**, because the GC deletes index rows then files and may
 *    be re-run over rows whose file is already gone.
 */
abstract class BlobStoreContractTest {

    /** A fresh, empty store. Called once per test; the fixture owns cleanup. */
    protected abstract fun newStore(): BlobStore

    private lateinit var store: BlobStore

    @BeforeTest
    fun createStore() {
        store = newStore()
    }

    @Test
    fun putThenGet_roundTripsExactBytes() {
        store.put("alice/blob-1", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.get("alice/blob-1"))
    }

    @Test
    fun get_absentKey_isNull() {
        assertNull(store.get("alice/never-written"))
    }

    @Test
    fun put_overwrites() {
        // Content addressing means a re-PUT carries identical bytes, so this is normally a no-op —
        // but the SPI promises overwrite, and the share plane's first-write-wins check is built on
        // `exists`, not on put refusing.
        store.put("alice/blob-1", byteArrayOf(1, 2, 3))
        store.put("alice/blob-1", byteArrayOf(9, 9))
        assertContentEquals(byteArrayOf(9, 9), store.get("alice/blob-1"))
    }

    @Test
    fun emptyBlob_isStoredNotAbsent() {
        store.put("alice/empty", ByteArray(0))
        assertTrue(store.exists("alice/empty"), "an empty blob still exists")
        assertContentEquals(ByteArray(0), store.get("alice/empty"))
    }

    @Test
    fun exists_tracksPutAndDelete() {
        assertFalse(store.exists("alice/blob-1"))
        store.put("alice/blob-1", byteArrayOf(7))
        assertTrue(store.exists("alice/blob-1"))
        store.delete("alice/blob-1")
        assertFalse(store.exists("alice/blob-1"))
    }

    @Test
    fun sizeOf_isTheStoredLength_orNullWhenAbsent() {
        assertNull(store.sizeOf("alice/sized"))
        store.put("alice/sized", ByteArray(37))
        assertEquals(37L, store.sizeOf("alice/sized"))
        store.put("alice/empty", ByteArray(0))
        assertEquals(0L, store.sizeOf("alice/empty"), "an empty blob is 0, not absent")
    }

    @Test
    fun delete_isIdempotent() {
        // The retention/orphan GC selects index rows then deletes files; a re-run (or a crash
        // between the two) means deleting a key whose bytes are already gone.
        store.delete("alice/never-written")
        store.put("alice/blob-1", byteArrayOf(7))
        store.delete("alice/blob-1")
        store.delete("alice/blob-1")
        assertNull(store.get("alice/blob-1"))
    }

    @Test
    fun deleteAll_removesEveryListedKey_andSparesTheRest() {
        store.put("alice/blob-1", byteArrayOf(1))
        store.put("alice/blob-2", byteArrayOf(2))
        store.put("alice/keep", byteArrayOf(3))

        store.deleteAll(listOf("alice/blob-1", "alice/blob-2"))

        assertNull(store.get("alice/blob-1"))
        assertNull(store.get("alice/blob-2"))
        assertContentEquals(byteArrayOf(3), store.get("alice/keep"))
    }

    @Test
    fun deleteAll_toleratesAbsentKeys() {
        // Every caller is a GC re-running over rows whose bytes may already be gone — same
        // contract as delete(), which is why a mixed batch must not fail.
        store.put("alice/blob-1", byteArrayOf(1))
        store.deleteAll(listOf("alice/blob-1", "alice/never-written"))
        assertNull(store.get("alice/blob-1"))
        store.deleteAll(listOf("alice/blob-1"))
    }

    @Test
    fun deleteAll_emptyCollection_isANoOp() {
        store.put("alice/keep", byteArrayOf(3))
        store.deleteAll(emptyList())
        assertContentEquals(byteArrayOf(3), store.get("alice/keep"))
    }

    @Test
    open fun deleteAll_spansMoreThanOneBatch() {
        // ⚠ S3's DeleteObjects rejects more than 1,000 keys per request outright, so a backend that
        // batches must split. 1,500 crosses that boundary; a backend that forgot would fail here
        // rather than in production on the first large reclaim.
        val keys = (1..1_500).map { "alice/blob-$it" }
        keys.forEach { store.put(it, byteArrayOf(1)) }

        store.deleteAll(keys)

        assertNull(store.get("alice/blob-1"))
        assertNull(store.get("alice/blob-1500"))
        assertFalse(store.exists("alice/blob-750"))
    }

    @Test
    fun distinctKeys_doNotCollide() {
        // Same ref, different owner: the per-owner keyspace is the isolation boundary
        // (`ContentRouting` resolves ownerId from the verified token), so a backend that
        // flattened these into one object would be a cross-realm read.
        store.put("alice/blob-1", byteArrayOf(1))
        store.put("bob/blob-1", byteArrayOf(2))
        assertContentEquals(byteArrayOf(1), store.get("alice/blob-1"))
        assertContentEquals(byteArrayOf(2), store.get("bob/blob-1"))
    }

    @Test
    fun prefixKeys_doNotCollide() {
        // `share/<shareId>/<idx>` makes prefix pairs reachable: chunk 1 vs chunk 11.
        store.put("share/s1/1", byteArrayOf(1))
        store.put("share/s1/11", byteArrayOf(2))
        assertContentEquals(byteArrayOf(1), store.get("share/s1/1"))
        assertContentEquals(byteArrayOf(2), store.get("share/s1/11"))
        store.delete("share/s1/1")
        assertContentEquals(byteArrayOf(2), store.get("share/s1/11"), "deleting a prefix key spared the longer one")
    }

    @Test
    fun chunkSizedBlob_roundTripsByteExact() {
        // 2 MiB is the real content chunk size (`FileContentChunkSizeBytes`). Pins that nothing
        // truncates or re-encodes on the way through.
        val bytes = Random(20260901).nextBytes(2 * 1024 * 1024)
        store.put("alice/blob-big", bytes)
        val read = store.get("alice/blob-big")
        assertEquals(bytes.size, read?.size)
        assertContentEquals(bytes, read)
    }

    @Test
    fun probe_succeedsOnHealthyStore_andLeavesNoReadableKey() {
        // The probe backs an unauthenticated `/health` (`HealthRouting`). It must be a real write
        // — a read could not tell a full volume from a healthy one (2026-08-14) — and it must not
        // leave anything a later GC would see as an orphan.
        repeat(3) { store.probe() }
        assertNull(store.get("alice/blob-1"))
    }
}

/**
 * The v1 backend: one file per key on the relay's block volume.
 *
 * ⚠⚠ This is the backend whose full-disk behaviour produced the 2026-08-14 incident, and the
 * reason [BlobStore.probe] is a write. `S3BlobStore` will subclass the same contract — but note
 * that passing it does **not** transfer the health story: see `hosting-provider-economics.md` D10.
 */
class FileBlobStoreContractTest : BlobStoreContractTest() {

    private lateinit var root: File

    override fun newStore(): BlobStore {
        root = File.createTempFile("relay-blobstore-contract", "").also { it.delete(); it.mkdirs() }
        return FileBlobStore(root)
    }

    @AfterTest
    fun teardown() {
        root.deleteRecursively()
    }

    @Test
    fun probe_leavesNoFileBehind() {
        // Backend-specific because "leaves nothing behind" is only observable on a store you can
        // list. FileBlobStore.put once leaked one `.tmp` per failed write — 8,682 of them in under
        // two hours — so the probe's own cleanup is pinned, not assumed.
        val before = root.list()?.size ?: 0
        repeat(5) { newStoreProbe() }
        assertEquals(before, root.list()?.size ?: 0, "probe left files behind")
    }

    private fun newStoreProbe() = FileBlobStore(root).probe()
}
