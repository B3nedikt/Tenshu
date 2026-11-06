package org.tenshu.core.common.util

/**
 * Derives the 6-digit contact-verification Short Authentication String per
 * `docs/proposals/contact-invitation-handshake.md` §5.3.
 *
 * ```
 * lo, hi   = sort_lexicographic(publicKeyA, publicKeyB)
 * sasBytes = HKDF-SHA-256(ikm = lo || hi, salt = empty,
 *                         info = "tenshu/contact/sas/v1", length = 4)
 * sas      = (be32(sasBytes) >> 12) % 1_000_000
 * ```
 *
 * Unlike the pairing SAS (`derivePairingSas`, keyed on the handshake's shared
 * secret), this derivation is over **both identity public keys only** —
 * session-independent, so the same code can be compared at handshake time and
 * at any later re-verification (the Signal safety-number shape). Public
 * derivation is correct here: this is mutual key-fingerprint comparison over
 * an out-of-band human channel, not a secrecy mechanism. The lexicographic
 * sort makes the code symmetric — both parties derive the same digits
 * regardless of who passes which key first.
 *
 * The output is a 6-character zero-padded decimal string, e.g. `"482913"`,
 * ready for display.
 */
fun deriveContactSas(
    publicKeyA: ByteArray,
    publicKeyB: ByteArray,
): String {
    val sorted = if (compareLexicographic(publicKeyA, publicKeyB) <= 0) {
        publicKeyA + publicKeyB
    } else {
        publicKeyB + publicKeyA
    }
    val sasBytes = hkdfSha256(
        ikm = sorted,
        salt = ByteArray(0),
        info = CONTACT_SAS_INFO,
        length = 4,
    )
    // Big-endian 32-bit read, top 20 bits → 6-digit decimal (the
    // derivePairingSas truncation, unchanged).
    val be32 = ((sasBytes[0].toInt() and 0xFF) shl 24) or
        ((sasBytes[1].toInt() and 0xFF) shl 16) or
        ((sasBytes[2].toInt() and 0xFF) shl 8) or
        (sasBytes[3].toInt() and 0xFF)
    val truncated = (be32 ushr 12) % 1_000_000
    return truncated.toString().padStart(6, '0')
}

/** Unsigned byte-wise comparison; shorter array wins ties on a common prefix. */
private fun compareLexicographic(a: ByteArray, b: ByteArray): Int {
    val minLength = minOf(a.size, b.size)
    for (i in 0 until minLength) {
        val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
        if (cmp != 0) return cmp
    }
    return a.size.compareTo(b.size)
}

private val CONTACT_SAS_INFO: ByteArray = "tenshu/contact/sas/v1".encodeToByteArray()
