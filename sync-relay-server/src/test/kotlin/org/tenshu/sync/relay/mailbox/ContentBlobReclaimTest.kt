package org.tenshu.sync.relay.mailbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Account-lifecycle reclamation (`retention-quota-model.md` Face A / D4) on the in-memory
 * backend.
 *
 * ⚠ This is the one relay primitive that **permanently destroys user content**, and for most
 * owners the relay holds the only durable copy. So the load-bearing tests here are the ones
 * that pin what it must *not* do: not touch another owner, not depend on the retention window,
 * and not accept a blank id.
 */
class ContentBlobReclaimTest {

    @Test
    fun `drops every blob for the owner and reports what it freed`() {
        val store = ContentBlobStore()
        store.put("alice", "blob-1", ByteArray(10), nowMs = 1_000)
        store.put("alice", "blob-2", ByteArray(30), nowMs = 1_000)

        val result = store.reclaimOwner("alice")

        assertEquals(2, result.blobsDropped)
        assertEquals(40L, result.bytesFreed)
        assertNull(store.get("alice", "blob-1", nowMs = 2_000))
        assertNull(store.get("alice", "blob-2", nowMs = 2_000))
        assertEquals(0, store.blobCount("alice"))
        assertEquals(0L, store.bytesUsed("alice"))
    }

    @Test
    fun `leaves every other owner untouched`() {
        val store = ContentBlobStore()
        store.put("alice", "blob-1", ByteArray(10), nowMs = 1_000)
        store.put("bob", "blob-1", ByteArray(20), nowMs = 1_000)

        store.reclaimOwner("alice")

        // ⚠ Same `ref` under a different owner. The keyspace is (ownerId, ref); a reclaim that
        // matched on ref alone would silently delete a stranger's file.
        assertNotNull(store.get("bob", "blob-1", nowMs = 2_000))
        assertEquals(20L, store.bytesUsed("bob"))
    }

    @Test
    fun `works with the time-based GC disabled - the production default`() {
        // ⚠⚠ gcSweep short-circuits to 0 when the retention window is Long.MAX_VALUE (durable
        // content is never time-reaped, and production runs that). Reclaim is a
        // different trigger — an explicit account event — so sharing that guard would make it a
        // silent no-op everywhere it matters.
        val store = ContentBlobStore(contentRetentionWindowMs = Long.MAX_VALUE)
        store.put("alice", "blob-1", ByteArray(10), nowMs = 1_000)

        assertEquals(0, store.gcSweep(nowMs = Long.MAX_VALUE / 2), "time-GC is off")
        assertEquals(1, store.reclaimOwner("alice").blobsDropped, "reclaim still fires")
    }

    @Test
    fun `an unknown owner is a no-op, not an error`() {
        val store = ContentBlobStore()
        store.put("alice", "blob-1", ByteArray(10), nowMs = 1_000)

        // A closure signal for an owner who never stored content must not throw — the caller
        // is a lifecycle event, and it has no way to know whether bytes exist.
        assertEquals(ContentBlobRegistry.ReclaimResult.NOTHING, store.reclaimOwner("nobody"))
        assertEquals(1, store.blobCount("alice"))
    }

    @Test
    fun `a blank owner id throws instead of matching everything`() {
        val store = ContentBlobStore()
        store.put("alice", "blob-1", ByteArray(10), nowMs = 1_000)

        // ⚠ The one input mistake here is unrecoverable, so it fails loudly rather than
        // degrading into "reclaim the world".
        assertFailsWith<IllegalArgumentException> { store.reclaimOwner("") }
        assertFailsWith<IllegalArgumentException> { store.reclaimOwner("   ") }
        assertEquals(1, store.blobCount("alice"), "nothing was touched")
    }

    @Test
    fun `the owner can store again afterwards`() {
        val store = ContentBlobStore()
        store.put("alice", "blob-1", ByteArray(10), nowMs = 1_000)
        store.reclaimOwner("alice")

        // Reclaim frees a keyspace; it does not tombstone the owner. A device that has not yet
        // observed the closure is refused by the entitlement gate, not by a corrupted store.
        store.put("alice", "blob-2", ByteArray(5), nowMs = 3_000)
        assertEquals(1, store.blobCount("alice"))
        assertEquals(5L, store.bytesUsed("alice"))
    }
}
