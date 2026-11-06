package org.tenshu.core.common.util

/**
 * An **incremental** SHA-256 — hash bytes as they arrive, without ever holding them all.
 *
 * The one-shot [sha256] takes a `ByteArray`, which means the caller must first materialise the whole
 * input. That is fine for a key or a manifest; it is fatal for a **map region bundle**, which is
 * hundreds of megabytes: `DefaultRegionDownloader` used to buffer the entire bundle to hash it, and
 * Control died with `OutOfMemoryError: Failed to allocate a 140165400 byte allocation` on the first
 * real region (`maps-region-delivery.md` §7.1 track 3e). Streaming the download needs a digest that
 * can be fed chunk by chunk.
 *
 * Not thread-safe; feed one stream per instance.
 */
expect class Sha256Digest() {

    /** Feeds the first [length] bytes of [bytes] into the digest. */
    fun update(bytes: ByteArray, length: Int = bytes.size)

    /** Finalises and returns the 32-byte digest. The instance must not be used afterwards. */
    fun digest(): ByteArray
}
