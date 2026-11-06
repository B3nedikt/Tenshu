package app.tenshu.basemap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app-side region-delivery terminus's pure resolution seam ([resolveBasemapSource]) — region
 * → bundled demo → schematic (docs/proposals/maps-region-delivery.md D5/D6). Each renderer's
 * existence probe + byte-staging + MapLibre load are framework code (not unit-testable), so the
 * load-bearing *order* — including the missing-region fall-through that keeps the surface from
 * blanking — is proven here in isolation, once, for every platform.
 *
 * Since AM-4b (`ios-apple-maps-option.md` §8.3) the order also answers a second question: not just
 * whether an archive *exists* but whether it **covers** what the hero is about to draw.
 */
class ResolveBasemapSourceTest {

    private val firenze = BasemapBounds(43.74, 11.19, 43.82, 11.32)
    private val inFirenze = BasemapBounds(43.76, 11.24, 43.78, 11.26)
    private val inLisbon = BasemapBounds(38.70, -9.20, 38.75, -9.13)

    @Test
    fun presentRegion_winsOverDemo() {
        val source = resolveBasemapSource(
            regionBundlePath = "/data/regions/firenze.pmtiles",
            regionBundleExists = true,
            bundledDemoAvailable = true,
            demoBounds = firenze,
            target = inFirenze,
        )
        assertEquals(BasemapSource.Region("/data/regions/firenze.pmtiles"), source)
    }

    @Test
    fun presentRegion_isNotCoverageChecked() {
        // ⚠ Deliberate, and documented on `resolveBasemapSource`: a region's own bbox never reaches
        // a renderer, which is handed a path. A user who downloaded a region did so *for* somewhere,
        // so the region wins even when the demo could not cover this target.
        val source = resolveBasemapSource(
            regionBundlePath = "/data/regions/lisboa.pmtiles",
            regionBundleExists = true,
            bundledDemoAvailable = true,
            demoBounds = firenze,
            target = inLisbon,
        )
        assertEquals(BasemapSource.Region("/data/regions/lisboa.pmtiles"), source)
    }

    @Test
    fun noRegion_fallsToBundledDemo() {
        val source = resolveBasemapSource(
            regionBundlePath = null,
            regionBundleExists = false,
            bundledDemoAvailable = true,
            demoBounds = firenze,
            target = inFirenze,
        )
        assertEquals(BasemapSource.BundledDemo, source)
    }

    @Test
    fun missingRegionFile_fallsThroughToDemo() {
        // A region was requested but its file isn't on disk yet (delivery in flight / GC'd) —
        // the surface must never blank, so we fall through rather than open a missing path.
        val source = resolveBasemapSource(
            regionBundlePath = "/data/regions/gone.pmtiles",
            regionBundleExists = false,
            bundledDemoAvailable = true,
            demoBounds = firenze,
            target = inFirenze,
        )
        assertEquals(BasemapSource.BundledDemo, source)
    }

    @Test
    fun missingRegionAndNoDemo_fallsToSchematic() {
        val source = resolveBasemapSource(
            regionBundlePath = "/data/regions/gone.pmtiles",
            regionBundleExists = false,
            bundledDemoAvailable = false,
            demoBounds = null,
            target = inFirenze,
        )
        assertEquals(BasemapSource.Schematic, source)
    }

    @Test
    fun nothingAvailable_fallsToSchematic() {
        val source = resolveBasemapSource(
            regionBundlePath = null,
            regionBundleExists = false,
            bundledDemoAvailable = false,
            demoBounds = null,
            target = null,
        )
        assertEquals(BasemapSource.Schematic, source)
    }

    // ── AM-4b: coverage ───────────────────────────────────────────────────────────────────────

    @Test
    fun demoThatCannotCoverTheTarget_fallsToSchematic() {
        // THE bug this phase exists for. Before AM-4b this returned BundledDemo and MapLibre drew a
        // real archive with no tiles for Lisbon — a route on an empty background, and no fallback.
        val source = resolveBasemapSource(
            regionBundlePath = null,
            regionBundleExists = false,
            bundledDemoAvailable = true,
            demoBounds = firenze,
            target = inLisbon,
        )
        assertEquals(BasemapSource.Schematic, source)
    }

