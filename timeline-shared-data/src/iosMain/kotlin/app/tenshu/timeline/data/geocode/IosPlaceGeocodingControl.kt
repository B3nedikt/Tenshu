package app.tenshu.timeline.data.geocode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/**
 * iOS [PlaceGeocodingControl]: the user's place-name-lookup opt-in, persisted to
 * `NSUserDefaults` so it survives process death (threat-model A35(d) — lookups are off by
 * default and only this flag turns them on; flipping it off stops all lookups immediately via
 * the enricher's per-lookup consent check). Mirror of `AndroidPlaceGeocodingControl` /
 * `DesktopPlaceGeocodingControl`.
 */
class IosPlaceGeocodingControl(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : PlaceGeocodingControl {

    private val _enabled = MutableStateFlow(defaults.boolForKey(KEY_ENABLED))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_ENABLED)
        _enabled.value = enabled
    }

    private companion object {
        const val KEY_ENABLED = "tenshu_timeline_geocoding_enabled"
    }
}
