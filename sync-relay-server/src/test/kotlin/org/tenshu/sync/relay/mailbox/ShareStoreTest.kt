package org.tenshu.sync.relay.mailbox

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Storage-side coverage for the Mode-A share store
 * (`docs/file-sharing.md`).
 *
 * Pins, in the spirit of [ContentBlobStoreTest]:
 *  - create is idempotent on same-owner re-PUT + refuses cross-owner shareId,
 *  - chunks round-trip byte-for-byte and are namespaced per share,
 *  - `open` atomically bumps `opensSoFar` and 410-equivalents (null) on
 *    revoked / expired / cap-exhausted,
 *  - `peek` doesn't bump,
 *  - revoke is owner-scoped + drops new opens immediately,
 *  - GC drops revoked / expired / orphan records strictly past the window and
 *    survives Long.MAX_VALUE.
 */
class ShareStoreTest {

    private fun record(
        shareId: String = "share-abc",
        ownerId: String = "alice",
        chunkCount: Int = 1,
        expiresAtMillis: Long? = null,
        maxOpens: Int? = null,
        createdAtMs: Long = 1_000L,
    ) = ShareStore.ShareRecord(
        shareId = shareId,
        ownerId = ownerId,
        sealedMeta = byteArrayOf(0x5E, 0x41),
        chunkCount = chunkCount,
        chunkHashes = listOf(byteArrayOf(0xAA.toByte())),
        expiresAtMillis = expiresAtMillis,
        maxOpens = maxOpens,
        createdAtMs = createdAtMs,
    )

