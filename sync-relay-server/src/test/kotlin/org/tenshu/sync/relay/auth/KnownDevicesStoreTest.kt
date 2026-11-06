package org.tenshu.sync.relay.auth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnownDevicesStoreTest {

    @Test
    fun registerAndLookup() {
        val store = KnownDevicesStore()
        val key = byteArrayOf(1, 2, 3)
        store.register("owner-a", "device-1", key)

        assertContentEquals(key, store.getPublicKey("owner-a", "device-1"))
    }

    @Test
    fun lookup_unknownOwner_returnsNull() {
        val store = KnownDevicesStore()
        store.register("owner-a", "device-1", byteArrayOf(1))
        assertNull(store.getPublicKey("owner-b", "device-1"))
    }

    @Test
    fun lookup_unknownDeviceForKnownOwner_returnsNull() {
        val store = KnownDevicesStore()
        store.register("owner-a", "device-1", byteArrayOf(1))
        assertNull(store.getPublicKey("owner-a", "device-2"))
    }

    @Test
    fun register_replacesPreviousKey() {
        val store = KnownDevicesStore()
        store.register("owner-a", "device-1", byteArrayOf(1))
        store.register("owner-a", "device-1", byteArrayOf(9, 9, 9))

        assertContentEquals(byteArrayOf(9, 9, 9), store.getPublicKey("owner-a", "device-1"))
        assertEquals(1, store.size())
    }

    @Test
    fun revoke() {
        val store = KnownDevicesStore()
        store.register("owner-a", "device-1", byteArrayOf(1))
        store.revoke("owner-a", "device-1")
        assertNull(store.getPublicKey("owner-a", "device-1"))
        assertEquals(0, store.size())
    }

    @Test
    fun revoke_isIdempotent() {
        // Idempotency matters because the relay may receive the same revocation tombstone
        // multiple times via `getTombstones` re-pulls before tombstone GC kicks in.
        val store = KnownDevicesStore()
        store.register("owner-a", "device-1", byteArrayOf(1))
        store.revoke("owner-a", "device-1")
        store.revoke("owner-a", "device-1")  // second call must not throw
        assertNull(store.getPublicKey("owner-a", "device-1"))
    }
}
