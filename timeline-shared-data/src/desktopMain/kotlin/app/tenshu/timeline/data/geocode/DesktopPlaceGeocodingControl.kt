package app.tenshu.timeline.data.geocode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.prefs.Preferences

/**
 * Desktop/JVM [PlaceGeocodingControl] (`docs/proposals/place-geocoding.md` G3): the user's
 * place-name-lookup opt-in, persisted via [java.util.prefs.Preferences] so it survives process
 * death — the JVM mirror of `AndroidPlaceGeocodingControl`'s `SharedPreferences` shape
 * (threat-model A35(d): lookups are off by default, only this flag turns them on, and flipping it
 * off stops all lookups immediately via the enricher's per-lookup consent check). Binding it is
 * what makes the shared Settings toggle appear on desktop (the G1 `getOrNull` mechanism).
 *
 * The [node] is injectable so tests round-trip against a scratch node instead of the user's
 * real preference tree.
 */
class DesktopPlaceGeocodingControl(
    private val node: Preferences = Preferences.userRoot().node(DEFAULT_NODE),
) : PlaceGeocodingControl {

    private val _enabled = MutableStateFlow(node.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        node.putBoolean(KEY_ENABLED, enabled)
        try {
            node.flush()
        } catch (_: Exception) {
            // Best-effort promptness: the value is already in the node; the platform syncs later.
        }
        _enabled.value = enabled
    }

    companion object {
        /** Prefs path under the user root — namespaced like the Android prefs file. */
        const val DEFAULT_NODE = "app/tenshu/timeline/geocoding"
        private const val KEY_ENABLED = "geocoding_enabled"
    }
}
