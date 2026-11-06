package app.tenshu.timeline.android.geocode

import app.tenshu.timeline.data.geocode.GeocodeResult
import app.tenshu.timeline.data.geocode.PlaceGeocodingEgress
import app.tenshu.timeline.data.geocode.ReverseGeocoder
import kotlin.math.roundToLong

/**
 * The **proxy tier** of the geocoder chain (`timeline-movement-capture.md` §11.3 P3): resolves a
 * coordinate to a venue label (an office's name) via the proxy-mediated `ReverseGeocodePlace` op — **Control**
 * does the network, the Timeline app stays `INTERNET`-free.
 *
 * ⚠⚠ **This class is where `city` used to be lost.** It built `GeocodeResult(label = …)` from an op
 * that returned a bare string, so `city` and `countryCode` defaulted to `""` on every lookup — and
 * because this tier runs *first* in the Android chain, they were blank for every place ever geocoded
 * (measured: nine geocoded places on a real store, nine blank cities). That silently disabled
 * `PlaceLabelEnricher`'s accuracy-coarsening, whose second conjunct is `result.city.isNotBlank()`.
 * The op now carries the locality tiers, so the enricher's rule can fire.
 *
 * ⚠ A blank label still maps to `null`, which is what lets the chain fall through to the device
 * geocoder — a *found* place with no known city is not a failure and must not be treated as one.
 *
 * ⚠⚠ Speaks to the proxy only through [PlaceGeocodingEgress], which re-checks the opt-in on every
 * call. [app.tenshu.timeline.data.geocode.PlaceLabelEnricher] checks it too, but a tier must not be
 * safe only because of who happens to call it.
 */
class ProxyReverseGeocoder(private val egress: PlaceGeocodingEgress) : ReverseGeocoder {

    /**
     * ⚠ Blank → `null`: "no stop in range" and "the tier could not answer" are the same thing to the
     * caller here, and both mean *fall back to the city*. The op returns `""` for both by design.
     */
    override suspend fun transitStop(lat: Double, lng: Double, withinM: Double): String? =
        egress.transitStopNear((lat * 1e7).roundToLong(), (lng * 1e7).roundToLong(), withinM.roundToLong())

    override suspend fun resolve(lat: Double, lng: Double): GeocodeResult? {
        val place = egress.placeAt((lat * 1e7).roundToLong(), (lng * 1e7).roundToLong()) ?: return null
        val label = place.label.get()
        if (label.isBlank()) return null
        return GeocodeResult(
            label = label,
            city = place.city.get(),
            countryCode = place.countryCode.get(),
        )
    }
}
