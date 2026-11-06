package app.tenshu.timeline.data.geocode

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The **Android device-tier geocoder** (`docs/proposals/place-geocoding.md` G2):
 * `android.location.Geocoder` behind the [ReverseGeocoder] seam. This is the platform *address*
 * API — server-backed ("native ≠ private", `timeline-services.md` §3.3: the coordinate reaches
 * the OS vendor's geocoding backend), which is exactly why every call sits behind the
 * [PlaceGeocodingControl] consent inside [PlaceLabelEnricher]. It is deliberately NOT the Places
 * SDK (billed, no-store ToS) — platform address output is storable (threat-model A35(d)).
 *
 * Gated on [Geocoder.isPresent]; API 33+ uses the async [Geocoder.GeocodeListener], older
 * releases dispatch the blocking call to IO. Any failure — no backend, throttled, garbage — is
 * an answerless `null`; the chain/enricher treat this tier as best-effort.
 */
class AndroidReverseGeocoder(context: Context) : ReverseGeocoder {

    private val appContext = context.applicationContext

    override suspend fun resolve(lat: Double, lng: Double): GeocodeResult? {
        if (!Geocoder.isPresent()) return null
        val address = lookup(lat, lng) ?: return null
        return geocodeResultOf(
            featureName = address.featureName,
            subThoroughfare = address.subThoroughfare,
            thoroughfare = address.thoroughfare,
            locality = address.locality,
            countryCode = address.countryCode,
        )
    }

    /**
     * ⚠ The platform geocoder is the *right* tier for this and the proxy tier is not: Photon is
     * POI-first (it exists to answer with the venue where this returns the street), so on Android the
     * chain's second tier is where the street actually lives. It also costs no egress — which is why
     * asking it in the coarse branch is affordable.
     */
    override suspend fun street(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return lookup(lat, lng)?.thoroughfare?.takeIf { it.isNotBlank() }
    }

    private suspend fun lookup(lat: Double, lng: Double): Address? {
        val geocoder = Geocoder(appContext, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(
                    lat,
                    lng,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            cont.resume(null)
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
