package app.tenshu.mapregion.app

/**
 * A **lazy byte source** for a region's PMTiles archive — the streamed-default delivery
 * (`docs/proposals/maps-region-delivery.md` D10), shared by every `:ui-map` consumer's render
 * terminus. A `:ui-map` surface wraps this into its `RegionStreamSource`, which fetches only the
 * tiles the static camera needs (via a sparse file) instead of downloading the whole region.
 *
 * Compose-/`:ui-map`-free by construction (mirrors [RegionTerminus]) so an app's data/VM layer can
 * hold it without pulling the basemap engine; [readRange] is bound to the proxy's `streamRegionRange`
 * op by [RegionTerminus.renderableStream].
 */
class RegionStreamHandle(
    val regionId: String,
    val totalSize: Long,
    val readRange: suspend (offset: Long, length: Int) -> ByteArray?,
)
