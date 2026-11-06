package app.tenshu.timeline.data

/**
 * What it would take to keep a **trip's** map areas offline (`maps-region-delivery.md` §7.1 track 3d).
 *
 * A trip is a *journey*, so unlike a place it can span **several** regions — the offer is a set, and its
 * price is the sum of the ones not yet resident. Kept neutral (no `:map-region` / `:ui-map` dependency) so
 * the VM can hold it; `:timeline-shared-ui` fills it from the shared `RegionTerminus`.
 */
data class TripOfflineOffer(
    val regions: List<TripOfflineRegion>,
) {
    /** The regions this trip crosses that are **not** resident yet — what a download would actually fetch. */
    val missing: List<TripOfflineRegion> get() = regions.filterNot { it.installed }

    /** The honest price of "keep this trip offline": only the bytes not already on disk. */
    val missingBytes: Long get() = missing.sumOf { it.byteSize }

    /** Every region this trip crosses is already resident — the route needs no network at all. */
    val fullyResident: Boolean get() = regions.isNotEmpty() && missing.isEmpty()
}

/** One region a trip's route passes through. */
data class TripOfflineRegion(
    val regionId: String,
    val name: String,
    val byteSize: Long,
    val installed: Boolean,
)
