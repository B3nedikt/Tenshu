package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit: **every platform hands `SyncManagerImpl` the `Database`'s lock.**
 *
 * ⚠⚠ `SyncManagerImpl(storeLock = …)` defaults to a no-op lock, and a no-op lock is a fully
 * working sync manager — its cursor rows simply reach the object store unlocked again, racing every
 * op on the device. Nothing fails; about one run in a thousand a row that exists reads as absent.
 * Only the call site shows it, which is the same shape [ThumbnailLadderRendererWiringTest] and
 * [MotionThumbnailRendererWiringTest] guard.
 *
 * ⚠ All three modules, read as text: `androidMain` and `iosMain` are out of a desktop test's
 * compile reach, and the harness's own lock check (`:system-test-utils`, `LockCheckedObjectStore`)
 * covers only the harness's wiring.
 */
class SyncManagerStoreLockWiringTest {

    @Test
    fun `every platform passes the database lock to the sync manager`() {
        for (sourceSet in listOf("desktopMain", "androidMain", "iosMain")) {
            val module = File("src/$sourceSet/kotlin/app/tenshu/control/di/PlatformProxyModule.kt")
            // ⚠ Anti-vacuity: a scan that finds no file passes for the wrong reason.
            assertTrue(module.isFile, "the $sourceSet PlatformProxyModule moved; this test is no longer guarding.")

            val arguments = argumentsOf(module.readText(), "SyncManagerImpl(")
            assertTrue(arguments != null, "no SyncManagerImpl construction found in $sourceSet — the scan is stale.")
            assertTrue(
                Regex("""storeLock\s*=\s*get<DatabaseLock>\(\)""").containsMatchIn(arguments),
                "$sourceSet constructs SyncManagerImpl without `storeLock = get<DatabaseLock>()`, so its " +
                    "cursor rows reach the object store outside the DatabaseLock.",
            )
        }
    }

    /** The argument text of the first invocation of [call], balanced on parentheses. */
    private fun argumentsOf(text: String, call: String): String? {
        val start = Regex("(^|[^A-Za-z0-9_])" + Regex.escape(call)).find(text)?.range?.last ?: return null
        var depth = 1
        var i = start + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start + 1, (i - 1).coerceAtLeast(start + 1))
    }
}
