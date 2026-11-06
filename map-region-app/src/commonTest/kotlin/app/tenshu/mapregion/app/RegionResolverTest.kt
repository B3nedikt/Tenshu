package app.tenshu.mapregion.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure viewport→region resolver (`docs/proposals/maps-region-delivery.md` §7 phase-2). Proves
 * the render-seam contract without any service: containment, tightest-box preference, installed-vs-
 * catalog split, the null-point guard, and the coverage-bbox parse.
 */
class RegionResolverTest {

    // A Lisbon city box (installed) nested inside a Portugal country box (installed).
    private val lisbon = ResolvableRegion(
        regionId = "pt-lisboa",
        bounds = GeoBounds(minLat = 38.69, minLng = -9.20, maxLat = 38.75, maxLng = -9.10),
        bundlePath = "/data/regions/pt-lisboa.pmtiles",
    )
    private val portugal = ResolvableRegion(
        regionId = "pt",
        bounds = GeoBounds(minLat = 36.9, minLng = -9.6, maxLat = 42.2, maxLng = -6.1),
        bundlePath = "/data/regions/pt.pmtiles",
    )
    private val here = LatLng(latitude = 38.71, longitude = -9.14)

    @Test
    fun resolvesToTightestCoveringInstalledRegion() {
        // Both boxes contain the point; the smaller (city) wins over the country.
        assertEquals(lisbon.bundlePath, RegionResolver.resolveBundlePath(here, listOf(portugal, lisbon)))
        assertEquals("pt-lisboa", RegionResolver.coveringRegion(here, listOf(portugal, lisbon))?.regionId)
    }

    @Test
    fun uninstalledTightestRegion_stillCovers_butResolvesToInstalledFallback() {
        val lisbonNotDownloaded = lisbon.copy(bundlePath = null)
        // coveringRegion reports the tightest (for a "download {region}" affordance)…
        assertEquals("pt-lisboa", RegionResolver.coveringRegion(here, listOf(portugal, lisbonNotDownloaded))?.regionId)
        // …while resolveBundlePath skips it (no bytes) and falls through to the installed country.
        assertEquals(portugal.bundlePath, RegionResolver.resolveBundlePath(here, listOf(portugal, lisbonNotDownloaded)))
    }

    @Test
    fun noCoveringRegion_resolvesNull() {
        val elsewhere = LatLng(latitude = 48.85, longitude = 2.35) // Paris
        assertNull(RegionResolver.resolveBundlePath(elsewhere, listOf(portugal, lisbon)))
        assertNull(RegionResolver.coveringRegion(elsewhere, listOf(portugal, lisbon)))
    }

    @Test
    fun nullPoint_resolvesNull() {
        assertNull(RegionResolver.resolveBundlePath(null, listOf(portugal, lisbon)))
        assertNull(RegionResolver.coveringRegion(null, listOf(portugal, lisbon)))
    }

    @Test
    fun noneInstalled_resolvesNull_butStillCovers() {
        val catalogOnly = listOf(portugal.copy(bundlePath = null), lisbon.copy(bundlePath = null))
        assertNull(RegionResolver.resolveBundlePath(here, catalogOnly))
        assertEquals("pt-lisboa", RegionResolver.coveringRegion(here, catalogOnly)?.regionId)
    }

    @Test
    fun parseBbox_readsGeoJsonOrder_minLngMinLatMaxLngMaxLat() {
        val b = GeoBounds.parseBbox(" -9.20, 38.69, -9.10, 38.75 ")!!
        assertEquals(38.69, b.minLat, 1e-9)
        assertEquals(-9.20, b.minLng, 1e-9)
        assertEquals(38.75, b.maxLat, 1e-9)
        assertEquals(-9.10, b.maxLng, 1e-9)
        assertTrue(b.contains(here))
    }

    @Test
    fun parseBbox_rejectsMalformedInput() {
        assertNull(GeoBounds.parseBbox("not,a,bbox,x"))       // non-numeric
        assertNull(GeoBounds.parseBbox("1,2,3"))              // wrong arity
        assertNull(GeoBounds.parseBbox("-9.1,38.75,-9.2,38.69")) // inverted min/max
        assertNull(GeoBounds.parseBbox("{\"type\":\"Polygon\"}")) // polygon, out of scope
    }
}
