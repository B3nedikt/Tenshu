package app.tenshu.timeline.android

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Start/stop seam for the [TimelineCaptureForegroundService] (I3b-2). `MainActivity` calls [start]
 * once the user has opted in *and* the location permission is held, and [stop] when the user turns
 * capture off. Repeated [start] while already running is a benign no-op (Android coalesces).
 */
class TimelineCaptureServiceLauncher(private val appContext: Context) {

    /**
     * Starts capture, returning whether the OS allowed it.
     *
     * ⚠ Refusable since API 31. `MainActivity` calls this straight after an on-screen opt-in, so it
     * is normally allowed; returning the outcome rather than throwing means a background caller
     * degrades (capture simply does not start) instead of crashing the process — the failure mode
     * that took Control and Fit down on 2026-07-30.
     */
    fun start(): Boolean = try {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, TimelineCaptureForegroundService::class.java),
        )
        true
    } catch (_: IllegalStateException) {
        false
    }

    fun stop() {
        appContext.stopService(Intent(appContext, TimelineCaptureForegroundService::class.java))
    }
}
