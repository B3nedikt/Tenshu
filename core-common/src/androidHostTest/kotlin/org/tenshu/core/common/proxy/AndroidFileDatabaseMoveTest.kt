package org.tenshu.core.common.proxy

import android.content.ContextWrapper
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `moveFile` with **no bytes at the source** — the ordinary case for an `OnDemand` property, not an
 * error.
 *
 * ⚠⚠ Why this exists: this implementation had no absent-source guard, so `renameTo` failed and the
 * copy fallback opened a file that was not there. The throw landed inside `MoveFileImplementation`
 * *after* it had detached the row from its old parent and *before* it saved the destination, so
 * moving a synced-but-never-downloaded file left it in neither folder's listing — invisible, and
 * unreachable by hand. Every other `FileDatabase` returns early here; Android was the outlier.
 */
class AndroidFileDatabaseMoveTest {

    private val root: File = File.createTempFile("android-file-db", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private val db = AndroidFileDatabaseImpl(
        object : ContextWrapper(null) {
            override fun getFilesDir(): File = root
        }
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(path: String, bytes: ByteArray) {
        File(root, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    @Test
    fun movingAFileWithNoLocalBytes_isANoOp_notAThrow() {
        // No source written: an OnDemand file whose bytes were never fetched.
        db.moveFile("photos/holiday.jpg", "archive/holiday.jpg")

        assertFalse(File(root, "archive/holiday.jpg").exists(), "nothing to move, nothing created")
    }

    /**
     * ⚠⚠ The retry case, and the reason the guard must come **before** `to.delete()`: a first
     * attempt can move the bytes and then fail later, so a re-run finds the source gone and the only
     * copy at the target. Deleting it there would destroy the file's last bytes.
     */
    @Test
    fun movingWithTheSourceGone_leavesBytesAlreadyAtTheTarget_untouched() {
        write("archive/holiday.jpg", byteArrayOf(7, 8, 9))

        db.moveFile("photos/holiday.jpg", "archive/holiday.jpg")

        assertContentEquals(
            byteArrayOf(7, 8, 9),
            File(root, "archive/holiday.jpg").readBytes(),
            "the target's bytes are the only copy — the no-op must not delete them",
        )
    }

    @Test
    fun movingAFileThatIsPresent_stillMovesTheBytes() {
        write("photos/holiday.jpg", byteArrayOf(1, 2, 3))

        db.moveFile("photos/holiday.jpg", "archive/holiday.jpg")

        assertFalse(File(root, "photos/holiday.jpg").exists(), "source is gone")
        assertContentEquals(byteArrayOf(1, 2, 3), File(root, "archive/holiday.jpg").readBytes())
    }

    @Test
    fun movingOntoAnExistingTarget_replacesIt() {
        write("photos/holiday.jpg", byteArrayOf(1, 2, 3))
        write("archive/holiday.jpg", byteArrayOf(9, 9))

        db.moveFile("photos/holiday.jpg", "archive/holiday.jpg")

        assertContentEquals(byteArrayOf(1, 2, 3), File(root, "archive/holiday.jpg").readBytes())
    }

    /** A directory at the source path is not a file to move — the same early return covers it. */
    @Test
    fun aDirectoryAtTheSourcePath_isNotMoved() {
        File(root, "photos/holiday.jpg").mkdirs()

        db.moveFile("photos/holiday.jpg", "archive/holiday.jpg")

        assertTrue(File(root, "photos/holiday.jpg").isDirectory, "left alone")
        assertFalse(File(root, "archive/holiday.jpg").exists(), "and nothing promoted out of it")
    }

    /** The absent-source no-op must not report progress it did not make. */
    @Test
    fun theNoOpDoesNotCreateTheTargetsParentDirectory() {
        db.moveFile("photos/holiday.jpg", "archive/holiday.jpg")

        assertEquals(
            emptyList(),
            root.listFiles()?.map { it.name }?.sorted() ?: emptyList(),
            "an absent source leaves the store exactly as it was",
        )
    }
}
