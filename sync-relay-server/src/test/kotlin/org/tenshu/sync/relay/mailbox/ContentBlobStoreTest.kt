package org.tenshu.sync.relay.mailbox

import org.tenshu.sync.relay.RelayConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Storage-side coverage for the content plane + its (disabled-by-default) GC
 * mechanism (`file-content-addressing.md` §4.2 / T-R-09).
 *
 * The content plane is **durable user data**: the production retention window is
 * `Long.MAX_VALUE` (GC off), so nothing is time-reaped — reclamation is
 * account-lifecycle-driven. The tests below drive the GC *mechanism* with explicit
 * finite windows (the seam a future account-lapse/admin reclaim reuses) and pin the
 * default-durable behaviour.
 *
 * Pins, in the spirit of [SyncMailboxGcTest] (the sibling ordered-log GC test):
 *  - PUT then GET round-trips opaque bytes byte-for-byte,
 *  - per-owner isolation (a ref stored under owner A is invisible to owner B),
 *  - re-PUT replaces the ciphertext and refreshes liveness (as `SqliteContentBlobStore` does),
 *  - the default store is **durable** — never reaps, even after a vast gap,
 *  - with an explicit finite window the mechanism still collects untouched blobs
 *    and retains GET-touched ones,
 *  - `Long.MAX_VALUE` retention disables GC and never overflows,
 *  - GC is idempotent (re-running is a no-op).
 */
class ContentBlobStoreTest {

    @Test
    fun putThenGet_roundTripsOpaqueBytes() {
        val store = ContentBlobStore()
        val cipher = byteArrayOf(0x01, 0x02, 0x03, 0x7F, -0x80)
        store.put("alice", "blob-abc", cipher, nowMs = 1_000L)
        val read = store.get("alice", "blob-abc", nowMs = 1_100L)
        assertContentEquals(cipher, read, "GET must return the exact stored ciphertext")
    }

    @Test
    fun get_missingRef_returnsNull() {
        val store = ContentBlobStore()
        assertNull(store.get("alice", "blob-never-stored"))
    }

    @Test
    fun defaultStore_isDurable_neverTimeReaps() {
        // Content is durable user data (file-content-addressing.md §4.2): the DEFAULT
        // retention window is Long.MAX_VALUE, so a blob nobody touches is NEVER reaped —
        // the "backup opened after years → gone" data-loss case must not happen.
        // Reclamation is account-lifecycle-driven (owner over-quota / entitlement lapse),
        // not time-driven.
        assertEquals(Long.MAX_VALUE, RelayConfig.CONTENT_RETENTION_WINDOW_MS)
        val store = ContentBlobStore() // default (durable) window
        store.put("alice", "blob-archive", byteArrayOf(0x42), nowMs = 1_000L)
        assertEquals(
            0,
            store.gcSweep(nowMs = Long.MAX_VALUE / 2), // aeons after the last touch
            "durable content must never be time-reaped by default",
        )
        assertContentEquals(
            byteArrayOf(0x42),
            store.get("alice", "blob-archive", nowMs = Long.MAX_VALUE / 2),
            "the blob is still readable after a vast unopened gap",
        )
    }

    @Test
    fun perOwnerIsolation_refStoredUnderAliceInvisibleToBob() {
        // Same ref string under two owners: each owner has its own namespace.
        // This is the storage-level half of the auth-scoping invariant (the
        // routing-level half — JWT.iss → ownerId — is pinned in
        // ContentRoutingTest).
        val store = ContentBlobStore()
        store.put("alice", "blob-shared-ref", byteArrayOf(0x0A))
        assertNull(
            store.get("bob", "blob-shared-ref"),
            "Bob must not see Alice's blob even under an identical ref"
        )
        // Bob can store his own blob under the same ref without clobbering Alice.
        store.put("bob", "blob-shared-ref", byteArrayOf(0x0B))
        assertContentEquals(byteArrayOf(0x0A), store.get("alice", "blob-shared-ref"))
        assertContentEquals(byteArrayOf(0x0B), store.get("bob", "blob-shared-ref"))
    }

