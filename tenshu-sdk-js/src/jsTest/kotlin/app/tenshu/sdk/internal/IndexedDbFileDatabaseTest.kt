package app.tenshu.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

// `deleteFile` on the browser store. It used to inherit `FileDatabase`'s no-op default, so a deleted
// path stayed in both the cache and IndexedDB, and `UploadFile`'s transient unsent-content marker
// outlived every upload. Reopening with the same DB name is the "page reload" that proves the disk
// half, as in `IndexedDbObjectStoreTest`.
class IndexedDbFileDatabaseTest {

    @Test
    fun aDeletedFile_isGoneFromTheCacheAndFromDisk() = runTest {
        val dbName = "test-files-${Date.now().toLong()}"
        val scope = CoroutineScope(Dispatchers.Default)
        val first = IndexedDbFileDatabase.open(dbName, scope)

        first.writeRawByteArray("/keep.bin", byteArrayOf(1, 2, 3))
        first.writeRawByteArray("/gone.bin", byteArrayOf(4, 5, 6))
        first.flush()
        first.deleteFile("/gone.bin")

        assertNull(first.readBytes("/gone.bin"), "the cache forgets it at once")
        first.flush()

        val second = IndexedDbFileDatabase.open(dbName, scope)
        assertNull(second.readBytes("/gone.bin"), "and so does IndexedDB")
        assertContentEquals(byteArrayOf(1, 2, 3), second.readBytes("/keep.bin")?.get())
    }

    @Test
    fun aDeleteRightAfterAWrite_winsOnDisk() = runTest {
        // The marker's own shape: written, then deleted before the write was flushed.
        val dbName = "test-files-order-${Date.now().toLong()}"
        val scope = CoroutineScope(Dispatchers.Default)
        val first = IndexedDbFileDatabase.open(dbName, scope)

        first.writeRawByteArray("/.unsent-content/abc", byteArrayOf(1))
        first.deleteFile("/.unsent-content/abc")
        first.flush()

        assertNull(IndexedDbFileDatabase.open(dbName, scope).readBytes("/.unsent-content/abc"))
    }
}
