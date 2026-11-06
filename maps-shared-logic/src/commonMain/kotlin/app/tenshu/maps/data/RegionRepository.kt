package app.tenshu.maps.data

import app.tenshu.maps.model.GeoPoint
import app.tenshu.maps.model.RegionItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The Maps-side seam onto offline basemap region delivery (`maps-region-delivery.md` D5). The UI
 * (region picker) depends only on this interface; the client-backed implementation
 * (`ClientRegionRepository`, `:maps-shared-data`) reads the proxy's `MapRegionService` over a
 * `TenshuClient`, and the mock/in-process proxy binds it — the same mock→real swap the Places
 * surface uses.
 */
/**
 * The answer to [RegionRepository.regions] — **three states, not a list.**
 *
 * ⚠⚠ It used to be `List<RegionItem>`, and a failed read degraded to `emptyList()`. The picker
 * cannot tell that from a catalogue with nothing in it, so an unreachable Control made the screen
 * say *there are no maps to download* — about the one feature whose whole purpose is downloading
 * maps. That is the silent-blank failure `ADR 0025` exists to prevent, produced from inside the app
 * rather than by the proxy.
 *
 * ⚠ [Unavailable] is not the same as an empty [Regions]: only one of them is worth offering a retry
 * for, and only one of them should stop the screen claiming a fact it does not have.
 *
 * ⚠⚠ **Control made this distinction first, and kept more of it** — `OfflineMapsSnapshot` carries
 * `catalogUnavailable` *beside* the regions, because on an unreadable catalogue its resident
 * regions still list and still work. Maps cannot copy that: `installed()` yields region **ids**
 * only, and a [RegionItem] needs the name and byte size that live in the catalogue. So here an
 * unreadable catalogue really does mean there is nothing to draw, and the honest answer is to say
 * so rather than to render zero rows.
 */
sealed interface RegionsRead {

    /** The catalogue, joined with install state. May legitimately be empty. */
    data class Regions(val regions: List<RegionItem>) : RegionsRead

    /** The catalogue could not be read. [detail] is diagnostic, never user-facing copy. */
    data class Unavailable(val detail: String) : RegionsRead
}

interface RegionRepository {

    /** The catalog regions joined with local install state. */
    suspend fun regions(): RegionsRead

    /**
     * Downloads a region and returns whether it landed (installed). Backed by the proxy's
     * `downloadRegion` op — simulated in the dev in-process graph, a real fetch + sha256 gate
     * on-device. A `false` return (op failure) lets the picker surface a transient message.
     */
    suspend fun download(regionId: String): Boolean

    /** Evicts a downloaded region from the proxy's canonical store. */
    suspend fun delete(regionId: String)

    /**
     * Resolves the installed region **covering [location]** to an absolute `.pmtiles` path staged in
     * the app's own sandbox, ready to hand to the `:ui-map` basemap as `regionBundlePath` (the
     * render-hop terminus, `maps-region-delivery.md` §7). When several regions contain the point the
     * tightest box wins (D7); a null [location] (or a point outside every region) falls back to the
     * first installed region. Returns `null` when nothing is installed or the platform staging seam is
     * unavailable, so the basemap cleanly falls back to the bundled demo.
     */
    suspend fun renderableRegionPath(location: GeoPoint? = null): String? = null

    /**
     * A **streaming handle** for the region **covering [location]** (`maps-region-delivery.md` D10
     * streamed default): the render fetches only the covering tiles on demand (a sparse file) rather
     * than downloading the whole region. Same coverage rule as [renderableRegionPath] — tightest box
     * wins (D7) — but streaming does not require the region be installed. Preferred over
     * [renderableRegionPath]; null when no region is available (or the catalog is unreachable), so the
     * snippet falls back to the whole-region path then the demo.
     */
    suspend fun renderableStream(location: GeoPoint? = null): RegionStreamHandle? = null

    /**
     * The **offline offer** for [location] (`maps-region-delivery.md` §7.1 track 3d): the region covering
     * this place, its size, and whether it is already resident — backing the place detail's contextual
     * "keep this area offline" row. Null when nothing covers the place (or the catalog is unreachable), so
     * the row simply isn't shown.
     *
     * ⚠ The map already renders without this (it streams — D10). The row offers **availability without a
     * network**, not a working map, and must say so.
     */
    suspend fun offlineOffer(location: GeoPoint?): OfflineRegionOffer? = null

    /**
     * A **push stream** of [regionId]'s downloaded bytes so far, while a [download] runs (§7.1 download
     * UX). The proxy pushes a tick per streamed chunk (not a poll); since the download is streamed this is
     * the real count on disk, so a stalled fetch shows a stalled bar. Completes when the download finishes
     * — the caller reads success/failure off [download]'s return.
     */
    fun downloadProgress(regionId: String): Flow<Long> = emptyFlow()
}

/** The covering region a place can be kept offline from ([RegionRepository.offlineOffer]). */
data class OfflineRegionOffer(
    val regionId: String,
    val name: String,
    val byteSize: Long,
    val installed: Boolean,
)
