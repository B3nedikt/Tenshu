package org.tenshu.core.common.util

/**
 * The whole-file plaintext integrity anchor shared by the file-content emit,
 * read, and upload paths. Lifted to `:core-common` (from the former
 * `:sync-service-proxy` `FileContentChunking` internals) so there is a **single
 * source of truth**: `UploadFileImplementation` stamps `File.contentHash` with
 * exactly the value the emitter puts in the manifest and the reader re-folds to
 * gate integrity — a duplicated construction would silently drift and no file
 * would ever verify (content-addressing Phase 1, `file-content-addressing.md`
 * §8.4 "one shared hash pass").
 *
 * The construction is an order-sensitive hash-chain, `O(1)` memory on every
 * plane: `H = sha256(H || sha256(chunk))` from `H_0 = 32` zero bytes, folded one
 * chunk at a time. A true incremental SHA-256 over 64-byte blocks would need a
 * platform primitive not exposed in commonMain; this chain is collision-
 * resistant and catches chunk reorder / truncation at the whole-file level.
 */
class IncrementalSha256 {
    private var running: ByteArray = ByteArray(32) // H_0 = 32 zero bytes

    fun update(chunk: ByteArray) {
        val chunkDigest = sha256(chunk)
        running = sha256(running + chunkDigest)
    }

    fun digest(): ByteArray = running
}

/**
 * The fixed plaintext chunk size (2 MiB) the emitter cuts content at, and the
 * stride the streaming read path maps `offset → chunkIndex` with. The whole-file
 * hash-chain ([IncrementalSha256]) is order- *and boundary*-sensitive, so any
 * value computing the same hash MUST fold at this exact boundary — which is why
 * [wholeFileHashOf] takes it as the single shared constant.
 */
const val FileContentChunkSizeBytes: Int = 2 * 1024 * 1024

/** Lower-case hex of a byte array (hash / ciphertext-ref rendering). */
fun ByteArray.toHexLower(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}

/**
 * Compute the whole-file hash-chain of [data] in memory, folding at
 * [FileContentChunkSizeBytes] boundaries so the result is **byte-identical** to
 * the emitter's streaming fold over the same bytes (and to the reader's re-fold
 * over the decrypted chunks). An empty file folds a single empty chunk, matching
 * the emitter's `chunkCount >= 1` rule for a 0-byte file.
 *
 * `UploadFileImplementation` uses this on the just-uploaded in-memory bytes to
 * stamp `File.contentHash` once, before the entity is saved — the manifest then
 * reuses that value rather than re-hashing (one hash pass).
 */
fun wholeFileHashOf(data: ByteArray, chunkSize: Int = FileContentChunkSizeBytes): String {
    val hasher = IncrementalSha256()
    if (data.isEmpty()) {
        hasher.update(ByteArray(0))
    } else {
        var offset = 0
        while (offset < data.size) {
            val end = minOf(data.size, offset + chunkSize)
            hasher.update(data.copyOfRange(offset, end))
            offset = end
        }
    }
    return hasher.digest().toHexLower()
}
