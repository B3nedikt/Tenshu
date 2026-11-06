package org.tenshu.sync.common

import org.tenshu.core.security.SyncCryptoManager

/**
 * The **sealed metadata** of a Mode-A share (`relay-metadata-minimisation.md` F3, ADR 0018): the
 * file's name, MIME type and exact plaintext size. The relay never sees any of the three — they
 * travel to it only as [sealShareMeta]'s ciphertext, in the descriptor's `sealedMeta` slot, and come
 * back out to the anonymous recipient, who opens them with the same `K_share` that decrypts the
 * chunks (URL fragment, A11).
 *
 * ⚠⚠ The sharer (`ShareLinkImplementation`) and the recipient (`ShareViewer` in `:tenshu-sdk-js`)
 * MUST share this file — the same reason [shareChunkAad] lives here: a codec spelled twice drifts.
 */
data class ShareMeta(
    val fileName: String,
    val mimeType: String,
    val size: Long,
) {
    init {
        require(size >= 0) { "share size must be non-negative" }
    }
}

/**
 * Thrown by [openShareMeta] when the descriptor carries a `sealedMeta` that is absent or, once
 * AEAD-opened, not a well-formed v1 record. Distinct from the AEAD's own failure (wrong `K_share`,
 * tampered bytes) so a viewer can tell "this link's key is wrong" from "this share carries no
 * readable details" — both are a visible failure, never a blank file name.
 */
class ShareMetaMalformedException(message: String) : RuntimeException(message)

/**
 * The AEAD associated data for a share's sealed metadata. Binds the opaque `shareId` and the
 * descriptor's `chunkCount`, so the relay can neither splice one share's metadata into another nor
 * edit the `chunkCount` it serves without the open failing. Domain-separated from [shareChunkAad]
 * by its own label, so a chunk ciphertext can never open as metadata or the reverse, even though
 * both sit under the same `K_share` (XChaCha20-Poly1305 with a fresh 24-byte random nonce per
 * seal, so one key across both is within the AEAD's usage bounds).
 */
fun shareMetaAad(shareId: String, chunkCount: Int): ByteArray =
    "tenshu/file-share-meta/v1|$shareId|$chunkCount".encodeToByteArray()

/** Plaintext record version. Bumped only together with a new [shareMetaAad] label. */
private const val SHARE_META_VERSION: Byte = 1

/**
 * The plaintext is zero-padded to a multiple of this before sealing, so the ciphertext length the
 * relay stores reveals the name's length only to within a bucket (one bucket for any name + MIME
 * under ~240 bytes). It is not a size hide for the *file* — chunk count and the last chunk's length
 * still bound that (F10, accepted).
 */
internal const val SHARE_META_PAD_BUCKET = 256

/** Ceiling on a name or MIME string's UTF-8 length; far above any real file name. */
internal const val SHARE_META_MAX_FIELD_BYTES = 4096

/**
 * Encode + AEAD-seal [meta] under [kShare]. Layout of the plaintext (big-endian):
 * `version:u8 | nameLen:i32 | name | mimeLen:i32 | mime | size:i64 | 0x00…` (padded to
 * [SHARE_META_PAD_BUCKET]).
 */
fun sealShareMeta(
    crypto: SyncCryptoManager,
    kShare: ByteArray,
    shareId: String,
    chunkCount: Int,
    meta: ShareMeta,
): ByteArray = crypto.encryptWithAad(encodeShareMeta(meta), kShare, shareMetaAad(shareId, chunkCount))

/**
 * Inverse of [sealShareMeta]. Throws the AEAD's own exception (or
 * [org.tenshu.core.security.AadMismatchException]) on a wrong key / tampered bytes / wrong
 * `(shareId, chunkCount)`, and [ShareMetaMalformedException] on an empty blob or a malformed record.
 */
fun openShareMeta(
    crypto: SyncCryptoManager,
    kShare: ByteArray,
    shareId: String,
    chunkCount: Int,
    sealed: ByteArray,
): ShareMeta {
    if (sealed.isEmpty()) throw ShareMetaMalformedException("share descriptor carries no sealedMeta")
    return decodeShareMeta(crypto.decryptWithAad(sealed, kShare, shareMetaAad(shareId, chunkCount)))
}

internal fun encodeShareMeta(meta: ShareMeta): ByteArray {
    val name = meta.fileName.encodeToByteArray()
    val mime = meta.mimeType.encodeToByteArray()
    require(name.size <= SHARE_META_MAX_FIELD_BYTES) { "share file name too long" }
    require(mime.size <= SHARE_META_MAX_FIELD_BYTES) { "share MIME type too long" }
    val raw = 1 + 4 + name.size + 4 + mime.size + 8
    val padded = ((raw + SHARE_META_PAD_BUCKET - 1) / SHARE_META_PAD_BUCKET) * SHARE_META_PAD_BUCKET
    val out = ByteArray(padded)
    var p = 0
    out[p++] = SHARE_META_VERSION
    p = putInt(out, p, name.size)
    name.copyInto(out, p); p += name.size
    p = putInt(out, p, mime.size)
    mime.copyInto(out, p); p += mime.size
    putLong(out, p, meta.size)
    return out
}

internal fun decodeShareMeta(bytes: ByteArray): ShareMeta {
    fun malformed(why: String): Nothing = throw ShareMetaMalformedException("malformed sealedMeta: $why")
    if (bytes.isEmpty() || bytes[0] != SHARE_META_VERSION) malformed("unknown version")
    var p = 1
    fun readLen(): Int {
        if (p + 4 > bytes.size) malformed("truncated")
        val n = getInt(bytes, p); p += 4
        if (n < 0 || n > SHARE_META_MAX_FIELD_BYTES || p + n > bytes.size) malformed("bad length")
        return n
    }
    fun readUtf8(n: Int): String =
        try {
            bytes.decodeToString(p, p + n, throwOnInvalidSequence = true).also { p += n }
        } catch (e: CharacterCodingException) {
            malformed("invalid UTF-8")
        }
    val name = readUtf8(readLen())
    val mime = readUtf8(readLen())
    if (p + 8 > bytes.size) malformed("truncated")
    val size = getLong(bytes, p); p += 8
    if (size < 0) malformed("negative size")
    for (i in p until bytes.size) if (bytes[i] != 0.toByte()) malformed("non-zero padding")
    if (name.isEmpty()) malformed("empty file name")
    return ShareMeta(fileName = name, mimeType = mime, size = size)
}

private fun putInt(b: ByteArray, at: Int, v: Int): Int {
    for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte()
    return at + 4
}

private fun putLong(b: ByteArray, at: Int, v: Long) {
    for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
}

private fun getInt(b: ByteArray, at: Int): Int {
    var v = 0
    for (i in 0 until 4) v = (v shl 8) or (b[at + i].toInt() and 0xFF)
    return v
}

private fun getLong(b: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
    return v
}
