package app.tenshu.windows

import java.security.MessageDigest

/**
 * The 13-character **publisher id** Windows derives from an MSIX manifest's `Publisher` string —
 * the half of a package family name after the `_` (`Name_PublisherId`), and what the Windows peer
 * gate pins (`docs/proposals/desktop-windows-port.md` §3.2).
 *
 * Derivation: SHA-256 over the publisher string as UTF-16LE (no BOM), the first 8 bytes of the
 * digest read as a 64-bit stream, padded with one zero bit to 65 and encoded 5 bits at a time in
 * the alphabet `0-9 a-z` minus `i l o u`. Pinned by a known answer: Microsoft's own publisher,
 * `CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond, S=Washington, C=US`, is
 * `8wekyb3d8bbwe` (`MsixPublisherIdTest`).
 *
 * ⚠ The string must be byte-for-byte the manifest's `Publisher` — which must in turn equal the
 * signing certificate's subject. For a Store package that is the publisher Partner Center assigns,
 * not anything on our certificate (the Store re-signs).
 */
object MsixPublisherId {

    private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

    fun of(publisher: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publisher.toByteArray(Charsets.UTF_16LE))
        var bits = 0L
        for (i in 0 until 8) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
        val out = StringBuilder(13)
        // 65 bits: the 64 hash bits followed by one zero bit, taken 5 at a time from the top. Group g
        // ends `59 - 5g` bits above the hash's low bit; the last group (-1) reaches into the pad bit.
        for (group in 0 until 13) {
            val shift = 59 - 5 * group
            val value = if (shift >= 0) (bits ushr shift) and 0x1F else (bits shl -shift) and 0x1F
            out.append(ALPHABET[value.toInt()])
        }
        return out.toString()
    }
}
