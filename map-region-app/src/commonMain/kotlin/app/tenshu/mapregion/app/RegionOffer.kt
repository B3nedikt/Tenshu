package app.tenshu.mapregion.app

/**
 * The offline offer for a place or route: the catalog region that **covers** it, its size, and whether it
 * is already resident (`maps-region-delivery.md` §7.1 track 3d). Produced by [RegionTerminus.offerFor],
 * consumed by each app's contextual "keep this area offline" affordance.
 *
 * ⚠ [byteSize] is the whole bundle — hundreds of MB for a real region. It is part of the offer precisely
 * so the surface can be honest about the cost: the map already renders by streaming a few percent of it
 * (D10), so downloading the rest buys **availability without a network and no per-viewport request
 * pattern** (T-MR-03), not a working map.
 */
data class RegionOffer(
    val regionId: String,
    val name: String,
    val byteSize: Long,
    val installed: Boolean,
)
