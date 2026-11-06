package org.tenshu.mapregion.proxy

import app.tenshu.vectortile.GeoBounds
import app.tenshu.vectortile.TransitLineIndex
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.common.util.sha256
import org.tenshu.core.common.util.toHexLower

/**
 * The transit-line tier as a seam, mirroring [TransitStopTiles]: the op's job is to compose and the
 * *refusals* are the load-bearing part, so they have to be testable without a CDN.
 *
 * ⚠ Public for the same reason its siblings are — it is a parameter of the public
 * `mapRegionServiceProxyImplementationFactory`.
 */
fun interface TransitLineOverlays {

    /**
     * The lines serving **both** endpoints of a leg, or empty for "these overlays cannot say".
     *
     * ⚠ Empty is *ignorance*, never "no line". The caller must render the leg exactly as it does
     * without an overlay rather than claiming anything.
     */
    suspend fun linesServing(
        fromLatE7: Long, fromLngE7: Long,
        toLatE7: Long, toLngE7: Long,
        withinM: Double,
    ): Set<String>
}

/**
 * **L4 — the overlay tier** (`timeline-movement-capture.md` §33): resolves a leg's stop pair against
 * the per-region transit-line artefact the signed catalogue points at, so a metro leg can read
 * "U5" (§17.6).
 *
 * ### Why this is a whole-file fetch and not a range read
 *
 * The tile tiers range-read a PMTiles archive because it is hundreds of megabytes and only a few
 * kilobytes are wanted. An overlay is the opposite: **754 bytes** for a real four-line city extract,
 * measured in L2. Ranging into that would be more round trips than bytes, so it is fetched whole,
 * verified, decoded once and cached per region.
 *
 * ### ⚠⚠ The digest is checked, and that is the whole integrity story
 *
 * `catalog.json` is signed as bytes, so the `overlaySha256` inside it is authenticated by the same
 * chain that protects a tile bundle (§33.2). Verifying the fetched bytes against it is therefore
 * sufficient — and **necessary**, because unlike a streamed tile range this artefact is small enough
 * to check in full, so there is no reason to fall back to "TLS plus the signed catalogue named it".
 *
 * ⚠ A digest mismatch yields *nothing*, not a stale answer and not a thrown error: the leg reads as
 * it did before the overlay existed. That is the same posture as every other refusal here.
 *
 * ### ⚠ Every failure is silent-and-empty by design
 *
 * No fetcher wired, no region covering the point, a region with no overlay, a format version this
 * build does not understand, a network failure, bytes that do not decode — all answer empty. A line
 * label is an enrichment; making it able to fail a timeline read would be a much worse trade than
 * not showing it.
 */
internal class CatalogTransitLineOverlays(
    private val catalogSource: RegionCatalogSource,
    private val fetcher: RegionRangeFetcher?,
) : TransitLineOverlays {

    /**
     * `regionId -> decoded overlay`. A region's artefact is immutable for a build (the path carries
     * the build date), so this never needs invalidating within a process.
     *
     * ⚠ Negative results are cached too — as an empty overlay — so a region without one does not
     * re-fetch on every leg the feed renders.
     */
    private val decoded = mutableMapOf<String, TransitLineIndex.Overlay>()

    override suspend fun linesServing(
        fromLatE7: Long, fromLngE7: Long,
        toLatE7: Long, toLngE7: Long,
        withinM: Double,
    ): Set<String> {
        val fetch = fetcher ?: return emptySet()
        // ⚠ Resolved on the leg's START. Both ends must be in one region's overlay to intersect at
        // all, and a leg that crosses a region boundary is a mainline train, not a metro hop — the
        // case this tier exists for is by construction inside one city.
        val entry = resolveEntry(fromLatE7, fromLngE7) ?: return emptySet()
        val overlay = overlayFor(entry, fetch) ?: return emptySet()
        return overlay.linesServing(
            fromLatE7 = fromLatE7.toInt(), fromLngE7 = fromLngE7.toInt(),
            toLatE7 = toLatE7.toInt(), toLngE7 = toLngE7.toInt(),
            withinM = withinM,
        )
    }

    private suspend fun overlayFor(
        entry: RegionEntryData,
        fetch: RegionRangeFetcher,
    ): TransitLineIndex.Overlay? {
        decoded[entry.regionId]?.let { return it }
        val empty = TransitLineIndex.Overlay(emptyList())

        if (entry.overlayPath.isBlank() || entry.overlayByteSize <= 0) {
            decoded[entry.regionId] = empty // a region with no rail — remembered, not re-asked
            return null
        }
        // ⚠ A newer artefact than this build understands is refused rather than guessed at. The
        // decoder would refuse it anyway; failing here says *why* in one place.
        if (entry.overlayFormatVersion != TransitLineIndex.FORMAT_VERSION) {
            decoded[entry.regionId] = empty
            return null
        }

        val bytes = runCatchingCancellable {
            fetch.fetchRange(entry.overlayPath, 0, entry.overlayByteSize.toInt())
        }.getOrNull()
        // ⚠ A network failure is NOT cached: the next read should try again, unlike the structural
        // refusals above which cannot change until the catalogue does.
        if (bytes == null || bytes.size.toLong() != entry.overlayByteSize) return null

        if (sha256(bytes).toHexLower() != entry.overlaySha256.lowercase()) {
            // ⚠⚠ Not cached either, and deliberately: a corrupted CDN object or a truncated proxy
            // response is transient, while caching the refusal would make one bad fetch permanent
            // for the life of the process.
            return null
        }

        val parsed = TransitLineIndex.decode(bytes) ?: return null
        decoded[entry.regionId] = parsed
        return parsed
    }

    /**
     * The smallest catalogued region containing the point — the same smallest-area-wins rule
     * [TileTransitStopSource] and `RegionResolver` use, so all three agree about which bundle owns a
     * coordinate.
     */
    private suspend fun resolveEntry(latE7: Long, lngE7: Long): RegionEntryData? {
        val lat = latE7 / 1e7
        val lng = lngE7 / 1e7
        val point = GeoBounds(minLon = lng, minLat = lat, maxLon = lng, maxLat = lat)
        return runCatchingCancellable { catalogSource.load().regions }.getOrNull()
            ?.mapNotNull { entry -> parseBbox(entry.coverage)?.let { entry to it } }
            ?.filter { (_, box) -> box.contains(point) }
            ?.minByOrNull { (_, box) -> (box.maxLon - box.minLon) * (box.maxLat - box.minLat) }
            ?.first
    }

    private fun parseBbox(coverage: String): GeoBounds? {
        val p = coverage.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        if (p.size != 4) return null
        return GeoBounds(minLon = p[0], minLat = p[1], maxLon = p[2], maxLat = p[3])
    }

    private fun GeoBounds.contains(other: GeoBounds): Boolean =
        minLon <= other.minLon && maxLon >= other.maxLon &&
            minLat <= other.minLat && maxLat >= other.maxLat
}
