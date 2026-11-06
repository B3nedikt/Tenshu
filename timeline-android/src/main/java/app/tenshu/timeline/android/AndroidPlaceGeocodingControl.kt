package app.tenshu.timeline.android

import android.content.Context
import androidx.core.content.edit
import app.tenshu.timeline.data.geocode.PlaceGeocodingControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android [PlaceGeocodingControl]: the user's place-name-lookup opt-in, persisted to
 * `SharedPreferences` so it survives process death (threat-model A35(d) — lookups are off by
 * default and only this flag turns them on; flipping it off stops all lookups immediately via
 * the enricher's per-lookup consent check). Mirror of [AndroidTimelineCaptureControl].
 */
class AndroidPlaceGeocodingControl(context: Context) : PlaceGeocodingControl {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        _enabled.value = enabled
    }

    private companion object {
        const val PREFS = "tenshu_timeline_geocoding"
        const val KEY_ENABLED = "geocoding_enabled"
    }
}
