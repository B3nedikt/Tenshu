package org.tenshu.sync.proxy

import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.sha256
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.FileContentManifestPayload
import org.tenshu.sync.common.ContentBlobStore

/**
 * Per-file content-chunk read source: fetch a single chunk by opaque ref, gate
 * it on its `cipherHash`, and AEAD-decrypt it under the content key (CEK).
 *
 * Factored out of [FileContentManifestApplier.apply]'s reassembly loop (D2b S1,
 * `docs/proposals/drive-streaming-preview.md` §6) so the two read paths share
 * **byte-identical** chunk logic:
 *  - the **reassemble** path (whole-file, durable artifacts — open-externally,
 *    download-for-offline) loops [decryptChunk] `0 until chunkCount` and applies
 *    the whole-file hash/size gate on top, and
 *  - the future **streaming** read path (the pull `ReadContentRange` source,
 *    §3.5) calls [decryptChunk] for just the chunks a seek/read touches.
 *
 * One instance binds to one [manifest]. It memoises the volume key that last
 * worked ([workingKey]) so the GR-7 trial-decrypt across retired keys
 * (`shared-realm-membership-grant.md` §13.4) settles on the file's single `Kr`
 * at chunk 0 and reuses it for the rest — identical to the inlined loop it
 * replaces. A single-element [candidateChunkKeys] (the personal applier or an
 * unrotated shared realm) makes the trial a no-op, byte-identical to the
 * pre-GR-7 single-`decryptWithAad` path.
 *
 * **No whole-file gate lives here.** The `wholeFileHash` chain + `totalSize`
 * check is the *reassemble* path's contract (it folds [IncrementalSha256]
 * across the full ordered sequence) and is deliberately relaxed for the
 * render-only streaming consumer (§4) — a seek reads chunk K without 0…K-1, so
 * an up-front whole-file gate cannot hold. What this class keeps on **every**
 * read is the per-chunk authenticity gate: the `cipherHash` match (catches a
 * relay serving a wrong/old blob under a ref *before* an AEAD decrypt is spent)
 * and the AAD `(fileId, chunkIndex, chunkCount)` binding inside
 * [SyncCryptoManager.decryptWithAad] (a reordered / cross-file-spliced /
 * truncated / bit-flipped chunk fails to authenticate).
 */
internal class FileChunkDecryptor(
    private val cryptoManager: SyncCryptoManager,
    private val contentBlobStore: ContentBlobStore,
    manifest: FileContentManifestPayload,
    /**
     * Candidate keys to trial-decrypt the chunks under. Normally a single element: the per-content
     * key (CEK) the caller unwrapped from the manifest (content-addressing §3.4). Element 0 is the
     * preferred key. ⚠ Empty means "no key opens this content": every [decryptChunk] is a clean
     * `null`, never a throw.
     */
    private val candidateChunkKeys: List<ByteArray>,
) {
    private val fileId = manifest.fileId.get()
    private val chunkRefs = manifest.chunks.toList()

    /** Number of chunks the manifest declares. */
    val chunkCount: Int = manifest.chunkCount.get().toInt()

    /**
     * True iff the manifest's chunk-ref list length matches its declared
     * [chunkCount]. A mismatch is a malformed manifest; callers should refuse to
     * read (the reassemble path returns a clean gate fail).
     */
    val isWellFormed: Boolean get() = chunkRefs.size == chunkCount

    /** The volume key that decrypted the previous chunk — tried first next time. */
    private var workingKey: ByteArray? = candidateChunkKeys.firstOrNull()

    /**
     * Fetch + cipherHash-gate + AEAD-decrypt chunk [index] (0-based, `< chunkCount`).
     * Returns the plaintext bytes, or `null` on a clean gate fail — a missing/dropped
     * blob, a `cipherHash` mismatch (wrong/old blob under the ref), or no candidate key
     * authenticating it (genuine tamper, or a key we don't hold). A `null` is by design
     * indistinguishable from a transient absence; the caller decides whether to retry
     * (chunks are immutable + content-addressed ⇒ idempotent).
     */
    suspend fun decryptChunk(index: Int): ByteArray? {
        if (candidateChunkKeys.isEmpty()) return null
        val chunkRef = chunkRefs[index]
        val fetched = contentBlobStore.getChunk(chunkRef.ref.get())
        val cipher = (fetched as? TResult.Success)?.value?.get() ?: return null

        // cipherHash gate first: catch a relay serving a wrong/old blob under this
        // ref before spending an AEAD decrypt.
        if (sha256(cipher).toHexLower() != chunkRef.cipherHash.get()) return null

        // Same AAD the emitter bound: (fileId, chunkIndex, chunkCount). Both the
        // bound-context check and the underlying AEAD tag failure surface as a clean
        // gate fail — a tampered chunk must never decrypt.
        val aad = fileChunkAad(fileId, index, chunkCount)
        val decrypted = decryptChunkTrying(cipher, aad) ?: return null
        workingKey = decrypted.keyUsed
        return decrypted.plain
    }

    /** A chunk's plaintext plus the volume key that decrypted it (GR-7 trial-decrypt). */
    private class DecryptedChunk(val plain: ByteArray, val keyUsed: ByteArray)

    /**
     * Decrypt [cipher]+[aad] trying [workingKey] (the key that worked for the
     * previous chunk) first, then every candidate. Both an `AadMismatchException`
     * (reordered/spliced/truncated chunk) and the underlying AEAD tag failure (wrong
     * key, or a relay that flipped bytes) surface as a `try` failure — so a wrong key
     * is indistinguishable from tamper *for that key* and we move to the next candidate.
     * Returns `null` only when no candidate decrypts it. With a single candidate this is
     * byte-identical to a single `decryptWithAad` call.
     */
    private fun decryptChunkTrying(cipher: ByteArray, aad: ByteArray): DecryptedChunk? {
        val ordered = if (candidateChunkKeys.size <= 1) {
            candidateChunkKeys
        } else {
            listOfNotNull(workingKey) + candidateChunkKeys
        }
        for (key in ordered) {
            val plain = try {
                cryptoManager.decryptWithAad(cipher, key, aad)
            } catch (_: Exception) {
                null
            }
            if (plain != null) return DecryptedChunk(plain, key)
        }
        return null
    }
}
