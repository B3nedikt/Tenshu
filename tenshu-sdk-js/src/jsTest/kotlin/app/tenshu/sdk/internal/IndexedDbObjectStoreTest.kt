package app.tenshu.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.db.ObjectStore
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Persistence + encoding round-trip test for `IndexedDbObjectStore`. Uses a
// uniquely-named IDB database per run so it's independent of any other tests'
// state and any prior runs (Karma's headless Chrome shares an origin's IDB
// across test executions).
//
// The store is opened twice with the same DB name to simulate a "page reload":
// the first open writes (and `flush()`es) entries to disk; the second open
// hydrates a fresh in-memory cache from disk. If the cache hydrates with the
// expected entries, the IDB write + read paths and the DbValue encoding both
// round-trip.
class IndexedDbObjectStoreTest {

    @Test
    fun storedEntries_persistAcrossReopen() = runTest {
        val dbName = "test-${Date.now().toLong()}"
        val scope = CoroutineScope(Dispatchers.Default)

        val first = IndexedDbObjectStore.open(dbName, scope)

        val entry = ObjectStore.DbEntry(
            type = "tenshu/test/sample",
            dataIdHash = 12345678901234L,
            fields = listOf(
                ObjectStore.DbValue.StringValue("hello"),
                ObjectStore.DbValue.LongValue(9007199254740993L), // > 2^53 — string-encoded
                ObjectStore.DbValue.BooleanValue(true),
                ObjectStore.DbValue.ByteArrayValue(byteArrayOf(1, 2, 3, -128, 127)),
                ObjectStore.DbValue.NullValue,
            ),
            properties = mapOf(
                "owner" to ObjectStore.DbValue.StringValue("alice"),
                "version" to ObjectStore.DbValue.LongValue(7L),
            ),
        )

        first.storeObject(entry)
        first.flush()

        // Reopen — this rebuilds the in-memory cache by reading IDB.
        val second = IndexedDbObjectStore.open(dbName, scope)

        val read = second.getObject(entry.dataIdHash) ?: error("entry missing after reopen")
        assertEquals(entry.type, read.type)
        assertEquals(entry.dataIdHash, read.dataIdHash)
        // ByteArray-bearing data classes don't have content equality; compare
        // each value type-by-type so the byte payload uses contentEquals.
        assertEquals(entry.fields.size, read.fields.size)
        entry.fields.zip(read.fields).forEach { (expected, actual) ->
            when (expected) {
                is ObjectStore.DbValue.ByteArrayValue -> {
                    val actualBytes = (actual as ObjectStore.DbValue.ByteArrayValue).value
                    assertContentEquals(expected.value, actualBytes)
                }
                else -> assertEquals(expected, actual)
            }
        }
        assertEquals(entry.properties, read.properties)
    }

    @Test
    fun removedEntries_doNotResurrect() = runTest {
        val dbName = "test-${Date.now().toLong()}-rm"
        val scope = CoroutineScope(Dispatchers.Default)

        val first = IndexedDbObjectStore.open(dbName, scope)
        val entry = ObjectStore.DbEntry(
            type = "tenshu/test/sample",
            dataIdHash = 42L,
            fields = listOf(ObjectStore.DbValue.StringValue("ephemeral")),
        )
        first.storeObject(entry)
        first.flush()
        first.removeObject(entry.dataIdHash)
        first.flush()

        val second = IndexedDbObjectStore.open(dbName, scope)
        assertNull(second.getObject(entry.dataIdHash))
    }
}
