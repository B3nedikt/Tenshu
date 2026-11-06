package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.TResult

/**
 * The **Mode-A link-share** client port (`docs/file-sharing.md`
 * §3.1 / §3.3). The sharer-side counterpart of the relay's `configureShareRouting`:
 * it `POST`s a [ShareDescriptor], streams the AEAD-encrypted ciphertext chunks
 * (`PUT /share/{shareId}/chunk/{i}`), and `DELETE`s a share on revoke. All three
 * verbs are JWT-scoped to the sharer's `ownerId` (the recipient reads are anonymous
 * and live entirely in the web viewer / SDK, not here).
 *
 * Sibling of [ContentBlobStore] — same zero-trust posture (House Rule #1): the
 * caller AEAD-encrypts every chunk under the per-share key `K_share` *before*
 * calling [putChunk], so this port only ever sees opaque ciphertext. `K_share`
 * is never on the wire (URL fragment only — A11). Unlike the content plane,
 * share chunks are addressed by `(shareId, chunkIndex)` (not a content hash):
 * the descriptor carries the per-chunk integrity anchors the recipient verifies,
 * and per-share independent chunks make revocation strictly per-share
 * (`docs/file-sharing.md` "Why re-encrypt rather than wrap").
 *
 * Nullable at every wiring site: test harnesses / pre-sync proxies leave it
 * unset, in which case `ShareLink` saves only the local `Share` entity (the
 * *My shares* projection + Eager multi-device convergence still work) and the
 * minted URL resolves to a relay 410 until a real client is wired — exactly
 * mirroring how [ContentBlobStore]-less wirings degrade the content plane.
 */
interface ShareRelayClient {

    /**
     * `POST /share` — register the ShareRecord the relay serves to recipients.
     * Idempotent on same-owner retry (the relay enforces the global `shareId`
     * namespace). Returns [TResult.Failure] on a non-2xx response / transport
     * error so the op can abort before saving a local `Share` that points at a
     * record the relay never accepted.
     */
    suspend fun createShare(descriptor: ShareDescriptor): TResult<Data?>

    /**
     * `PUT /share/{shareId}/chunk/{chunkIndex}` — stream one already-encrypted
     * chunk. Implementations MUST stream the body (Ktor
     * `OutgoingContent.WriteChannelContent`) rather than re-buffer, mirroring
     * [ContentBlobStore.putChunk] — the share path inherits the same `O(CHUNK)`
     * bound.
     */
    suspend fun putChunk(shareId: String, chunkIndex: Int, ciphertext: ByteArray): TResult<Data?>

    /**
     * `DELETE /share/{shareId}` — soft-revoke at the relay (recipient `GET`s
     * start returning 410). JWT-scoped to the owner; a `DELETE` for a share
     * owned by someone else is a 404 the relay maps to a [TResult.Failure]
     * here. Revocation's local source of truth is the Eager `Share.revoked`
     * flip; this call propagates the revocation to the recipient surface.
     */
    suspend fun deleteShare(shareId: String): TResult<Data?>

    /**
     * The wire descriptor `POST /share` carries. Mirrors the relay's
     * `CreateShareRequest`. `chunkHashesBase64Url` are the per-chunk
     * ciphertext-hash anchors (base64url, no padding) the recipient verifies
     * each fetched chunk against; `expiresAtMillis` / `maxOpens` are `null`
     * for "no cap" (the `0L` UI sentinel is normalised to `null` by the caller).
     *
     * `sealedMetaBase64Url` is [sealShareMeta]'s output (base64url, no padding):
     * the file's name, MIME type and exact size, sealed under `K_share`. There is
     * deliberately no clear name / MIME / size field — the relay keeps only what
     * it enforces (`relay-metadata-minimisation.md` F3).
     */
    data class ShareDescriptor(
        val shareId: String,
        val sealedMetaBase64Url: String,
        val chunkCount: Int,
        val chunkHashesBase64Url: List<String>,
        val expiresAtMillis: Long?,
        val maxOpens: Int?,
    )

    companion object {
        const val ID = "tenshu/file/shareRelayClient"
    }
}

/**
 * The per-chunk AEAD associated data for a Mode-A share chunk
 * (`docs/file-sharing.md` step 4). Binds
 * `(shareId, chunkIndex, chunkCount)` into every chunk's AEAD frame so a
 * ciphertext cannot be silently reordered, truncated, replayed, or spliced
 * across shares — any tamper of the bound context fails
 * [org.tenshu.core.security.SyncCryptoManager.decryptWithAad]. Deliberately
 * **not** the content-plane `fileChunkAad`: that binds the file's *path* in
 * the sharer's realm (which would leak through to a recipient and couple the
 * shared chunks to the original file's chunks). The share AAD binds the opaque
 * `shareId` instead, so a share is cryptographically independent of the file it
 * was minted from — the precondition for per-share revocation (§3.3).
 *
 * The sharer (`ShareLinkImplementation`) and the recipient (`ShareViewer.fetch`
 * in `:tenshu-sdk-js`) MUST reconstruct this byte-identically — sharing the
 * helper here is what guarantees they cannot drift.
 */
fun shareChunkAad(shareId: String, chunkIndex: Int, chunkCount: Int): ByteArray =
    "tenshu/file-share/v1|$shareId|$chunkIndex|$chunkCount".encodeToByteArray()
