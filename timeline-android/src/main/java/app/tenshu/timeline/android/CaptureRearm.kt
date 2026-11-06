package app.tenshu.timeline.android

import android.content.Context
import android.util.Log
import app.tenshu.timeline.data.capture.CaptureWatchdogState
import app.tenshu.diagnostics.Diagnostics
import app.tenshu.timeline.data.capture.decideCaptureRestart

/**
 * The one place that turns the [decideCaptureRestart] verdict into action, shared by the boot
 * receiver and the watchdog worker so both behave identically.
 *
 * ⚠ **Starting a foreground service from the background can be refused** on Android 12+
 * (`ForegroundServiceStartNotAllowedException`) unless the app is battery-optimisation-exempt or in
 * an allowed window (a `BOOT_COMPLETED` receiver is one). The start is wrapped so a refusal is a
 * logged no-op — capture simply starts on the next allowed trigger (app foreground, or the next
 * watchdog tick once the user has granted the exemption) rather than crashing the caller. This is why
 * the battery-exemption prompt (`BatteryOptimization`) matters: it is what makes background restarts
 * reliably *allowed*, not merely attempted.
 */
internal object CaptureRearm {

    /** Restart capture if the opt-in is on and the service is dead. Returns whether it restarted. */
    fun rearmIfNeeded(context: Context, trigger: String): Boolean {
        val state = CaptureWatchdogState(
            captureEnabled = AndroidTimelineCaptureControl.isCaptureEnabled(context),
            serviceRunning = CaptureServiceStatus.isRunning(context),
        )
        val verdict = decideCaptureRestart(state)
        if (!verdict.restart) return false

        // Starting a location FGS without location permission is a guaranteed SecurityException
        // crash — never attempt it. (The FGS also guards startForeground for the eligible-state case
        // that permission alone can't satisfy on Android 14+.)
        if (!hasLocationPermission(context)) {
            Diagnostics.w("watchdog", "capture on but no location permission — deferring re-arm ($trigger)", code = "watchdog_no_permission")
            return false
        }

        return try {
            TimelineCaptureServiceLauncher(context.applicationContext).start()
            CaptureHealthStore(context).recordWatchdogRestart(System.currentTimeMillis())
            Log.i(TAG, "capture re-armed ($trigger): ${verdict.reason}")
            Diagnostics.w("watchdog", "re-armed capture ($trigger): ${verdict.reason}", code = "watchdog_restart")
            true
        } catch (e: Exception) {
            // Most likely ForegroundServiceStartNotAllowedException — not exempt / not in a window.
            Log.w(TAG, "capture re-arm refused ($trigger): ${e.message}")
            Diagnostics.e("watchdog", "re-arm refused ($trigger): ${e.message} — not battery-exempt?", code = "watchdog_refused")
            false
        }
    }

    private fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private const val TAG = "TenshuCaptureRearm"
}
