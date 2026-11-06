package app.tenshu.basemap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure camera framing behind every route map (`maps-region-delivery.md` R6): the geographic
 * bounding box → centre + zoom. The MapLibre line-layer render is visual (verified on device), but
 * the framing math — centre = midpoint, zoom fits the larger span, clamped — is the load-bearing
 * contract, so it is unit-tested here (mirroring [NormalizeRouteTest] for the schematic sketch).
 */
class RouteCameraTest {

    @Test
    fun centreIsBoundingBoxMidpoint() {
        val frame = routeCameraFrame(
            listOf(
                BasemapCoord(latitude = 10.0, longitude = 20.0),
                BasemapCoord(latitude = 30.0, longitude = 40.0),
            ),
        )
        assertEquals(20.0, frame.centerLatitude, 1e-9)
        assertEquals(30.0, frame.centerLongitude, 1e-9)
    }

    @Test
    fun centreIgnoresPointOrderingAndUsesExtremes() {
        // Midpoint is of the bounding box, not the visiting order.
        val frame = routeCameraFrame(
            listOf(
                BasemapCoord(latitude = 30.0, longitude = 40.0),
                BasemapCoord(latitude = 10.0, longitude = 20.0),
                BasemapCoord(latitude = 25.0, longitude = 35.0),
            ),
        )
        assertEquals(20.0, frame.centerLatitude, 1e-9)
        assertEquals(30.0, frame.centerLongitude, 1e-9)
    }

    @Test
    fun largerSpanGivesSmallerZoom() {
        val tight = routeCameraFrame(
            listOf(BasemapCoord(0.0, 0.0), BasemapCoord(0.5, 0.5)),
        )
        val wide = routeCameraFrame(
            listOf(BasemapCoord(0.0, 0.0), BasemapCoord(20.0, 20.0)),
        )
        assertTrue(wide.zoom < tight.zoom, "wider route must zoom out further (${wide.zoom} < ${tight.zoom})")
    }

    @Test
    fun hemisphereSpanClampsToMinZoom() {
        val frame = routeCameraFrame(
            listOf(
                BasemapCoord(latitude = -80.0, longitude = -170.0),
                BasemapCoord(latitude = 80.0, longitude = 170.0),
            ),
        )
        assertEquals(3.0, frame.zoom, 1e-9)
    }

    @Test
    fun nearCoincidentPointsClampToMaxZoom() {
        val frame = routeCameraFrame(
            listOf(
                BasemapCoord(latitude = 43.7696, longitude = 11.2558),
                BasemapCoord(latitude = 43.76961, longitude = 11.25581),
            ),
        )
        assertEquals(15.0, frame.zoom, 1e-9)
    }

    @Test
    fun degenerateSpanUsesMaxZoom() {
        // A single point (or all points identical) has no span to fit → max zoom, no divide-by-zero.
        val single = routeCameraFrame(listOf(BasemapCoord(latitude = 5.0, longitude = 9.0)))
        assertEquals(15.0, single.zoom, 1e-9)
        assertEquals(5.0, single.centerLatitude, 1e-9)
        assertEquals(9.0, single.centerLongitude, 1e-9)
    }

    @Test
    fun zoomStaysWithinBounds() {
        val frame = routeCameraFrame(
            listOf(BasemapCoord(0.0, 0.0), BasemapCoord(3.0, 3.0)),
        )
        assertTrue(frame.zoom in 3.0..15.0, "zoom out of range: ${frame.zoom}")
    }

    @Test
    fun aShorterSurfaceZoomsFurtherOutForALatitudeDominantRoute() {
        // The heroes this backs are wide, short strips. Less vertical room means a
        // latitude-dominant route has to sit further out.
        val route = listOf(BasemapCoord(48.10, 11.55), BasemapCoord(48.30, 11.57))
        val square = routeCameraFrame(route, viewportWidthDp = 411.0, viewportHeightDp = 411.0)
        val strip = routeCameraFrame(route, viewportWidthDp = 411.0, viewportHeightDp = 200.0)
        assertTrue(
            strip.zoom < square.zoom,
            "shorter surface must zoom out further (${strip.zoom} !< ${square.zoom})",
        )
    }

