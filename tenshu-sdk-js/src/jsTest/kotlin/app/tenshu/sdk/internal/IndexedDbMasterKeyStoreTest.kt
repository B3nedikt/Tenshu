package app.tenshu.sdk.internal

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.security.WrappedMasterKey
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Persistence test for `IndexedDbMasterKeyStore`. Uses a uniquely-named
// IDB database per run so it's independent of any prior runs (Karma's
// headless Chrome shares an origin's IDB across executions).
//
// The store is opened twice with the same DB name to simulate a "page
// reload": the first open writes (and `flush()`es) entries to disk; the
// second open hydrates a fresh in-memory cache from disk. If the cache
// hydrates with the expected entries, the IDB write + read paths
// round-trip the wrapped-master-key bytes.
class IndexedDbMasterKeyStoreTest {

    @Test
    fun storedEntries_persistAcrossReopen() = runTest {
        val dbName = "test-master-keys-${Date.now().toLong()}"
        val ownerId = "owner-alpha"
        // Synthetic 41-byte blob (24 nonce + 1 ciphertext + 16 tag) — the
        // store doesn't interpret bytes, just persists them. Contents
        // chosen to exercise positive and negative byte values so the
        // Int8Array round-trip is visible.
        val blob = WrappedMasterKey(
            ByteArray(41) { i -> (if (i % 2 == 0) i else -i).toByte() },
        )

        val first = IndexedDbMasterKeyStore.open(dbName)
        first.put(ownerId, blob)
        first.flush()

        val second = IndexedDbMasterKeyStore.open(dbName)
        val read = second.get(ownerId)

        assertNotNull(read, "entry missing after reopen")
        assertContentEquals(blob.bytes, read.bytes)
    }

    @Test
    fun multipleOwners_persistIndependently() = runTest {
        val dbName = "test-master-keys-multi-${Date.now().toLong()}"
        val blobAlpha = WrappedMasterKey(ByteArray(41) { 0x11 })
        val blobBeta = WrappedMasterKey(ByteArray(50) { 0x22 })

        val first = IndexedDbMasterKeyStore.open(dbName)
        first.put("alpha", blobAlpha)
        first.put("beta", blobBeta)
        first.flush()

        val second = IndexedDbMasterKeyStore.open(dbName)
        assertContentEquals(blobAlpha.bytes, second.get("alpha")?.bytes)
        assertContentEquals(blobBeta.bytes, second.get("beta")?.bytes)
        assertNull(second.get("gamma"))
    }

    @Test
    fun put_replacesExistingEntry() = runTest {
        val dbName = "test-master-keys-replace-${Date.now().toLong()}"
        val ownerId = "owner-replace"
        val original = WrappedMasterKey(ByteArray(41) { 0x33 })
        val replacement = WrappedMasterKey(ByteArray(60) { 0x44 })

        val first = IndexedDbMasterKeyStore.open(dbName)
        first.put(ownerId, original)
        first.put(ownerId, replacement)
        first.flush()

        val second = IndexedDbMasterKeyStore.open(dbName)
        assertContentEquals(replacement.bytes, second.get(ownerId)?.bytes)
    }

    @Test
    fun remove_dropsEntry() = runTest {
        val dbName = "test-master-keys-remove-${Date.now().toLong()}"
        val ownerId = "owner-remove"
        val blob = WrappedMasterKey(ByteArray(41) { 0x55 })

        val first = IndexedDbMasterKeyStore.open(dbName)
        first.put(ownerId, blob)
        first.remove(ownerId)
        first.flush()

        val second = IndexedDbMasterKeyStore.open(dbName)
        assertNull(second.get(ownerId))
    }

    @Test
    fun clearAll_wipesEverything() = runTest {
        val dbName = "test-master-keys-clear-${Date.now().toLong()}"
        val blob = WrappedMasterKey(ByteArray(41) { 0x66 })

        val first = IndexedDbMasterKeyStore.open(dbName)
        first.put("alpha", blob)
        first.put("beta", blob)
        first.flush()

        first.clearAll()

        val second = IndexedDbMasterKeyStore.open(dbName)
        assertNull(second.get("alpha"))
        assertNull(second.get("beta"))
        // And the in-memory cache on the original instance is empty too.
        assertNull(first.get("alpha"))
        assertNull(first.get("beta"))
    }

    @Test
    fun openEmptyStore_returnsNullForUnknownOwner() = runTest {
        val dbName = "test-master-keys-empty-${Date.now().toLong()}"
        val store = IndexedDbMasterKeyStore.open(dbName)
        assertEquals(null, store.get("anything"))
    }
}
