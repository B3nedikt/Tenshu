@file:OptIn(ExperimentalForeignApi::class)

package app.tenshu.fit.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The RC-5 in-flight capture journal on iOS (fit-app.md §13.5) — the platform twin of Android's
 * `FileRecordingJournal`, rooted in Application Support like the §11.3 outbox (never reclaimed
 * under storage pressure; app-private — the same threat-model A34 posture: local-only, never a
 * wire format, deleted at save/discard).
 *
 * Unlike Android's append-only file this one is **whole-snapshot, atomically replaced** per
 * flush (`writeToFile(atomically = true)`): the engine already holds the full session buffers,
 * a two-hour workout is only a few hundred KB of lines, and an atomic replace can never leave a
 * torn tail at all (the codec's tolerance then only guards Android).
 */
internal class IosRecordingJournal(fileName: String = "recording.journal") {

    private val path: String = run {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .filterIsInstance<String>()
            .firstOrNull()
            ?: NSTemporaryDirectory()
        NSFileManager.defaultManager.createDirectoryAtPath(base, true, null, null)
        "$base/$fileName"
    }

    fun write(lines: List<String>) {
        NSString.create(string = lines.joinToString("\n"))
            .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    /** Null when no journal exists — the common case at every healthy launch. */
    fun readAll(): List<String>? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        val content = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return null
        return content.split('\n')
    }

    fun clear() {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}