    @Test
    fun aNarrowerSurfaceZoomsFurtherOutForALongitudeDominantRoute() {
        // The half the first fix missed entirely: when the horizontal axis binds, the surface's
        // WIDTH is what the route has to fit into. Framing an east-west trip identically on a
        // 411dp phone and a 512dp reference viewport is exactly how it ran off both edges.
        val route = listOf(BasemapCoord(48.10, 11.00), BasemapCoord(48.11, 12.00))
        val reference = routeCameraFrame(route, viewportWidthDp = 512.0, viewportHeightDp = 200.0)
        val phone = routeCameraFrame(route, viewportWidthDp = 411.0, viewportHeightDp = 200.0)
        assertTrue(
            phone.zoom < reference.zoom,
            "narrower surface must zoom out further (${phone.zoom} !< ${reference.zoom})",
        )
    }

    @Test
    fun latitudeSpanCostsMoreZoomAwayFromTheEquator() {
        // The Mercator correction. The same span of degrees covers ~1.5x more screen at Munich's
        // latitude than at the equator, so it must frame further out — a naive "a degree is a
        // degree" fit gives these two identical zoom and clips the northern route.
        val equatorial = routeCameraFrame(
            listOf(BasemapCoord(0.0, 11.0), BasemapCoord(1.0, 11.0)),
        )
        val northern = routeCameraFrame(
            listOf(BasemapCoord(48.0, 11.0), BasemapCoord(49.0, 11.0)),
        )
        assertTrue(
            northern.zoom < equatorial.zoom,
            "a high-latitude span must zoom out further (${northern.zoom} !< ${equatorial.zoom})",
        )
    }

    @Test
    fun everyRouteShapeFitsTheHeroItIsDrawnIn() {
        // The property that actually matters, asserted end-to-end on a real phone-sized hero rather
        // than through the internals: at the returned zoom the route must fit BOTH axes, with the
        // margin still to spare. Checked across shapes because each earlier version of this function
        // fitted one shape and overflowed another — north-south, then east-west.
        val widthDp = 411.0
        val heightDp = 200.0
        val shapes = mapOf(
            "north-south" to listOf(BasemapCoord(48.05, 11.55), BasemapCoord(48.45, 11.60)),
            "east-west" to listOf(BasemapCoord(48.10, 11.00), BasemapCoord(48.13, 12.00)),
            "diagonal" to listOf(BasemapCoord(48.05, 11.20), BasemapCoord(48.40, 11.90)),
            "tiny" to listOf(BasemapCoord(48.1370, 11.5750), BasemapCoord(48.1390, 11.5790)),
        )

        for ((name, route) in shapes) {
            val frame = routeCameraFrame(route, viewportWidthDp = widthDp, viewportHeightDp = heightDp)
            val scale = pow2(frame.zoom)
            val visibleHorizontal = (widthDp / 512.0) / scale
            val visibleVertical = (heightDp / 512.0) / scale

            val routeHorizontal = (route.maxOf { it.longitude } - route.minOf { it.longitude }) / 360.0
            val routeVertical =
                mercatorSpan(route.minOf { it.latitude }, route.maxOf { it.latitude })

            // maxZoom clamps the "tiny" case, where fitting is trivially satisfied anyway.
            if (frame.zoom >= 15.0) continue
            assertTrue(
                routeHorizontal <= visibleHorizontal,
                "$name: width $routeHorizontal must fit visible $visibleHorizontal",
            )
            assertTrue(
                routeVertical <= visibleVertical,
                "$name: height $routeVertical must fit visible $visibleVertical",
            )
        }
    }

