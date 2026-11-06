@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The read that used to crash Drive.
 *
 * A Keynote document is a **package** — a directory — so `NSData.dataWithContentsOfFile` returns
 * nil for it exactly as it does for a missing file. Read client-side, that nil became a throw
 * while the upload op's *arguments* were being evaluated, which is before any `TResult` exists:
 * nothing converted it, it escaped an unhandled `viewModelScope.launch`, and Kotlin/Native
 * `abort()`ed the app. `drive-upload-source-read.md` moves the read inside the operation; these
 * pin the reader's own contract.
 *
 * ⚠ Uploading a package still fails — that is the separate flattening track. What must hold here
 * is that it fails *honestly*: a throw, never an empty ByteArray, and a message that says which
 * case it was.
 */
class IosFileUploadSourceTest {

    private val fm = NSFileManager.defaultManager

    private fun tempDir(): String =
        NSTemporaryDirectory() + "iosuploadsource-" + kotlin.random.Random.nextInt(1_000_000)

    @Test
    fun readsAFileFromABarePath() = runTest {
        val dir = tempDir()
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val path = "$dir/report.txt"
        (("hello" as NSString)).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)

        assertEquals("hello", IosFileUploadSource.readBytes(path).decodeToString())
    }

    /**
     * ⚠⚠ The regression this exists for. A `.key` is a directory; the reader must say so rather
     * than claim the path is missing — "File not found" for a path that plainly exists is what
     * sent the original investigation down a disassembly rabbit hole.
     */
    @Test
    fun aDocumentPackageFailsAsADirectory_notAsAMissingFile() = runTest {
        val pkg = tempDir() + "/Deck.key"
        fm.createDirectoryAtPath(pkg, withIntermediateDirectories = true, attributes = null, error = null)
        assertTrue(fm.fileExistsAtPath(pkg), "precondition: the package must exist on disk")

        val message = assertFailsWith<IllegalStateException> {
            IosFileUploadSource.readBytes(pkg)
        }.message.orEmpty()

        assertTrue(pkg in message, "the message must name the source: $message")
        assertTrue(
            "directory" in message,
            "a directory must not be reported as a missing file — that reading cost a session: $message",
        )
    }

    /** ⚠ Never an empty ByteArray for an unreadable source: a zero-byte upload reporting success. */
    @Test
    fun aMissingFileThrowsRatherThanReturningEmpty() = runTest {
        assertFailsWith<IllegalStateException> {
            IosFileUploadSource.readBytes(tempDir() + "/nope.txt")
        }
    }

    @Test
    fun fileUrlsArePercentDecodedToPaths() {
        assertEquals("/tmp/a b.txt", IosFileUploadSource.pathOf("file:///tmp/a%20b.txt"))
        assertEquals("/tmp/plain.txt", IosFileUploadSource.pathOf("/tmp/plain.txt"))
    }

    /**
     * ⚠ D6 (`ios-per-app-frameworks.md`): the base reader has no PhotoKit, so a `phasset://` source
     * must fail as one — not be read as a relative path and reported "missing or a directory".
     */
    @Test
    fun aPhotoKitSourceIsRefusedByName_notReadAsAPath() = runTest {
        val message = assertFailsWith<IllegalStateException> {
            IosFileUploadSource.readBytes("phasset://ABC-123/L0/001")
        }.message.orEmpty()
        assertTrue("PhotoKit" in message, "the refusal must say what kind of source it was: $message")
    }
}
