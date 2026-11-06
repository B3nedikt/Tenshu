package app.tenshu.maps.data

/**
 * A **lazy byte source** for a region's PMTiles archive — the streamed-default delivery
 * (`docs/proposals/maps-region-delivery.md` D10). The UI wraps this into `:ui-map`'s
 * `RegionStreamSource`, which fetches only the tiles the static snippet camera needs (via a sparse
 * file) instead of downloading the whole region. Kept Compose-/`:ui-map`-free here so the VM can
 * hold it; the client repository binds [readRange] to the proxy's `streamRegionRange` op.
 */
class RegionStreamHandle(
    val regionId: String,
    val totalSize: Long,
    val readRange: suspend (offset: Long, length: Int) -> ByteArray?,
)
