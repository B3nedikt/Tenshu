package org.tenshu.core.common.util

/**
 * JS incremental SHA-256.
 *
 * ⚠ **Not memory-bounded**: this buffers the chunks and hashes them at the end with the one-shot
 * [sha256], so it satisfies the API without satisfying its *point*. That is a deliberate, contained
 * cheat: the only caller that needs the streaming property is the **proxy-side region downloader**
 * (`maps-region-delivery.md` §7.1 track 3e), and regions are downloaded by Control on Android/desktop
 * — never by the web runtime, which has no region store at all. If a JS caller ever does need to hash
 * hundreds of megabytes, swap this for `@noble/hashes`' incremental API (`sha256.create()`), which the
 * bundled dependency already provides.
 */
actual class Sha256Digest actual constructor() {

    private val chunks = mutableListOf<ByteArray>()
    private var total = 0

    actual fun update(bytes: ByteArray, length: Int) {
        if (length <= 0) return
        chunks += if (length == bytes.size) bytes.copyOf() else bytes.copyOf(length)
        total += length
    }

    actual fun digest(): ByteArray {
        val all = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(all, destinationOffset = offset)
            offset += chunk.size
        }
        return sha256(all)
    }
}