    @Test
    fun partialOverlap_stillDrawsTheDemo() {
        // A route leaving the extract renders partly on tiles and partly on nothing. That is worse
        // than a full map and better than a sketch of a walk whose first half we *can* draw, so
        // intersection — not containment — is the rule.
        val straddling = BasemapBounds(43.80, 11.30, 43.90, 11.60)
        val source = resolveBasemapSource(
            regionBundlePath = null,
            regionBundleExists = false,
            bundledDemoAvailable = true,
            demoBounds = firenze,
            target = straddling,
        )
        assertEquals(BasemapSource.BundledDemo, source)
    }

    @Test
    fun unknownBounds_behaveExactlyAsBeforeTheCheck() {
        // Null means "unknown", never "does not cover": a renderer that cannot read the header must
        // not lose its map over it. Both directions.
        assertEquals(
            BasemapSource.BundledDemo,
            resolveBasemapSource(null, false, true, demoBounds = null, target = inLisbon),
        )
        assertEquals(
            BasemapSource.BundledDemo,
            resolveBasemapSource(null, false, true, demoBounds = firenze, target = null),
        )
    }

    @Test
    fun boundsOfRoute_isItsBoundingBox() {
        val bounds = boundsOf(
            listOf(
                BasemapCoord(43.77, 11.25),
                BasemapCoord(43.79, 11.21),
                BasemapCoord(43.75, 11.28),
            )
        )
        assertEquals(BasemapBounds(43.75, 11.21, 43.79, 11.28), bounds)
        assertNull(boundsOf(emptyList()))
    }

    @Test
    fun touchingEdgesCount_asIntersecting() {
        // A route that starts exactly on the extract's edge has tiles to draw on.
        assertTrue(firenze.intersects(BasemapBounds(43.70, 11.32, 43.74, 11.40)))
    }

    // ── AM-4b: the header reader ──────────────────────────────────────────────────────────────

    @Test
    fun aHeaderThatIsNotPmtiles_readsAsUnknown() {
        assertNull(basemapBoundsFromPmtilesHeader(ByteArray(127)))
        assertNull(basemapBoundsFromPmtilesHeader(ByteArray(12) { 'P'.code.toByte() }))
    }

    @Test
    fun anAllZeroBbox_readsAsUnknownRatherThanTheGulfOfGuinea() {
        // (0, 0) is a real place — the same trap `Place.hasCoordinate` guards on the app side. An
        // unset bbox must degrade to "unknown", which keeps the map, not to a rectangle that covers
        // nothing anyone will look at, which would lose it.
        val header = pmtilesHeader(minLonE7 = 0, minLatE7 = 0, maxLonE7 = 0, maxLatE7 = 0)
        assertNull(basemapBoundsFromPmtilesHeader(header))
    }

    @Test
    fun aNegativeBbox_survivesTheSignedRead() {
        // Lisbon is west of Greenwich, so its longitudes are negative — an unsigned read would place
        // this archive at +420°, which intersects nothing and would blank every hero in Portugal.
        val header = pmtilesHeader(
            minLonE7 = -92_000_000,
            minLatE7 = 387_000_000,
            maxLonE7 = -91_300_000,
            maxLatE7 = 387_500_000,
        )
        val bounds = basemapBoundsFromPmtilesHeader(header)
        assertEquals(BasemapBounds(38.7, -9.2, 38.75, -9.13), bounds)
    }

    private fun pmtilesHeader(
        minLonE7: Int,
        minLatE7: Int,
        maxLonE7: Int,
        maxLatE7: Int,
    ): ByteArray {
        val header = ByteArray(127)
        "PMTiles".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        header[7] = 3
        fun put(offset: Int, value: Int) {
            for (i in 0 until 4) header[offset + i] = ((value shr (8 * i)) and 0xff).toByte()
        }
        put(102, minLonE7)
        put(106, minLatE7)
        put(110, maxLonE7)
        put(114, maxLatE7)
        return header
    }
}
