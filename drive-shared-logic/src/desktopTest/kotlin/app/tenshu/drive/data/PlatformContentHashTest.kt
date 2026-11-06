package app.tenshu.drive.data

import org.tenshu.core.common.util.FileContentChunkSizeBytes
import org.tenshu.core.common.util.wholeFileHashOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The one property [foldPlatformContentHash] exists for: its answer must equal what
 * `UploadFileImplementation` stamps on `File.ContentHash` for the same bytes
 * (`photos-app.md` PH-6).
 *
 * ⚠⚠ Written against `wholeFileHashOf` — the *production* construction — rather than against
 * expected hex strings. A pinned literal would go on passing after the emitter changed, and the
 * symptom of a mismatch is not a failure anywhere: the ingest-time skip simply never fires, and
 * the user's setting silently does nothing.
 */
class PlatformContentHashTest {

    /** Feed [bytes] through the `InputStream.read`-shaped lambda the delegates supply. */
    private fun fold(bytes: ByteArray): String {
        var offset = 0
        return foldPlatformContentHash { buffer ->
            if (offset >= bytes.size) {
                -1
            } else {
                val n = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, 0, offset, offset + n)
                offset += n
                n
            }
        }
    }

    @Test
    fun aSmallFileFoldsToTheStoredHash() {
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        assertEquals(wholeFileHashOf(bytes), fold(bytes))
    }

    @Test
    fun anEmptyFileFoldsOneEmptyChunk() {
        // ⚠ Not the H_0 of nothing. The emitter's `chunkCount >= 1` rule means a 0-byte file
        // still folds one chunk, and folding nothing gives a different — and wrong — value.
        assertEquals(wholeFileHashOf(ByteArray(0)), fold(ByteArray(0)))
    }

    @Test
    fun aFileSpanningChunkBoundariesFoldsToTheStoredHash() {
        // ⚠⚠ The case a naive implementation gets wrong: the chain is boundary-sensitive, so
        // reading in any stride other than the shared chunk size gives a different hash for the
        // same bytes.
        val bytes = ByteArray(FileContentChunkSizeBytes * 2 + 7) { (it % 97).toByte() }
        assertEquals(wholeFileHashOf(bytes), fold(bytes))
    }

    @Test
    fun exactlyOneChunkFoldsToTheStoredHash() {
        val bytes = ByteArray(FileContentChunkSizeBytes) { (it % 13).toByte() }
        assertEquals(wholeFileHashOf(bytes), fold(bytes))
    }

    @Test
    fun differentBytesDoNotCollide() {
        val a = ByteArray(64) { 1 }
        val b = ByteArray(64) { 2 }
        assertNotEquals(fold(a), fold(b))
    }

    @Test
    fun aShortReadIsNotEndOfStream() {
        // ⚠ A real `InputStream.read` may return fewer bytes than asked for without being done —
        // a stream over a content provider does this routinely. Treating a short read as EOF
        // would hash a prefix of the file and match nothing.
        val bytes = ByteArray(5000) { (it % 31).toByte() }
        var offset = 0
        val hash = foldPlatformContentHash { buffer ->
            if (offset >= bytes.size) {
                -1
            } else {
                val n = minOf(64, bytes.size - offset) // dribble it out 64 bytes at a time
                bytes.copyInto(buffer, 0, offset, offset + n)
                offset += n
                n
            }
        }
        assertEquals(wholeFileHashOf(bytes), hash)
    }
}
