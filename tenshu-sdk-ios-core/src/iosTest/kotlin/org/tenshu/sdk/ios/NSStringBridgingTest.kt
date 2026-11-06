@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the String↔NSString bridging that [SharedReplicaChoice.declare] (Control's replica
 * declaration) and `NsFileOutboxStore.append` (`:app-outbox`) rely on.
 *
 * ## Why this test exists
 *
 * Both write via `(kotlinString as NSString).writeToFile(...)`, and the Kotlin compiler flags that
 * cast **"This cast can never succeed"**. The warning is spurious — Kotlin/Native bridges the two
 * types at runtime — but nothing pinned that, so the warning was read as a live defect and reported
 * as one (`sync-hlc-tip-device-identity.md` TC-2b, since corrected). This test is what makes the
 * answer checkable instead of arguable. (It was written for the July 2026 route-1 App-Group probe,
 * whose own write path never ran headless; the probe is deleted, the cast form is not.)
 *
 * If a future Kotlin/Native toolchain stops bridging and turns the warning into the truth, this
 * goes red here rather than silently in an App-Group write nobody watches.
 */
class NSStringBridgingTest {

    private fun tempDir(): String {
        val dir = NSTemporaryDirectory() + "nsstring-bridging-" +
            NSProcessInfo.processInfo.globallyUniqueString
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return dir
    }

    @Test
    fun aKotlinStringCastToNSStringWritesAndReadsBack() {
        val path = tempDir() + "/marker.txt"

        // The exact form `SharedReplicaChoice.declare` uses.
        @Suppress("CAST_NEVER_SUCCEEDS")
        val ok = ("shared-clock-42" as NSString).writeToFile(
            path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )

        assertTrue(ok, "the cast form reported a failed write")
        assertEquals(
            "shared-clock-42",
            NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null),
            "the bridged write must round-trip, or the cast-form writes in production are lying",
        )
    }
}
