package app.tenshu.timeline.data

/**
 * A **lazy byte source** for a trip route's offline-basemap region — the streamed-default delivery
 * (`docs/proposals/maps-region-delivery.md` D10). The render fetches only the tiles the route's
 * static camera needs (via a sparse file) instead of downloading the whole region. Kept neutral
 * (no `:map-region` / `:ui-map` dependency) so this VM/logic module stays light and fakeable — the
 * real [readRange] is bound to the proxy's `streamRegionRange` op in `:timeline-shared-ui`, and
 * `TripRouteMap` wraps it into `:ui-map`'s `RegionStreamSource`.
 */
class RouteRegionStream(
    val regionId: String,
    val totalSize: Long,
    val readRange: suspend (offset: Long, length: Int) -> ByteArray?,
)
