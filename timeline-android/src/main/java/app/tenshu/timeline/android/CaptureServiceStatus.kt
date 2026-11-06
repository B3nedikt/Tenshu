package app.tenshu.timeline.android

import android.app.ActivityManager
import android.content.Context

/**
 * Is [TimelineCaptureForegroundService] actually running in this process right now?
 *
 * `ActivityManager.getRunningServices` is deprecated for *observing other apps*, but it still
 * reliably returns the **caller's own** services on every Android version — which is exactly the
 * question the watchdog asks. This is the signal that catches the OEM force-stop: the opt-in pref
 * says capture should run, but the service is gone.
 */
internal object CaptureServiceStatus {

    fun isRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val target = TimelineCaptureForegroundService::class.java.name
        @Suppress("DEPRECATION")
        return runCatching {
            am.getRunningServices(Int.MAX_VALUE).any { it.service.className == target }
        }.getOrDefault(false)
    }
}
