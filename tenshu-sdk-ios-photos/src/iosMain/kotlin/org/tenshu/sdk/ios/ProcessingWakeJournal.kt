@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.tenshu.sdk.ios

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The processing wake's journal (`ios-on-device-inference.md` §16, `how-to/photos-processing-wake.md`):
 * one UTC-stamped line per arm, withdrawal, launch and end, in Photos' **private** inference root —
 * what the device runbook's three nights read, since nothing is attached to the phone overnight and
 * stdout goes nowhere. ⚠ Device-local and derived, never synced; kept under [MAX_CHARS] by dropping
 * its older half. A failed write is said and nothing else: the wake never depends on it.
 */
internal object ProcessingWakeJournal {
    const val FILE: String = "processing-wakes.log"
    private const val MAX_CHARS: Int = 64 * 1024

    fun append(root: String, event: String) {
        runCatching {
            val path = "$root/$FILE"
            val manager = NSFileManager.defaultManager
            val old: String = if (manager.fileExistsAtPath(path)) NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: "" else ""
            val kept: String = if (old.length > MAX_CHARS) old.substring(old.length / 2).substringAfter('\n') else old
            manager.createDirectoryAtPath(root, true, null, null)
            NSString.create(string = kept + "${kotlin.time.Clock.System.now()} $event\n")
                .writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        }.onFailure { println("[photos-wake] journal not written: $it") }
    }

    /** The journal under [root], or `""`. */
    fun read(root: String): String = NSString.stringWithContentsOfFile("$root/$FILE", NSUTF8StringEncoding, null) ?: ""
}
