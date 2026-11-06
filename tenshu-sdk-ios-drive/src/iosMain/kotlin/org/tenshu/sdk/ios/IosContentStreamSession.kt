@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.drive.data.StreamingContentHandle
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * Swift-facing wrapper over a client [StreamingContentHandle] (ios-drive-parity.md S7).
 *
 * The Swift `AVAssetResourceLoaderDelegate` (`drive-ios/Sources/StreamingVideoLoader.swift`)
 * drives seekable video playback by pulling ≤256 KiB ranges through [readRange]; the
 * `ByteArray`→`NSData` conversion happens HERE in Kotlin so Swift receives a `Data`.
 *
 * **Bridge posture (vs D2a):** only bounded ranges ever cross the SKIE byte bridge — never the
 * whole file. The P4 "no bytes across SKIE" rule was about multi-MB *whole* payloads (routed via
 * a Kotlin-written temp file); a per-range 256 KiB `NSData` is exactly the streaming win — no
 * whole-file plaintext temp, no whole-`ByteArray` materialisation. Only the touched chunks
 * decrypt (bounded memory in the proxy-side [app.tenshu.drive.data.StreamingContentHandle] session
 * LRU). House Rule #1 unchanged — the bytes were decrypted client-side over the same op path.
 */
class IosContentStreamSession internal constructor(
    private val handle: StreamingContentHandle,
) {
    /** Plaintext content length up front — the resource loader's `contentInformationRequest`. */
    val totalSize: Long get() = handle.totalSize

    /**
     * Read up to [length] bytes at [offset] as `NSData`. Empty `NSData` at/after EOF; `null` if
     * the stream is closed or a covering chunk fails its per-chunk integrity gate (the delegate
     * then finishes the request — a tampered range fails closed).
     */
    suspend fun readRange(offset: Long, length: Int): NSData? =
        handle.readRange(offset, length)?.toNSData()

    /** Release the proxy-side session (its decrypted-chunk LRU). Idempotent. */
    suspend fun close() = handle.close()
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        memScoped { NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong()) }
    }
