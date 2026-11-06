package app.tenshu.timeline.android

import android.content.Context
import androidx.core.content.edit
import app.tenshu.timeline.capture.TimelineCaptureControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [TimelineCaptureControl]: the user's capture opt-in, persisted to `SharedPreferences` so it
 * survives process death (threat-model A35 — capture is off by default and only this flag turns it
 * on). Holds *intent* only; `MainActivity` observes [enabled] and does the OS-permission grant +
 * starting/stopping the [TimelineCaptureForegroundService].
 */
class AndroidTimelineCaptureControl(context: Context) : TimelineCaptureControl {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        _enabled.value = enabled
    }

    internal companion object {
        const val PREFS = "tenshu_timeline_capture"
        const val KEY_ENABLED = "capture_enabled"

        /**
         * Read the persisted opt-in **without** the Koin graph — for the boot receiver and the
         * watchdog worker, which may run before (or independently of) app-scope initialisation and
         * must not depend on it. Single source of the pref name so it can't drift from [setEnabled].
         */
        fun isCaptureEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
    }
}
