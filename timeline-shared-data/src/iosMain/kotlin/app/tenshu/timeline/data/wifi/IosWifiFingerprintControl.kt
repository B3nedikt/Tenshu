package app.tenshu.timeline.data.wifi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/**
 * iOS [WifiFingerprintControl]: the user's Wi-Fi-place-recognition opt-in, persisted to
 * `NSUserDefaults` so it survives the process teardown that happens between every pair of location
 * events. Mirror of `AndroidWifiFingerprintControl` and the sibling
 * [app.tenshu.timeline.data.capture.IosTimelineCaptureControl].
 *
 * Off by default. Flipping it off stops both the capture-time recognise and the learn immediately —
 * [WifiPlaceFingerprint] re-reads this before it touches the radio, so an opted-out user's BSSID is
 * never read at all.
 */
class IosWifiFingerprintControl(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : WifiFingerprintControl {

    private val _enabled = MutableStateFlow(defaults.boolForKey(KEY_ENABLED))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_ENABLED)
        _enabled.value = enabled
    }

    private companion object {
        const val KEY_ENABLED = "tenshu_timeline_wifi_fingerprint_enabled"
    }
}