    @Test
    fun theFittedRouteKeepsItsMarginOnAtLeastOneAxis() {
        // Fitting alone would be satisfied by a route touching the edges exactly. The complaint that
        // prompted this was "the trip is drawn at the ends of the map" — so pin the breathing room:
        // the binding axis must leave the configured padding, not merely avoid clipping.
        val widthDp = 411.0
        val heightDp = 200.0
        val route = listOf(BasemapCoord(48.10, 11.00), BasemapCoord(48.13, 12.00))
        val frame = routeCameraFrame(route, viewportWidthDp = widthDp, viewportHeightDp = heightDp)

        val scale = pow2(frame.zoom)
        val visibleHorizontal = (widthDp / 512.0) / scale
        val routeHorizontal = (1.00) / 360.0

        // 15% padding each side ⇒ the route occupies at most 1/1.3 of the visible span.
        assertTrue(
            routeHorizontal <= visibleHorizontal / 1.29,
            "route $routeHorizontal must leave margin inside $visibleHorizontal",
        )
    }

    private fun pow2(exponent: Double): Double = exp(exponent * ln(2.0))

    /** Independent restatement of the Mercator extent, so the test does not reuse the impl's helper. */
    private fun mercatorSpan(fromLatitude: Double, toLatitude: Double): Double {
        fun y(latitude: Double): Double {
            val radians = latitude * PI / 180.0
            return ln(tan(PI / 4.0 + radians / 2.0)) / (2.0 * PI)
        }
        return abs(y(toLatitude) - y(fromLatitude))
    }
}

/**
 * The pure route fitting behind the schematic sketches (`:ui-map`'s `RoutePolyline`,
 * `timeline-ios`'s `RouteSketch`): a lat/lng route fitted to the unit square, x = longitude
 * (west→0), y = latitude inverted (north→0). The drawing is visual; the normalization is tested.
 */
class NormalizeRouteTest {

    @Test
    fun mapsCornersNorthUp() {
        // SW corner (min lng, min lat) → (0, 1); NE corner (max lng, max lat) → (1, 0).
        val route = listOf(
            BasemapCoord(latitude = 10.0, longitude = 20.0), // SW
            BasemapCoord(latitude = 30.0, longitude = 40.0), // NE
        )
        val n = normalizeRoute(route)
        assertEquals(0.0 to 1.0, n.first())
        assertEquals(1.0 to 0.0, n.last())
    }

    @Test
    fun midpointIsCentred() {
        val route = listOf(
            BasemapCoord(0.0, 0.0),
            BasemapCoord(10.0, 10.0),
            BasemapCoord(20.0, 20.0),
        )
        assertEquals(0.5 to 0.5, normalizeRoute(route)[1])
    }

    @Test
    fun degenerateSpanCollapsesToCentreLine() {
        // All points share a latitude → y collapses to 0.5 (no divide-by-zero), x still spreads.
        val route = listOf(
            BasemapCoord(latitude = 5.0, longitude = 0.0),
            BasemapCoord(latitude = 5.0, longitude = 10.0),
        )
        val n = normalizeRoute(route)
        assertEquals(0.0 to 0.5, n.first())
        assertEquals(1.0 to 0.5, n.last())
    }

    @Test
    fun emptyRouteIsEmpty() {
        assertTrue(normalizeRoute(emptyList()).isEmpty())
    }

    @Test
    fun theExportedMaxZoomIsWhatADegenerateRouteClampsTo() {
        // ⚠⚠ This pins a LINK, not a number. Since 2026-08-14 the iOS MapKit hero reads
        // `BASEMAP_MAX_ZOOM` directly (`ios-basemap/Sources/MapKitHeroes.swift`) to clamp its own
        // fit, because it cannot call this function — it frames with `MKMapRect`. If the clamp here
        // stops being that constant, the two renderers silently disagree about how tight a hero may
        // frame, and only one of them is wrong on screen.
        //
        // That is not hypothetical: a workout recorded standing still (7 fixes within 4 cm) framed
        // its own bounding box on MapKit and rendered a 10 m window of Apple's basemap — bare land,
        // no roads, no labels — while MapLibre drew the same workout correctly, because of this
        // clamp.
        val stationary = routeCameraFrame(
            List(7) { BasemapCoord(latitude = 48.1374, longitude = 11.5755) }
        )
        assertEquals(BASEMAP_MAX_ZOOM, stationary.zoom, 1e-9)
    }

}