    @Test
    fun createIdempotent_onSameOwnerRePutKeepsRecord() {
        val store = ShareStore()
        val first = store.create(record())
        val second = store.create(record()) // identical retry
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.shareId, second.shareId)
        assertEquals(1, store.recordCount())
    }

    @Test
    fun createRefusesCrossOwnerShareIdCollision() {
        val store = ShareStore()
        assertNotNull(store.create(record(ownerId = "alice")))
        // Different owner under the same shareId -> the relay refuses.
        val collision = store.create(record(ownerId = "bob"))
        assertNull(collision, "cross-owner shareId collision must be refused")
        assertEquals("alice", store.peek("share-abc")?.ownerId)
    }

    @Test
    fun putThenGetChunk_roundTripsOpaqueBytes() {
        val store = ShareStore()
        store.create(record(chunkCount = 2))
        val cipher = byteArrayOf(0x01, 0x02, 0x03, 0x7F, -0x80)
        store.putChunk("share-abc", chunkIndex = 0, ciphertext = cipher)
        assertContentEquals(cipher, store.getChunk("share-abc", 0))
        assertNull(store.getChunk("share-abc", 1), "absent chunk index is null")
    }

    @Test
    fun openBumpsOpensSoFar_andEnforcesCap() {
        val store = ShareStore()
        store.create(record(maxOpens = 2))
        assertNotNull(store.open("share-abc"))
        assertNotNull(store.open("share-abc"))
        // Third open is over cap.
        assertNull(store.open("share-abc"))
        assertEquals(2, store.peek("share-abc")?.opensSoFar)
    }

    @Test
    fun openReturnsNullForRevokedShare() {
        val store = ShareStore()
        store.create(record())
        store.revoke("share-abc", ownerId = "alice", nowMs = 2_000L)
        assertNull(store.open("share-abc"))
    }

    @Test
    fun openReturnsNullPastExpiry() {
        val store = ShareStore()
        store.create(record(expiresAtMillis = 5_000L))
        assertNotNull(store.open("share-abc", nowMs = 4_999L))
        assertNull(store.open("share-abc", nowMs = 5_000L))
    }

    @Test
    fun peekDoesNotBumpOpensSoFar() {
        val store = ShareStore()
        store.create(record())
        assertEquals(0, store.peek("share-abc")?.opensSoFar)
        assertEquals(0, store.peek("share-abc")?.opensSoFar)
    }

    @Test
    fun revokeIsOwnerScoped_otherOwnersCannotRevoke() {
        val store = ShareStore()
        store.create(record(ownerId = "alice"))
        assertFalse(
            store.revoke("share-abc", ownerId = "bob"),
            "non-owner must not revoke"
        )
        assertNull(store.peek("share-abc")?.revokedAtMs)
        assertTrue(store.revoke("share-abc", ownerId = "alice"))
        assertNotNull(store.peek("share-abc")?.revokedAtMs)
    }

    @Test
    fun getChunkReturnsNullAfterRevoke() {
        val store = ShareStore()
        store.create(record())
        store.putChunk("share-abc", 0, byteArrayOf(0x42))
        assertNotNull(store.getChunk("share-abc", 0))
        store.revoke("share-abc", ownerId = "alice", nowMs = 2_000L)
        assertNull(store.getChunk("share-abc", 0))
    }

    @Test
    fun gcSweepDropsRevokedRecordsStrictlyPastWindow() {
        val store = ShareStore(revokedRetentionWindowMs = 10_000L)
        store.create(record(createdAtMs = 0L))
        store.putChunk("share-abc", 0, byteArrayOf(0x42))
        store.revoke("share-abc", ownerId = "alice", nowMs = 1_000L)
        // Boundary: window since revoke, not strictly past.
        assertEquals(0, store.gcSweep(nowMs = 11_000L))
        assertNotNull(store.peek("share-abc"))
        val dropped = store.gcSweep(nowMs = 11_001L)
        assertEquals(1, dropped)
        assertNull(store.peek("share-abc"))
        assertNull(store.getChunk("share-abc", 0), "chunks dropped with record")
    }

    @Test
    fun gcSweepDropsExpiredRecordsStrictlyPastExpiryPlusWindow() {
        val store = ShareStore(revokedRetentionWindowMs = 5_000L)
        store.create(record(expiresAtMillis = 1_000L))
        // Window since expiry, not strictly past.
        assertEquals(0, store.gcSweep(nowMs = 6_000L))
        assertNotNull(store.peek("share-abc"))
        assertEquals(1, store.gcSweep(nowMs = 6_001L))
        assertNull(store.peek("share-abc"))
    }

    @Test
    fun gcSweepDropsOrphanRecords_butKeepsRecordsWithChunks() {
        val store = ShareStore(revokedRetentionWindowMs = 5_000L)
        store.create(record(shareId = "orphan", createdAtMs = 0L))
        store.create(record(shareId = "live", createdAtMs = 0L))
        store.putChunk("live", 0, byteArrayOf(0x01))
        val dropped = store.gcSweep(nowMs = 5_001L)
        assertEquals(1, dropped, "orphan dropped, live kept")
        assertNull(store.peek("orphan"))
        assertNotNull(store.peek("live"))
    }

    @Test
    fun gcSweepNeverCollectsWhenWindowIsMaxValue() {
        val store = ShareStore(revokedRetentionWindowMs = Long.MAX_VALUE)
        store.create(record())
        store.revoke("share-abc", ownerId = "alice", nowMs = 1L)
        assertEquals(0, store.gcSweep(nowMs = Long.MAX_VALUE))
        assertNotNull(store.peek("share-abc"))
    }

    @Test
    fun takedown_inMemoryTwin_matchesTheDurableOne() {
        val store = ShareStore()
        store.create(record(shareId = "s1", chunkCount = 2))
        store.putChunk("s1", 0, byteArrayOf(1, 2))
        store.create(record(shareId = "s2"))
        store.putChunk("s2", 0, byteArrayOf(3))

        val first = store.takeDown("s1", TakedownReason.IP_INFRINGEMENT, "t-1", nowMs = 5L)
        assertEquals(TakedownOutcome.TAKEN_DOWN, first.record.outcome)
        assertEquals("alice", first.record.ownerId)
        assertEquals(2L, first.bytesDeleted)
        assertNull(store.open("s1", nowMs = 6L))
        assertNull(store.getChunk("s1", 0, nowMs = 6L))
        assertNotNull(store.open("s2", nowMs = 6L), "another share is untouched")

        assertEquals(TakedownOutcome.ALREADY_TAKEN_DOWN, store.takeDown("s1", TakedownReason.OTHER, null, 7L).record.outcome)
        val unknown = store.takeDown("nope", TakedownReason.OTHER, null, 8L).record
        assertEquals(TakedownOutcome.NOT_FOUND, unknown.outcome)
        assertNull(unknown.ownerId)
        assertEquals(2, store.takedowns("s1").size)
        assertTrue(store.revoke("s1", "alice", nowMs = 9L), "the owner's revoke still succeeds")
    }

    @Test
    fun takedown_inMemory_retiresTheId_andDropsALateChunk() {
        val store = ShareStore(revokedRetentionWindowMs = 10L)
        store.create(record(shareId = "s1", chunkCount = 2))
        store.takeDown("s1", TakedownReason.CSAM, null, nowMs = 5L)
        store.putChunk("s1", 1, byteArrayOf(1, 2, 3))
        // The ledger counts the bytes still held: none, the late chunk was dropped as it landed.
        assertEquals(0L, store.eraseOwnerBytes("alice").bytesFreed)
        assertNull(store.peek("s1"), "the row is gone, as after the GC")
        assertNull(store.create(record(shareId = "s1")), "a taken-down id cannot be re-created once its row is gone")
    }
}
