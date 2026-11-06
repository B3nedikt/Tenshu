package app.tenshu.timeline.android

import android.content.Context
import androidx.core.content.edit

/**
 * A tiny, persistent capture-health record — the durable "did it actually run?" trail the user asked
 * for, done without the (read-only, error-only) `LoggingService`.
 *
 * Deliberately just a few timestamps/counters in `SharedPreferences`, not a log stream: they survive
 * process death, cost nothing, and answer the questions that matter over hours — when did the
 * foreground service last (re)start, how many times has the watchdog had to resurrect it (i.e. how
 * hard is the OEM killing us), and when did it last check. Readable on-device and over `adb` without
 * a laptop-side log pipe.
 */
internal class CaptureHealthStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordServiceStarted(nowMs: Long) = prefs.edit { putLong(KEY_LAST_START, nowMs) }

    fun recordWatchdogRestart(nowMs: Long) = prefs.edit {
        putLong(KEY_LAST_RESTART, nowMs)
        putInt(KEY_RESTART_COUNT, prefs.getInt(KEY_RESTART_COUNT, 0) + 1)
    }

    fun recordWatchdogTick(nowMs: Long) = prefs.edit { putLong(KEY_LAST_TICK, nowMs) }

    val lastServiceStartMs: Long get() = prefs.getLong(KEY_LAST_START, 0)
    val lastWatchdogRestartMs: Long get() = prefs.getLong(KEY_LAST_RESTART, 0)
    val watchdogRestartCount: Int get() = prefs.getInt(KEY_RESTART_COUNT, 0)
    val lastWatchdogTickMs: Long get() = prefs.getLong(KEY_LAST_TICK, 0)

    private companion object {
        const val PREFS = "tenshu_timeline_capture_health"
        const val KEY_LAST_START = "last_service_start_ms"
        const val KEY_LAST_RESTART = "last_watchdog_restart_ms"
        const val KEY_RESTART_COUNT = "watchdog_restart_count"
        const val KEY_LAST_TICK = "last_watchdog_tick_ms"
    }
}
