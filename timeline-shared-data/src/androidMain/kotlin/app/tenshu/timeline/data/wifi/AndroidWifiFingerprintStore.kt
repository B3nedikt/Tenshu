package app.tenshu.timeline.data.wifi

import android.content.Context

/**
 * Android [WifiFingerprintStore]: a **device-local, never-synced** `network → placeId` map in
 * `SharedPreferences` — one `b:<bssid>` entry per radio and one `s:<ssid>` entry per venue (the map
 * is small; no JSON needed). Network identifiers pin a location precisely, so they stay on the one
 * device that observed them — never on the sync plane, never on the relay (HR #1). Mirror of
 * `CaptureHealthStore` / `AndroidPlaceGeocodingControl`.
 *
 * The `b:`/`s:` prefixes keep the two indexes apart in one preferences file, so the SSID index
 * (§8.4 F2) needed **no migration** — existing `b:` bindings keep working untouched.
 */
class AndroidWifiFingerprintStore(context: Context) : WifiFingerprintStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun remember(bssid: String, placeId: String) {
        prefs.edit().putString(bssidKey(bssid), placeId).apply()
    }

    override fun placeFor(bssid: String): String? = prefs.getString(bssidKey(bssid), null)

    override fun rememberSsid(ssid: String, placeId: String) {
        prefs.edit().putString(ssidKey(ssid), placeId).apply()
    }

    override fun placeForSsid(ssid: String): String? = prefs.getString(ssidKey(ssid), null)

    override fun knownPlaceIds(): Collection<String> =
        prefs.all.values.filterIsInstance<String>().toSet()

    /** Case-insensitive: BSSIDs can come back with mixed casing across reads. */
    private fun bssidKey(bssid: String) = "b:${bssid.lowercase()}"

    /** ⚠ SSIDs are case-*sensitive* by spec, so unlike a BSSID this key is not lowercased. */
    private fun ssidKey(ssid: String) = "s:$ssid"

    private companion object {
        const val PREFS = "tenshu_timeline_wifi_fingerprint"
    }
}
