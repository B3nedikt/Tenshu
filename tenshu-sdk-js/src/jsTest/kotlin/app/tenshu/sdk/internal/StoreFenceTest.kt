package app.tenshu.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The write fence (`StoreFence.kt`). Two opens of one database stand in
// for two tabs: the first is a holder whose lock was stolen while it was frozen, the second the new
// holder that claimed the database on open. The old one's writes, landing late, must be refused.
class StoreFenceTest {
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun row(hash: Long, value: String) =
        DbEntry(type = "tenshu/test/fence", dataIdHash = hash, fields = listOf(DbValue.StringValue(value)))

    @Test
    fun aStaleHoldersLateWriteIsRefused_andTheNewHoldersRowSurvives() = runTest {
        val dbName = "test-fence-objects-${Date.now().toLong()}"
        val stale = IndexedDbObjectStore.open(dbName, scope)
        val current = IndexedDbObjectStore.open(dbName, scope)

        current.storeObject(row(42L, "new"))
        current.flush()
        // The thawed tab's queue drains AFTER the new holder wrote.
        stale.storeObject(row(42L, "old"))
        stale.storeObject(row(43L, "old-only"))
        stale.flush()

        val reloaded = IndexedDbObjectStore.open(dbName, scope, claim = false)
        assertEquals(listOf<DbValue>(DbValue.StringValue("new")), reloaded.getObject(42L)?.fields)
        assertNull(reloaded.getObject(43L), "nothing the stale holder wrote after the claim lands")
    }

    // A tab frozen inside a purge (`Session.clear({purgeRealm})` / ForgetThisDevice) that thaws
    // after a steal must not wipe the new holder's rows.
    @Test
    fun aStaleHoldersClearIsRefused() = runTest {
        val dbName = "test-fence-clearall-${Date.now().toLong()}"
        val stale = IndexedDbObjectStore.open(dbName, scope)
        val current = IndexedDbObjectStore.open(dbName, scope)
        current.storeObject(row(9L, "kept"))
        current.flush()

        stale.clearAll()

        val reloaded = IndexedDbObjectStore.open(dbName, scope, claim = false)
        assertEquals(listOf<DbValue>(DbValue.StringValue("kept")), reloaded.getObject(9L)?.fields)
    }

    // End to end — a REAL refused write (not a stubbed FenceReporter) reaches `onFencedOut`,
    // exactly once, which is the hook JsRuntime wires to `RuntimeLease.fencedOut`.
    @Test
    fun aRealRefusedWriteReachesOnFencedOut_once() = runTest {
        val dbName = "test-fence-hook-${Date.now().toLong()}"
        var fencedOut = 0
        val stale = IndexedDbObjectStore.open(dbName, scope, onFencedOut = { fencedOut++ })
        IndexedDbObjectStore.open(dbName, scope) // the new holder claims

        stale.storeObject(row(5L, "late"))
        stale.flush()
        assertEquals(1, fencedOut, "the refused write reports itself")

        stale.storeObject(row(6L, "later"))
        stale.flush()
        assertEquals(1, fencedOut, "once, not per write")
    }

    @Test
    fun fileBytesAreFencedToo() = runTest {
        val dbName = "test-fence-files-${Date.now().toLong()}"
        val stale = IndexedDbFileDatabase.open(dbName, scope)
        val current = IndexedDbFileDatabase.open(dbName, scope)

        current.writeRawByteArray("/a.bin", byteArrayOf(2))
        current.flush()
        stale.writeRawByteArray("/a.bin", byteArrayOf(1))
        stale.flush()

        val reloaded = IndexedDbFileDatabase.open(dbName, scope, claim = false)
        assertContentEquals(byteArrayOf(2), reloaded.readBytes("/a.bin")?.get())
    }

    @Test
    fun aClosedStoreRefusesLaterWrites() = runTest {
        val dbName = "test-fence-closed-${Date.now().toLong()}"
        val store = IndexedDbObjectStore.open(dbName, scope)
        store.storeObject(row(1L, "before"))
        store.close()
        store.storeObject(row(2L, "after"))

        val reloaded = IndexedDbObjectStore.open(dbName, scope, claim = false)
        assertEquals(listOf<DbValue>(DbValue.StringValue("before")), reloaded.getObject(1L)?.fields)
        assertNull(reloaded.getObject(2L), "a write after close() never reaches the disk")
    }

    @Test
    fun theOwnClearKeepsTheFence_soLaterWritesStillLand() = runTest {
        val dbName = "test-fence-clear-${Date.now().toLong()}"
        val store = IndexedDbSecureStorageDelegate.open(dbName, scope)
        store.storeString("k", "v1")
        store.clearAll()
        store.storeString("k", "v2")
        store.flush()

        val reloaded = IndexedDbSecureStorageDelegate.open(dbName, scope, claim = false)
        assertEquals("v2", reloaded.getString("k"))
        assertNull(reloaded.getString(FENCE_KEY), "the fence key never surfaces as a record")
    }
}