    @Test
    fun rePut_replacesTheCiphertextAndRefreshesTimestamp() {
        // ⚠⚠ Replace, as the production backend (`SqliteContentBlobStore`) does: a ref that is not a
        // hash of its body — a manifest, a relation slice re-sealed or withdrawn in place — must take
        // the new bytes. A retried chunk carries identical bytes, so it is unaffected. The re-PUT still
        // refreshes liveness so a still-uploading file's chunks don't age out mid-upload.
        val store = ContentBlobStore(contentRetentionWindowMs = 1_000L)
        store.put("alice", "blob-x", byteArrayOf(0x11, 0x22), nowMs = 0L)
        val replacement = byteArrayOf(0x33)
        store.put("alice", "blob-x", replacement, nowMs = 5_000L)
        assertContentEquals(replacement, store.get("alice", "blob-x", nowMs = 5_100L))
        assertEquals(1L, store.bytesUsed("alice"), "the replaced bytes are what is counted")
        // Refreshed at 5_000 -> not collectible at 5_500 (window 1_000).
        assertEquals(0, store.gcSweep(nowMs = 5_500L))
        assertContentEquals(replacement, store.get("alice", "blob-x", nowMs = 5_500L))
    }

    @Test
    fun gcSweep_doesNotCollectBlobStillBeingGetTouched() {
        // A blob a live manifest still references keeps being GET-ed; each GET
        // refreshes lastReferencedAt, so the sweep must never collect it. This
        // is the shared-blob safety property: identical ciphertext dedups to
        // one ref referenced by >=1 live manifest -> kept while live.
        val store = ContentBlobStore(contentRetentionWindowMs = 1_000L)
        store.put("alice", "blob-live", byteArrayOf(0x42), nowMs = 0L)
        // Touched at 5_000 via a GET (a device opened a file pointing here).
        assertContentEquals(byteArrayOf(0x42), store.get("alice", "blob-live", nowMs = 5_000L))
        // Sweep at 5_500: only 500ms since the GET, window is 1_000 -> survives.
        assertEquals(0, store.gcSweep(nowMs = 5_500L), "recently-GET-ed blob must survive")
        assertContentEquals(byteArrayOf(0x42), store.get("alice", "blob-live", nowMs = 5_600L))
    }

    @Test
    fun gcSweep_collectsUnreferencedBlobOnlyAfterRetentionStrictlyElapses() {
        // The orphan / deleted-file case: a blob nobody GETs anymore (its file
        // was deleted, manifest tombstoned + drained everywhere, or the upload
        // aborted before the manifest landed). It ages out — but only once the
        // window has STRICTLY elapsed (`> window`, mirroring the tombstone GC's
        // strict deadline).
        val store = ContentBlobStore(contentRetentionWindowMs = 10_000L)
        store.put("alice", "blob-orphan", byteArrayOf(0x79), nowMs = 1_000L)

        // At exactly window boundary (now - last = 10_000, not strictly past).
        assertEquals(0, store.gcSweep(nowMs = 11_000L), "boundary is not strictly past")
        assertContentEquals(byteArrayOf(0x79), store.get("alice", "blob-orphan", nowMs = 11_000L))
        // The GET above refreshed it to 11_000; advance strictly past the window.
        assertEquals(0, store.gcSweep(nowMs = 21_000L), "GET refreshed liveness")
        val dropped = store.gcSweep(nowMs = 21_001L)
        assertEquals(1, dropped, "strictly past window with no touch -> collected")
        assertNull(store.get("alice", "blob-orphan"), "collected blob is gone")
        assertEquals(0, store.blobCount("alice"))
    }

    @Test
    fun gcSweep_neverCollectsWhenRetentionIsMaxValue() {
        // Long.MAX_VALUE = "never GC" (parity with SyncMailbox's tombstone-body
        // GC sentinel) and must not overflow on `now + window`.
        val store = ContentBlobStore(contentRetentionWindowMs = Long.MAX_VALUE)
        store.put("alice", "blob-keep", byteArrayOf(0x01), nowMs = 0L)
        assertEquals(0, store.gcSweep(nowMs = Long.MAX_VALUE))
        assertContentEquals(byteArrayOf(0x01), store.get("alice", "blob-keep"))
    }

