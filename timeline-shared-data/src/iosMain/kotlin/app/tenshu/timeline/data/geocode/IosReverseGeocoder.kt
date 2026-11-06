package app.tenshu.timeline.data.geocode

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLPlacemark
import kotlin.coroutines.resume

/**
 * The **iOS device-tier geocoder** (`docs/proposals/place-geocoding.md` G5): `CLGeocoder` behind
 * the [ReverseGeocoder] seam. Server-backed like Android's ("native ≠ private" — the coordinate
 * reaches Apple), which is why every call sits behind the [PlaceGeocodingControl] consent inside
 * [PlaceLabelEnricher]; Apple's own guidance is one request at a time, which the enricher's 1 rps
 * throttle satisfies.
 *
 * Deviation from the proposal's "native Swift" sketch, recorded in §4.5's as-built note: the
 * whole enricher stack is Kotlin and `CLGeocoder` is a small, cleanly-interoping Objective-C API,
 * so a Kotlin/Native wrapper here beats exporting a Swift-implementable suspend interface through
 * the XCFramework. Any failure — no network, throttled, nothing there — is an answerless `null`.
 */
class IosReverseGeocoder : ReverseGeocoder {

    override suspend fun resolve(lat: Double, lng: Double): GeocodeResult? {
        val placemark = lookup(lat, lng) ?: return null
        return geocodeResultOf(
            featureName = placemark.name,
            subThoroughfare = placemark.subThoroughfare,
            thoroughfare = placemark.thoroughfare,
            locality = placemark.locality,
            countryCode = placemark.ISOcountryCode,
        )
    }

    private suspend fun lookup(lat: Double, lng: Double): CLPlacemark? =
        suspendCancellableCoroutine { cont ->
            val geocoder = CLGeocoder()
            geocoder.reverseGeocodeLocation(CLLocation(latitude = lat, longitude = lng)) { placemarks, error ->
                cont.resume(if (error != null) null else placemarks?.firstOrNull() as? CLPlacemark)
            }
            cont.invokeOnCancellation { geocoder.cancelGeocode() }
        }
}
