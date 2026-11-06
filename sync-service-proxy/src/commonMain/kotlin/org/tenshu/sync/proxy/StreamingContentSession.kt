package org.tenshu.sync.proxy

/**
 * The bounded-memory + seek core of D2b streaming
 * (`docs/proposals/drive-streaming-preview.md` §3.5, S2). A per-open session over one file's
 * [FileChunkDecryptor]: it answers arbitrary **byte ranges** ([readRange]) by decrypting only
 * the chunks a range touches and assembling across chunk boundaries, holding at most
 * [maxCachedChunks] decrypted chunks in memory at once.
 *
 * This is the pull `ReadContentRange` engine (transport-independent — no op/IPC types here): the
 * future `ReadContentRange(handle, offset, length)` op (S2b) resolves its handle to a session and
 * calls [readRange]; the Android proxy-fd / iOS resource-loader / web Service-Worker bindings
 * (S3/S6/S7) all reduce to the same `readRange` call. Random-access reads are first-class — a
 * player's first read of a moov-at-tail MP4 seeks to EOF then back to 0 (§3.4) — because chunks
 * decrypt independently and the LRU is recency-ordered, not forward-only.
 *
 * **Offset arithmetic** uses the fixed [FileContentChunkSizeBytes] stride (§3.4: chunks are fixed
 * 2 MiB except the last, so `chunkIndex = offset / stride`, O(1), no offset table). Per-chunk
 * authenticity (cipherHash + AAD position-binding) is enforced on every decrypt by
 * [FileChunkDecryptor]; the whole-file hash gate is deliberately NOT applied here (it is the
 * reassemble path's contract — §4) so a seek can read chunk K without 0…K-1.
 */
internal class StreamingContentSession(
    private val decryptor: FileChunkDecryptor,
    /** The file's total plaintext size (from the manifest); reads are clamped to it. */
    val totalSize: Long,
    private val chunkSizeBytes: Int = FileContentChunkSizeBytes,
    /** Max decrypted chunks held at once — the bounded-memory knob (SQ1). */
    private val maxCachedChunks: Int = 4,
) {
    /** Recency-ordered decrypted-chunk cache (insertion order = LRU; reinsert-on-hit refreshes). */
    private val cache = LinkedHashMap<Int, ByteArray>()

    /** Number of decrypted chunks currently retained — bounded by [maxCachedChunks] (test seam). */
    val cachedChunkCount: Int get() = cache.size

    /** Decrypt chunk [index] via the LRU, or `null` on a per-chunk gate fail (tamper / missing). */
    private suspend fun chunk(index: Int): ByteArray? {
        cache.remove(index)?.let { hit ->
            cache[index] = hit // move to most-recent
            return hit
        }
        val plain = decryptor.decryptChunk(index) ?: return null
        cache[index] = plain
        while (cache.size > maxCachedChunks) {
            cache.remove(cache.keys.iterator().next()) // evict least-recent (front)
        }
        return plain
    }

    /**
     * Read up to [length] plaintext bytes starting at [offset], assembling across chunk
     * boundaries. The result is clamped to [totalSize]: a read at/after EOF returns an empty
     * array, and a read overlapping EOF returns only the bytes that exist. Returns `null` if any
     * chunk covering the range fails its per-chunk gate (tamper / missing blob) — the caller
     * surfaces that as a read error, never partial/forged bytes.
     */
    suspend fun readRange(offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length < 0) return null
        if (length == 0 || offset >= totalSize) return ByteArray(0)

        val end = minOf(offset + length, totalSize)
        val out = ByteArray((end - offset).toInt())
        var written = 0
        var pos = offset
        while (pos < end) {
            val index = (pos / chunkSizeBytes).toInt()
            val chunkStart = index.toLong() * chunkSizeBytes
            val plain = chunk(index) ?: return null

            val intra = (pos - chunkStart).toInt()
            if (intra >= plain.size) break // manifest/totalSize disagreement — stop cleanly
            val n = minOf(plain.size - intra, (end - pos).toInt())
            plain.copyInto(out, written, intra, intra + n)
            written += n
            pos += n
        }
        // Exact fit in the common case; trim if a short final chunk ended the range early.
        return if (written == out.size) out else out.copyOf(written)
    }
}

/**
 * Per-session map of open [StreamingContentSession]s keyed by an opaque string handle — the
 * shared state the three pull ops (`OpenContentStream` → handle, `ReadContentRange(handle, …)`,
 * `CloseContentStream(handle)`, S2b) operate on across IPC calls. Mirrors the lifecycle shape of
 * `StreamRegistry` (one instance per session, [clear]ed on teardown) without the push-channel
 * machinery the v1.1 design dropped (§3.5).
 *
 * Single-threaded by the proxy's per-session dispatch (same assumption as `StreamRegistry`); the
 * handle counter is a plain monotone `Long` (no `Math.random`/clock — unavailable + would break
 * resume).
 */
internal class StreamingContentRegistry {
    private val sessions = LinkedHashMap<String, StreamingContentSession>()
    private var nextId = 0L

    /** Register [session] and return its fresh handle. */
    fun open(session: StreamingContentSession): String {
        val handle = "content-stream-${nextId++}"
        sessions[handle] = session
        return handle
    }

    fun get(handle: String): StreamingContentSession? = sessions[handle]

    /** Close [handle]; returns true iff it was open (idempotent otherwise). */
    fun close(handle: String): Boolean = sessions.remove(handle) != null

    /** Drop all sessions — called on session teardown. */
    fun clear() = sessions.clear()

    /** Open-session count (test seam / observability). */
    val activeCount: Int get() = sessions.size
}
