package app.tenshu.timeline.data.wifi

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Android [ConnectedWifi]: the currently-connected access point's BSSID + the network's SSID via
 * [WifiManager].
 *
 * ⚠ `connectionInfo` is deprecated on API 31+ but still returns the connected AP; the real values
 * come back only for a caller holding `ACCESS_FINE_LOCATION` with location on (Timeline holds it,
 * and capture runs in a location foreground service) — otherwise Android hands back the redacted
 * `02:00:00:00:00:00` / `<unknown ssid>`, which the facade discards. Needs `ACCESS_WIFI_STATE`
 * (declared); no `INTERNET`.
 */
class AndroidConnectedWifi(context: Context) : ConnectedWifi {

    private val app = context.applicationContext

    override fun currentBssid(): String? = info()?.bssid

    /** ⚠ Android wraps the SSID in double quotes when it is a valid UTF-8 name — strip them. */
    override fun currentSsid(): String? = info()?.ssid?.removeSurrounding("\"")

    @Suppress("DEPRECATION")
    private fun info() =
        try {
            (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
        } catch (_: Exception) {
            null
        }
}
