package app.tenshu.sdk

import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.tenshu.core.file.ContentModifiedAt
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCount
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size

/**
 * `FileSystem.inventory()` — Control → Data → Files (`web-app-suite.md` §2, Wave 4).
 *
 * ⚠⚠ The contract is three answers, and the point of this suite is that two of them never
 * collapse: an empty drive is `ok: true` with zero counts, a failed read is `ok: false` with a
 * kind. The listing reads beside it fold a failure into an empty array; an inventory that did the
 * same would tell the user they own nothing.
 *
 * ⚠ The keys asserted here are the ones `web/sdk/src/files.ts` (`FileInventory`) and
 * `web/apex/src/control/data/files/` read. Rename one side and the other changes in the same commit.
 */
class FileInventoryContractTest : SdkRuntimeTest() {

    @Test
    fun anEmptyListingIsASuccessWithZeroCounts() {
        val answer = inventoryResultJs(emptyList())
        assertEquals(true, answer.ok as Boolean, "an empty drive is an answer, not a failure")
        val inventory = answer.inventory as FileInventoryJs
        assertEquals(0, inventory.fileCount)
        assertEquals(0, inventory.folderCount)
        assertEquals(0.0, inventory.totalBytes)
        assertEquals(0.0, inventory.newestModifiedMillis)
        assertEquals(0, inventory.byExtension.size)
    }

    @Test
    fun aFailedReadIsAFailureWithAKindNotAnEmptyInventory() {
        val answer = inventoryFailureJs("tree read broke")
        assertEquals(false, answer.ok as Boolean)
        assertEquals("read", answer.error.kind as String, "the kind TypeScript switches on")
        assertEquals("tree read broke", answer.error.message as String)
        assertEquals(undefined, answer.inventory, "a failure must carry no inventory to misread as zeros")
    }

    @Test
    fun rowsFoldIntoTotalsWithFoldersCountedApartAndBucketsLargestFirst() {
        val rows = listOf(
            row("/a/Photo.JPG", 300.0, modified = 10.0),
            row("/a/other.jpg", 100.0, modified = 30.0),
            row("/notes.md", 50.0, modified = 20.0),
            row("/Makefile", 7.0, modified = 5.0),
            row("/a", 999.0, modified = 99.0, isFolder = true),
        )
        val inventory = fileInventoryOf(rows)

        assertEquals(4, inventory.fileCount)
        assertEquals(1, inventory.folderCount)
        assertEquals(457.0, inventory.totalBytes, "a folder's own size must not be added to the total")
        assertEquals(30.0, inventory.newestModifiedMillis, "newest FILE, never the folder's touch")
        assertEquals(listOf("jpg", "md", ""), inventory.byExtension.map { it.extension })
        assertEquals(listOf(2, 1, 1), inventory.byExtension.map { it.count })
        assertEquals(listOf(400.0, 50.0, 7.0), inventory.byExtension.map { it.bytes })
    }

    @Test
    fun extensionsAreLowerCasedAndADotfileHasNone() {
        assertEquals("pdf", extensionOf("/x/Report.PDF"))
        assertEquals("gz", extensionOf("/x/a.tar.gz"))
        assertEquals("", extensionOf("/x/.env"))
        assertEquals("", extensionOf("/x/README"))
        assertEquals("", extensionOf("/x/trailing."))
        assertEquals("", extensionOf("/dir.d/file"), "a dot in a FOLDER name is not the file's extension")
    }

    // The live read, through the real runtime: an upload moves the totals by exactly its own size.
    // ⚠ Deltas, not absolutes — the suite shares one IndexedDB store with every other test.
    @Test
    fun theLiveReadSucceedsAndCountsAnUpload() = runTest {
        val before = FileSystem.inventory().await()
        assertTrue(before.ok as Boolean, "the live tree read must succeed: ${before.error?.message}")
        val b = before.inventory as FileInventoryJs

        val stamp = Date.now().toLong()
        assertTrue(FileSystem.uploadFile("/", "FileInventoryContractTest-$stamp.inv", ByteArray(1234) { 3 }).await())
        assertTrue(FileSystem.createFolder("/FileInventoryContractTest-dir-$stamp").await())

        val after = FileSystem.inventory().await()
        assertTrue(after.ok as Boolean)
        val a = after.inventory as FileInventoryJs
        assertEquals(b.fileCount + 1, a.fileCount)
        assertEquals(b.folderCount + 1, a.folderCount)
        assertEquals(b.totalBytes + 1234.0, a.totalBytes)
        val bucket = a.byExtension.firstOrNull { it.extension == "inv" }
        assertNotNull(bucket, "the upload's extension must have its own bucket")
        assertTrue(bucket.count >= 1)
        assertTrue(a.newestModifiedMillis > 0.0)
        assertFalse(a.byExtension.any { it.extension.any { c -> c.isUpperCase() } })
    }

    // ⚠ Review finding #2: the inventory projected the listing's select, which carries
    // `Folder.FileCount` — a dynamic resolve per folder — and `LastModifiedAt`, neither of which
    // the fold reads. Pins the projection to exactly what `inventoryRowOf` uses.
    @Test
    fun theInventoryProjectsExactlyTheFieldsItFolds() {
        assertEquals(listOf(File.Field.PATH), INVENTORY_FIELDS)
        assertEquals(setOf(File.Size, File.ContentModifiedAt, File.CreatedAt), INVENTORY_RELATIONS.toSet())
        assertEquals(INVENTORY_RELATIONS.size, INVENTORY_RELATIONS.toSet().size, "no relation projected twice")
        assertFalse(Folder.FileCount in INVENTORY_RELATIONS, "a per-folder dynamic resolve nothing reads")
        assertFalse(File.LastModifiedAt in INVENTORY_RELATIONS)
    }

    private fun row(path: String, size: Double, modified: Double, isFolder: Boolean = false) =
        InventoryRow(path = path, size = size, modified = modified, isFolder = isFolder)
}
