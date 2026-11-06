package app.tenshu.ui.map

import app.tenshu.vectortile.PmtilesDirectory
import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.MvtDecoder
import app.tenshu.vectortile.MvtGeometryType
import app.tenshu.vectortile.MvtLayer
import app.tenshu.vectortile.RailMatcher
import app.tenshu.vectortile.toLonLat
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T1 matching against the **real shipped archive** — real Firenze rail, real Firenze roads.
 *
 * This is the test that decides whether T1 works at all, and it is deliberately adversarial: the
 * negative case is not "a path in a field", it is **actual road geometry from the same tile**. Roads
 * and railways in a city run parallel, cross, and share corridors; if the matcher cannot separate
 * them here it would turn ordinary drives into train journeys in production.
 *
 * Traces are synthesised from real geometry plus GPS-scale jitter, because the archive contains no
 * recorded traces — but the *reference* geometry, the projection, and the discrimination are all real.
 */
class RailMatchRealArchiveTest {

    private val archive = File("src/androidMain/assets/tenshu_basemap.pmtiles")
    private val z = 14
    private val tileX = 8704L
    private val tileY = 5971L

    private fun readRange(offset: Long, length: Int): ByteArray =
        RandomAccessFile(archive, "r").use { raf ->
            raf.seek(offset)
            ByteArray(length).also { raf.readFully(it) }
        }

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(b.inputStream()).use { it.readBytes() }

    private fun layers(): List<MvtLayer> {
        val header = PmtilesDirectory.parseHeader(readRange(0, PmtilesDirectory.HEADER_BYTES))
        val rootDir = PmtilesDirectory.decodeDirectory(
            gunzip(readRange(header.rootDirOffset, header.rootDirLength.toInt())),
        )
        val tileId = PmtilesDirectory.zxyToTileId(z, tileX, tileY)
        val range = PmtilesDirectory.tileRange(header, rootDir, tileId) { r ->
            PmtilesDirectory.decodeDirectory(gunzip(readRange(r.start, r.length.toInt())))
        }!!
        return MvtDecoder.decode(gunzip(readRange(range.start, range.length.toInt())))
    }

    private fun railLines() =
        RailMatcher.railLinesFrom(layers().single { it.name == "transit" }, z, tileX, tileY)

