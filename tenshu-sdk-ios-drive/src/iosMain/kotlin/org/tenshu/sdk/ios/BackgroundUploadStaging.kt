@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * Small App-Group file-I/O helpers shared by the O3-B background-upload path
 * (ios-drive-parity.md P3b) — [BackgroundUploadJournal] + [BackgroundUploadManager].
 *
 * Deliberately the SAME `NSData`-over-a-pinned-`ByteArray` shape the rest of the route-1
 * runtime uses (`DriveLocalRuntime.debugUploadProbe`, `HlcWatermarkFile`) — no new interop
 * surface, so a byte written here reads back byte-identical to a chunk `emitFileContent`
 * produced. All bytes handled here are already opaque AEAD ciphertext (House Rule #1).
 */

/** Write [bytes] to [path] atomically; returns whether the write succeeded. */
internal fun writeBytesToFile(path: String, bytes: ByteArray): Boolean {
    val data: NSData = if (bytes.isEmpty()) {
        NSData()
    } else {
        memScoped { NSData.create(bytes = allocArrayOf(bytes), length = bytes.size.convert()) }
    }
    return data.writeToFile(path, atomically = true)
}

/** Read [path] into a `ByteArray`, or null when the file is absent/unreadable. */
internal fun readBytesFromFile(path: String): ByteArray? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val size = data.length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return out
}

/** `mkdir -p` for [path] (no-op if it already exists). */
internal fun ensureDirectory(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
}

/** Best-effort delete of [path] (a spent staging file); missing-file is not an error. */
internal fun deleteFileAt(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}
