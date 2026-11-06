package org.tenshu.core.ingest

/**
 * Wire-safe handle for a Control-owned macOS security-scoped folder grant.
 *
 * ⚠⚠ **The bookmark itself never leaves Control.** Control keeps the bookmark bytes in its own
 * container under [grantId] and is the only process that reads, refreshes or replaces them. Drive
 * stores only this handle (in its selection, and so in `IngestSource.watchedPaths`), which is why
 * a refreshed stale bookmark cannot be overwritten by Drive re-registering its selection, and why
 * the folder's absolute path (which a bookmark embeds) does not travel in synced preferences.
 *
 * [displayName] rides along so Drive can render the row without asking Control.
 */
object SecurityScopedFolderGrant {
    private const val PREFIX = "tenshu.security-scoped-folder.v2:"
    private const val SEPARATOR = ":"
    private val GRANT_ID = Regex("[0-9a-f]{32}")

    data class Decoded(
        /** 128-bit lowercase hex; safe to use as a file name (validated on decode). */
        val grantId: String,
        val displayName: String,
    )

    fun isValidGrantId(grantId: String): Boolean = GRANT_ID.matches(grantId)

    fun encode(grantId: String, displayName: String): String {
        require(isValidGrantId(grantId)) { "A folder grant id is 32 lowercase hex digits" }
        require(displayName.isNotBlank()) { "A folder grant needs a display name" }
        return PREFIX + grantId + SEPARATOR + displayName.encodeToByteArray().toHex()
    }

    fun decode(value: String): Decoded? {
        if (!value.startsWith(PREFIX)) return null
        val body = value.removePrefix(PREFIX)
        val separator = body.indexOf(SEPARATOR)
        if (separator <= 0 || separator == body.lastIndex) return null
        val grantId = body.substring(0, separator).takeIf(::isValidGrantId) ?: return null
        val displayName = body.substring(separator + SEPARATOR.length).fromHex()?.decodeToString()
            ?: return null
        if (displayName.isBlank()) return null
        return Decoded(grantId, displayName)
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private const val HEX = "0123456789abcdef"

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }
}
