package app.tenshu.maps.model

/**
 * One row in the region picker (`docs/proposals/maps-region-delivery.md` D5): a catalog region
 * joined with local install state. [byteSize] is carried raw (bytes) — the UI formats it, so the
 * logic layer stays string-free (ADR 0016).
 */
data class RegionItem(
    val regionId: String,
    val name: String,
    val byteSize: Long,
    val installed: Boolean,
)
