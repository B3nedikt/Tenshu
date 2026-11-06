package app.tenshu.drive.data

import org.tenshu.core.common.util.FileContentChunkSizeBytes
import org.tenshu.core.common.util.IncrementalSha256
import org.tenshu.core.common.util.toHexLower

/**
 * Fold a platform stream into the **same** whole-file hash `UploadFileImplementation` stamps on
 * `File.contentHash`, so an ingest-time skip compares like with like.
 *
 * ⚠⚠ This exists because the alternative is three platform implementations each folding their own
 * way, and the failure mode of getting it subtly wrong — a different chunk boundary, a missed
 * final partial chunk — is a hash that never matches anything. Nothing crashes; the duplicate
 * skip simply never fires, and the setting silently does nothing. One construction, three callers
 * that only have to supply bytes.
 *
 * [readChunk] fills [into] from the current position and returns how many bytes it wrote, or `-1`
 * at end of stream — the `InputStream.read` shape, expressed without a JVM type so iOS can use it.
 *
 * ⚠⚠ A short read is **not** end of stream, and this is where the first draft was wrong. A real
 * stream — notably one over an Android content provider — routinely returns fewer bytes than asked
 * for while more remain, so folding whatever one read returned would cut the chain at the wrong
 * boundary and produce a hash that matches nothing. Bytes are therefore accumulated into a full
 * chunk before folding, and only the final partial chunk is short. `PlatformContentHashTest`
 * dribbles a file out 64 bytes at a time precisely to hold this.
 *
 * ⚠ An empty file folds a **single empty chunk**, matching the emitter's `chunkCount >= 1` rule
 * for a 0-byte file. Folding nothing at all would give the `H_0` of a file with no content, which
 * is a different value from the one that is stored.
 */
fun foldPlatformContentHash(readChunk: (into: ByteArray) -> Int): String {
    val hash = IncrementalSha256()
    val chunk = ByteArray(FileContentChunkSizeBytes)
    val scratch = ByteArray(READ_BUFFER_BYTES)
    var filled = 0
    var folded = false

    while (true) {
        val read = readChunk(scratch)
        if (read <= 0) break
        var consumed = 0
        while (consumed < read) {
            val n = minOf(chunk.size - filled, read - consumed)
            scratch.copyInto(chunk, destinationOffset = filled, startIndex = consumed, endIndex = consumed + n)
            filled += n
            consumed += n
            if (filled == chunk.size) {
                hash.update(chunk.copyOf())
                folded = true
                filled = 0
            }
        }
    }

    if (filled > 0) {
        hash.update(chunk.copyOf(filled))
        folded = true
    }
    if (!folded) hash.update(ByteArray(0))
    return hash.digest().toHexLower()
}

/** ⚠ The **read** stride, deliberately not the chunk size: it bounds the scratch buffer, while the
 *  fold boundary is `FileContentChunkSizeBytes` and is set by the accumulator above. */
private const val READ_BUFFER_BYTES = 64 * 1024
