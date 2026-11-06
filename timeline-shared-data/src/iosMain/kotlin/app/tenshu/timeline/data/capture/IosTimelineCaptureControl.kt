package app.tenshu.timeline.data.capture

import app.tenshu.timeline.capture.TimelineCaptureControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/**
 * iOS [TimelineCaptureControl]: the user's capture opt-in, persisted to `NSUserDefaults` so it
 * survives process death (threat-model A35(a) — capture is off by default and only this flag turns
 * it on). Mirror of `AndroidTimelineCaptureControl` and the sibling [IosPlaceGeocodingControl]
 * [app.tenshu.timeline.data.geocode.IosPlaceGeocodingControl] shape: this holds *intent* only; the
 * capture runtime enacts it (authorization request + starting/stopping [IosLocationCapture]) and
 * reflects a denied grant back by resetting the flag.
 */
class IosTimelineCaptureControl(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : TimelineCaptureControl {

    private val _enabled = MutableStateFlow(defaults.boolForKey(KEY_ENABLED))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_ENABLED)
        _enabled.value = enabled
    }

    private companion object {
        const val KEY_ENABLED = "tenshu_timeline_capture_enabled"
    }
}