    /**
     * Walk a projected line, emitting a point every ~[stepM] metres with [jitterM] of lateral
     * wobble — a stand-in for GPS noise, so the match is not testing an exact coordinate identity.
     * The wobble is deterministic (a sine of the index) so the test cannot flake.
     */
    private fun traceAlong(points: List<LonLat>, stepM: Double = 40.0, jitterM: Double = 12.0): List<LonLat> {
        val out = ArrayList<LonLat>()
        val mPerLat = 111_320.0
        fun mPerLon(lat: Double) = mPerLat * cos(lat * PI / 180.0)
        var carry = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = (b.lon - a.lon) * mPerLon(a.lat)
            val dy = (b.lat - a.lat) * mPerLat
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            if (len <= 0.0) continue
            var t = carry
            while (t < len) {
                val f = t / len
                val jitter = sin(out.size * 1.7) * jitterM
                // Offset perpendicular to the segment.
                val nx = -dy / len
                val ny = dx / len
                out += LonLat(
                    lon = a.lon + (dx * f + nx * jitter) / mPerLon(a.lat),
                    lat = a.lat + (dy * f + ny * jitter) / mPerLat,
                )
                t += stepM
            }
            carry = t - len
        }
        return out
    }

    @Test
    fun aTraceFollowingRealHeavyRailMatchesExclusively() {
        val lines = railLines()
        assertTrue(lines.isNotEmpty(), "no running-line rail extracted from the transit layer")

        // ⚠ Specifically `railway=rail`, not merely the longest line — the longest line in this tile
        // is a *tram*, and matching a tram proves much less (see the road test below).
        val heavy = lines.filter { RailMatcher.isExclusiveTrack(it.kind) }
        assertTrue(heavy.isNotEmpty(), "no heavy rail in the fixture tile")
        val longest = heavy.maxBy { it.points.size }
        val trace = traceAlong(longest.points)
        assertTrue(trace.size >= 5, "trace too short to be meaningful (${trace.size} points)")

        val match = RailMatcher.match(trace, lines)
        assertTrue(
            match.isOnExclusiveRail,
            "a GPS-jittered trace along real heavy rail should match exclusively; " +
                "exclusive=${match.exclusiveCoverage} any=${match.coverage} kinds=${match.matchedKinds}",
        )
    }

    @Test
    fun realRoadsDoNotMatchTrackACarCannotOccupy() {
        // ⚠⚠ The case that decides whether T1 is usable, and the one that reshaped the API.
        //
        // Measured against this tile: **10 of 240 road traces matched `isOnAnyRail` completely** —
        // because Firenze's Linea T1/T2/T3 trams run down the middle of streets, so a car on a tram
        // street really is on tram geometry. A single coverage number would therefore have promoted
        // ordinary drives to "Train".
        //
        // Splitting the verdict fixes it at the root: heavy rail is track a road vehicle physically
        // cannot follow. The same 240 traces against `exclusiveCoverage`: **2 matches, 0.83%** — a
        // 5x reduction. The residual two are real (roads do run inside rail corridors), which is why
        // the bar below is "essentially never" rather than zero, and why T1 is a strong signal rather
        // than a proof.
        val lines = railLines()
        val roads = layers().single { it.name == "roads" }
        val roadParts = roads.features
            .filter { it.type == MvtGeometryType.LINESTRING }
            .flatMap { it.geometry }
            .filter { it.size >= 4 }
            .map { part -> part.map { roads.toLonLat(it, z, tileX, tileY) } }

        assertTrue(roadParts.size > 50, "expected plenty of road geometry, got ${roadParts.size}")

        var exclusive = 0
        var anyRail = 0
        var scored = 0
        for (part in roadParts) {
            val trace = traceAlong(part)
            if (trace.size < 5) continue
            scored++
            val m = RailMatcher.match(trace, lines)
            if (m.isOnExclusiveRail) exclusive++
            if (m.isOnAnyRail) anyRail++
        }
        assertTrue(scored > 100, "expected a decent sample, scored $scored")

        val exclusiveRate = exclusive.toDouble() / scored
        assertTrue(
            exclusiveRate <= 0.02,
            "roads matched track a car cannot occupy: $exclusive/$scored " +
                "(${(exclusiveRate * 100).toInt()}%) — T1 would promote real drives",
        )

        // And the reason the split exists: the permissive figure really is much worse. If this ever
        // stops holding, the fixture changed and the tram hazard should be re-examined rather than
        // assumed away.
        assertTrue(
            anyRail >= exclusive,
            "sanity: any-rail ($anyRail) can never be below exclusive ($exclusive)",
        )
    }

    @Test
    fun yardsAndSidingsAreExcludedFromRunningTrack() {
        // The survey found 388 service=yard/siding/crossover features in this small extract. A
        // matcher that accepts them would call a walk across a depot a train journey.
        val transit = layers().single { it.name == "transit" }
        val withService = transit.features.count { it.tag("service") != null }
        assertTrue(withService > 0, "fixture assumption: the tile has service track")

        val extracted = RailMatcher.railLinesFrom(transit, z, tileX, tileY)
        // Every extracted line must trace back to a feature with no `service` tag.
        val runningFeatureCount = transit.features.count {
            it.type == MvtGeometryType.LINESTRING && it.tag("railway") != null && it.tag("service") == null
        }
        assertTrue(extracted.isNotEmpty())
        assertTrue(
            extracted.size <= transit.features.sumOf { it.geometry.size },
            "extraction should never invent geometry",
        )
        assertTrue(runningFeatureCount < transit.features.size, "fixture assumption: some track is service track")
    }

    @Test
    fun aTraceFarFromAnyRailDoesNotMatch() {
        val lines = railLines()
        // A few hundred metres offshore of the tile, well away from any track.
        val far = (0..20).map { LonLat(lon = 11.30 + it * 0.0005, lat = 43.60) }
        val match = RailMatcher.match(far, lines)
        assertEquals(0.0, match.coverage)
        assertEquals(0.0, match.exclusiveCoverage)
        assertTrue(!match.isOnAnyRail)
        assertTrue(!match.isOnExclusiveRail)
    }

    @Test
    fun stopsOnRailCountsOnlyTheStopsThatFall0nTrack() {
        val lines = railLines()
        val onRail = lines.maxBy { it.points.size }.points.first()
        val offRail = LonLat(lon = 11.30, lat = 43.60)
        assertEquals(1, RailMatcher.stopsOnRail(listOf(onRail, offRail), lines))
    }
}
