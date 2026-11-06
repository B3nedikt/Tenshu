package app.tenshu.fit.ui.screens.record

import androidx.compose.runtime.Composable

/**
 * Why the last session ended — read from the OS's own record of how this app's processes died.
 *
 * A recovered draft (RC-5) knows capture *stopped*; it cannot know whether the app crashed, the user
 * swiped it away, or the phone killed a healthy foreground service to save battery. The difference
 * matters to the person reading the review screen: "the recording was interrupted" invites them to
 * suspect the app, while "your phone stopped the recording" is both true and actionable — it is the
 * OEM behaviour that ended a 21-minute run mid-stride on 2026-08-15 (§13.5b).
 *
 * Android has kept this since API 30 (`ActivityManager.getHistoricalProcessExitReasons`), needs no
 * permission to read about yourself, and survives reboots — it is the same data
 * `adb shell dumpsys activity exit-info` prints. Platforms without such a record report null, and
 * the review screen keeps its neutral wording.
 */
interface ProcessDeathReporter {

    /**
     * Wall clock (epoch ms) of the most recent process death **the system caused**, or null when
     * there is none on record (or the platform has no record at all).
     *
     * Deaths this app or its user caused — a crash, an ANR, a force-stop, a package update — are
     * deliberately excluded: blaming the phone for our own crash would be worse than saying nothing.
     */
    fun lastSystemKillWallMs(): Long?
}

@Composable
expect fun rememberProcessDeathReporter(): ProcessDeathReporter
