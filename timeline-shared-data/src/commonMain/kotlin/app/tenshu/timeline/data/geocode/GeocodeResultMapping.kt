package app.tenshu.timeline.data.geocode

/**
 * Maps a platform address answer onto a [GeocodeResult] (`docs/proposals/place-geocoding.md` G2).
 * Pure and platform-neutral so the interesting precedence logic is unit-tested device-free while
 * the platform geocoders (`AndroidReverseGeocoder`, iOS `CLGeocoder` in G5) stay thin framework
 * shims that only extract fields.
 *
 * Label precedence: the POI/feature name — unless it is blank or merely the house number
 * (Android's `featureName` degrades to `subThoroughfare` when no POI is known at the coordinate) —
 * then the street, then the locality. All-blank input maps to `null`: "no answer", so the
 * enricher memoises the coordinate as fruitless instead of writing an empty label.
 */
fun geocodeResultOf(
    featureName: String?,
    subThoroughfare: String?,
    thoroughfare: String?,
    locality: String?,
    countryCode: String?,
): GeocodeResult? {
    val feature = featureName?.takeIf { it.isNotBlank() && it != subThoroughfare }
    val label = feature
        ?: thoroughfare?.takeIf { it.isNotBlank() }
        ?: locality?.takeIf { it.isNotBlank() }
        ?: return null
    return GeocodeResult(
        label = label,
        city = locality.orEmpty(),
        countryCode = countryCode.orEmpty(),
    )
}
