package app.tenshu.fit.ui.screens.record

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Reasons that mean **the system ended us**, not the app or its user.
 *
 * `REASON_OTHER` is the one that matters in practice: it is what an OEM killer reports (ColorOS
 * logged `o-kill(6)` at `importance=125`, i.e. it killed a process holding a foreground service).
 * `SIGNALED` covers a SIGKILL we did not ask for — the classic low-level kill — and the two
 * resource reasons cover the AOSP paths.
 *
 * ⚠ Everything else is deliberately absent, and the exclusions are the load-bearing half:
 * `REASON_USER_REQUESTED` (a force-stop or a swipe from recents), `REASON_CRASH` / `_NATIVE`,
 * `REASON_ANR`, `REASON_PACKAGE_UPDATED` (every reinstall) are all *us or the user*. Blaming the
 * phone for our own crash would be worse than saying nothing at all.
 */
private val SYSTEM_KILL_REASONS = setOf(
    ApplicationExitInfo.REASON_OTHER,
    ApplicationExitInfo.REASON_SIGNALED,
    ApplicationExitInfo.REASON_LOW_MEMORY,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
)

/** The OS keeps at most 16 records per package; a handful is plenty to find the newest kill. */
private const val EXIT_RECORDS = 8

@Composable
actual fun rememberProcessDeathReporter(): ProcessDeathReporter {
    val context = LocalContext.current
    return remember(context) { AndroidProcessDeathReporter(context) }
}

private class AndroidProcessDeathReporter(private val context: Context) : ProcessDeathReporter {

    override fun lastSystemKillWallMs(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        // pid 0 = "any process of this package". Reading your own exit records needs no permission.
        val records = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_RECORDS)
        }.getOrNull().orEmpty()
        return records.filter { it.reason in SYSTEM_KILL_REASONS }.maxOfOrNull { it.timestamp }
    }
}