    @Test
    fun gcSweep_isIdempotent_secondSweepIsNoop() {
        val store = ContentBlobStore(contentRetentionWindowMs = 1_000L)
        store.put("alice", "blob-1", byteArrayOf(0x01), nowMs = 0L)
        val first = store.gcSweep(nowMs = 10_000L)
        val second = store.gcSweep(nowMs = 10_000L)
        assertEquals(1, first)
        assertEquals(0, second, "second sweep over an empty store is a no-op")
    }

    @Test
    fun gcSweep_collectsAcrossMultipleOwnersIndependently() {
        val store = ContentBlobStore(contentRetentionWindowMs = 1_000L)
        store.put("alice", "blob-a", byteArrayOf(0x0A), nowMs = 0L)
        store.put("bob", "blob-b", byteArrayOf(0x0B), nowMs = 0L)
        // Bob's blob stays live (GET-touched late); Alice's ages out.
        store.get("bob", "blob-b", nowMs = 9_000L)
        val dropped = store.gcSweep(nowMs = 9_500L)
        assertEquals(1, dropped, "only Alice's untouched blob is collected")
        assertNull(store.get("alice", "blob-a"))
        assertContentEquals(byteArrayOf(0x0B), store.get("bob", "blob-b", nowMs = 9_600L))
    }

    @Test
    fun defaultRetentionWindow_isAtLeastTombstoneBodyRetention() {
        // The compose-with-sync-tombstones invariant: content GC must provably
        // outlive the ordered-log tombstone-body GC, so the default content
        // window is >= the tombstone-body retention default. A regression here
        // (shorter content window) would risk collecting a blob a still-
        // draining tombstone could cause a late device to GET.
        val store = ContentBlobStore() // default window
        store.put("alice", "blob-d", byteArrayOf(0x01), nowMs = 0L)
        // One ms short of the tombstone-body retention default: must NOT collect.
        assertEquals(
            0,
            store.gcSweep(nowMs = RelayConfig.DEFAULT_RETENTION_WINDOW_MS),
            "default content retention must be >= tombstone-body retention"
        )
        assertTrue(store.blobCount("alice") == 1)
    }

    // ── §8 Phase 2b per-owner byte quota ────────────────────────────────────

    @Test
    fun bytesUsed_sumsPerOwnerAndDropsWithGc() {
        val store = ContentBlobStore(contentRetentionWindowMs = 100)
        assertEquals(0L, store.bytesUsed("alice"))
        store.put("alice", "blob-1", ByteArray(10), nowMs = 0L)
        store.put("alice", "blob-2", ByteArray(20), nowMs = 0L)
        store.put("bob", "blob-3", ByteArray(99), nowMs = 0L) // isolated per owner
        assertEquals(30L, store.bytesUsed("alice"))
        store.put("alice", "blob-1", ByteArray(10), nowMs = 0L) // idempotent re-PUT, no double count
        assertEquals(30L, store.bytesUsed("alice"))
        store.get("alice", "blob-2", nowMs = 150L) // refresh one; let the other age out
        store.gcSweep(nowMs = 200L)
        assertEquals(20L, store.bytesUsed("alice"), "GC frees the aged-out blob's quota")
    }

    @Test
    fun putWithinQuota_storesUnderQuota_rejectsOver_andIsIdempotent() {
        val store = ContentBlobStore()
        assertEquals(
            ContentBlobRegistry.PutResult.STORED,
            store.putWithinQuota("alice", "blob-1", ByteArray(20), quotaBytes = 30, nowMs = 0L),
        )
        // A new ref totalling 40 > 30 is refused, and nothing is stored for it.
        assertEquals(
            ContentBlobRegistry.PutResult.OVER_QUOTA,
            store.putWithinQuota("alice", "blob-2", ByteArray(20), quotaBytes = 30, nowMs = 0L),
        )
        assertNull(store.get("alice", "blob-2"))
        assertEquals(20L, store.bytesUsed("alice"))
        // Re-PUT of the stored ref is accepted even at quota (idempotent, not re-counted).
        assertEquals(
            ContentBlobRegistry.PutResult.STORED,
            store.putWithinQuota("alice", "blob-1", ByteArray(20), quotaBytes = 20, nowMs = 1L),
        )
        assertEquals(20L, store.bytesUsed("alice"))
    }
}
