package org.tenshu.sync.relay.mailbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealmStoreTest {

    @Test
    fun createIfAbsent_returnsTrue_whenOwnerIsNew() {
        val store = RealmStore()
        val record = RealmStore.RealmRecord(
            ownerId = "owner-a",
            masterPublicKey = byteArrayOf(1, 2, 3),
            createdAt = 1000L
        )
        assertTrue(store.createIfAbsent(record))
        assertEquals(record, store.get("owner-a"))
    }

    @Test
    fun createIfAbsent_returnsFalse_whenOwnerAlreadyExists() {
        val store = RealmStore()
        val original = RealmStore.RealmRecord("owner-a", byteArrayOf(1), 1000L)
        store.createIfAbsent(original)

        val duplicate = RealmStore.RealmRecord("owner-a", byteArrayOf(9, 9, 9), 9999L)
        assertFalse(store.createIfAbsent(duplicate))
        // Original record is preserved; duplicate is rejected.
        assertEquals(original, store.get("owner-a"))
    }

    @Test
    fun get_unknownOwner_returnsNull() {
        val store = RealmStore()
        assertNull(store.get("never-seen"))
    }

    @Test
    fun differentOwners_storedIndependently() {
        val store = RealmStore()
        val a = RealmStore.RealmRecord("owner-a", byteArrayOf(1), 1000L)
        val b = RealmStore.RealmRecord("owner-b", byteArrayOf(2), 2000L)
        assertTrue(store.createIfAbsent(a))
        assertTrue(store.createIfAbsent(b))
        assertNotNull(store.get("owner-a"))
        assertNotNull(store.get("owner-b"))
        assertEquals(2, store.size())
    }
}
