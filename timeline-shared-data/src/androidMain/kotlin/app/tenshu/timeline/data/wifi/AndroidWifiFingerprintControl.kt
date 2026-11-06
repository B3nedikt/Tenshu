package app.tenshu.timeline.data.wifi

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [WifiFingerprintControl]: the user's Wi-Fi-place-recognition opt-in, persisted to
 * `SharedPreferences` so it survives process death. Off by default; flipping it off stops both the
 * capture-time recognise and the learn immediately (the facade re-checks it every call). Mirror of
 * `AndroidPlaceGeocodingControl`.
 */
class AndroidWifiFingerprintControl(context: Context) : WifiFingerprintControl {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    private companion object {
        const val PREFS = "tenshu_timeline_wifi"
        const val KEY_ENABLED = "wifi_fingerprint_enabled"
    }
}
