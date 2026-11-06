package app.tenshu.timeline.data.capture

import app.tenshu.vectortile.GeoBounds
import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.RailMatcher
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **policy** half of B4 (`timeline-movement-capture.md` §14): under what evidence a route may be
 * redrawn, and what survives the redraw. The geometry half is `:vector-tile`'s `RailSnapperTest`.
 *
 * Coordinates sit near 52°N where ~1e-5° of latitude ≈ 1.1 m, so offsets read as metres.
 */
class RailRouteSnapTest {

    private val baseLat = 52.0
    private val baseLon = 13.0

    /**
     * A **sawtooth** path of [n] fixes, one every 30 s, running north while alternating ~34 m
     * east/west.
     *
     * ⚠ The zig-zag is load-bearing for the timestamp tests: a *straight* path is collapsed to its
     * two endpoints by the Douglas–Peucker decimation the snapper reuses, which would leave nothing
     * to check timestamps on. A sawtooth at 34 m amplitude sits far above the 15 m tolerance, so
     * every point survives.
     */
    private fun sawtooth(n: Int, lonOffsetDeg: Double = 0.0): List<CapturedFix> =
        (0 until n).map { i ->
            CapturedFix(
                latE7 = ((baseLat + i * 0.0001) * 1e7).toLong(),
                lngE7 = ((baseLon + lonOffsetDeg + if (i % 2 == 0) 0.0 else 0.0005) * 1e7).toLong(),
                timestampMs = 1_800_000_000_000L + i * 30_000L,
                accuracyM = 8 + i,
            )
        }

    private fun lonLat(f: CapturedFix) = LonLat(lon = f.lngE7 / 1e7, lat = f.latE7 / 1e7)

    private fun railLine(kind: String, fixes: List<CapturedFix>): RailMatcher.RailLine {
        val points = fixes.map(::lonLat)
        return RailMatcher.RailLine(kind, points, GeoBounds.of(points)!!)
    }

    private fun surveyed(vararg lines: RailMatcher.RailLine) = RailEvidence.Surveyed(lines.toList())

    private fun transit(version: Int = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED) =
        ModeClassification(MovementMode.TRANSIT, confidence = 90, medianSpeedMmPerS = 25_000, version = version)

    private fun drive() =
        ModeClassification(MovementMode.DRIVE, confidence = 60, medianSpeedMmPerS = 20_000, version = MOVEMENT_CLASSIFIER_VERSION)

    // ── the happy path ───────────────────────────────────────────────────────

    @Test
    fun aCleanHeavyRailTraceSnaps() {
        val fixes = sawtooth(20)
        // The recorded path runs ~14 m east of the track it was actually on.
        val recorded = sawtooth(20, lonOffsetDeg = 0.0002)
        val snap = RailRouteSnap.snap(recorded, transit(), surveyed(railLine("rail", fixes)))

        assertNotNull(snap)
        assertEquals(1.0, snap.exclusiveCoverage)
        assertEquals(10_000L, snap.coverageBp)
        // Every drawn point now sits within a metre of the track — where before it was ~14 m off.
        val track = railLine("rail", fixes)
        assertTrue(
            metresFromTrack(recorded, track) > 10.0,
            "precondition: the input really was off track (${metresFromTrack(recorded, track)} m)",
        )
        assertTrue(
            metresFromTrack(snap.fixes, track) < 1.0,
            "still off track by ${metresFromTrack(snap.fixes, track)} m",
        )
    }

    /** The worst distance any of [fixes] sits from [track], in metres. */
    private fun metresFromTrack(fixes: List<CapturedFix>, track: RailMatcher.RailLine): Double =
        fixes.maxOf { f ->
            val p = lonLat(f)
            (0 until track.points.size - 1).minOf { i ->
                pointToSegmentM(p, track.points[i], track.points[i + 1])
            }
        }

    /** Point-to-segment distance in metres (the `:vector-tile` helper is internal to that module). */
    private fun pointToSegmentM(p: LonLat, a: LonLat, b: LonLat): Double {
        val perLon = 111_320.0 * kotlin.math.cos(p.lat * kotlin.math.PI / 180.0)
        val bx = (b.lon - a.lon) * perLon; val by = (b.lat - a.lat) * 111_320.0
        val px = (p.lon - a.lon) * perLon; val py = (p.lat - a.lat) * 111_320.0
        val lenSq = bx * bx + by * by
        val t = if (lenSq == 0.0) 0.0 else ((px * bx + py * by) / lenSq).coerceIn(0.0, 1.0)
        val dx = px - t * bx; val dy = py - t * by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // ── ⚠⚠ timestamps survive ────────────────────────────────────────────────

    @Test
    fun everySourceFixKeepsItsOwnTimestampAndAccuracy() {
        // Path == track, so every fix lands on itself and nothing is interpolated: the strongest form
        // of the claim, since any timestamp loss shows up as an exact inequality.
        val fixes = sawtooth(20)
        val snap = RailRouteSnap.snap(fixes, transit(), surveyed(railLine("rail", fixes)))

        assertNotNull(snap)
        assertEquals(fixes.map { it.timestampMs }, snap.fixes.map { it.timestampMs })
        assertEquals(fixes.map { it.accuracyM }, snap.fixes.map { it.accuracyM })
    }

    @Test
    fun aWalkedTrackVertexGetsAnInterpolatedTimestampInsideItsBracket() {
        // A tunnel: 21 track vertices, two surviving fixes 10 minutes apart. The 19 recovered vertices
        // must be timestamped *between* those two, in order — not zeroed, not copied.
        val track = sawtooth(21)
        val recorded = listOf(track.first(), track.last())
        val snap = RailRouteSnap.snap(recorded, transit(), surveyed(railLine("rail", track)))

        assertNotNull(snap)
        assertTrue(snap.fixes.size > 2, "the track was not walked: ${snap.fixes.size} points")
        assertEquals(recorded.first().timestampMs, snap.fixes.first().timestampMs)
        assertEquals(recorded.last().timestampMs, snap.fixes.last().timestampMs)
        val stamps = snap.fixes.map { it.timestampMs }
        assertEquals(stamps, stamps.sorted(), "timestamps must advance along the drawn route")
        assertTrue(
            stamps.all { it in recorded.first().timestampMs..recorded.last().timestampMs },
            "a timestamp escaped the journey's own span: $stamps",
        )
        // Genuinely interpolated, not just the two endpoints repeated.
        assertTrue(stamps.distinct().size > 2, "every recovered vertex got the same timestamp")
    }

    // ── ⚠⚠ the negatives ─────────────────────────────────────────────────────

    @Test
    fun aStreetRunningTramTraceDoesNotSnap() {
        val track = sawtooth(20)
        val recorded = sawtooth(20, lonOffsetDeg = 0.0002)
        // Precondition: this exact fixture snaps when the track is heavy rail, so a null below is the
        // street-running rule firing rather than the fixture never reaching the snapper.
        assertNotNull(
            RailRouteSnap.snap(recorded, transit(), surveyed(railLine("rail", track))),
            "precondition: the fixture must snap as heavy rail",
        )

        for (kind in listOf("tram", "light_rail")) {
            assertNull(
                RailRouteSnap.snap(recorded, transit(), surveyed(railLine(kind, track))),
                "a $kind ride must never be redrawn — a car on that street matches it too",
            )
        }
    }

    @Test
    fun unavailableEvidenceProducesNoSnappedPath() {
        val fixes = sawtooth(20)
        // Precondition: the very same fixes DO snap once the survey succeeds — so `null` here is the
        // Unavailable rule, not an unsnappable path.
        assertNotNull(RailRouteSnap.snap(fixes, transit(), surveyed(railLine("rail", fixes))))

        assertNull(RailRouteSnap.snap(fixes, transit(), RailEvidence.Unavailable))
    }

    @Test
    fun aSurveyThatFoundNoTrackIsNotTheSameAsAnUnavailableOne() {
        // Both answer "no snap", but for different reasons — and only `Surveyed` is evidence. Pinning
        // both keeps the distinction from being collapsed into one branch later.
        val fixes = sawtooth(20)
        assertNull(RailRouteSnap.snap(fixes, transit(), RailEvidence.Surveyed(emptyList())))
        assertNull(RailRouteSnap.snap(fixes, transit(), RailEvidence.Unavailable))
    }

    @Test
    fun aDriveIsNeverRedrawnEvenWhenItRunsAlongTrack() {
        val fixes = sawtooth(20)
        // Same geometry, same evidence — only the verdict differs.
        assertNotNull(RailRouteSnap.snap(fixes, transit(), surveyed(railLine("rail", fixes))))
        assertNull(RailRouteSnap.snap(fixes, drive(), surveyed(railLine("rail", fixes))))
    }

    @Test
    fun coverageBelowTheBarDoesNotSnap() {
        // The track spans the whole journey; what varies is how much of the *path* strays ~1.4 km off
        // it. So the bar is what decides, not where the geometry happens to end.
        val track = railLine("rail", sawtooth(20))

        val mostlyOff = strayAfter(8)   // 8 of 20 on track = 0.40
        assertNull(
            RailRouteSnap.snap(mostlyOff, transit(), surveyed(track)),
            "0.40 exclusive coverage must not be redrawn",
        )

        val mostlyOn = strayAfter(12)   // 12 of 20 = 0.60
        val snap = RailRouteSnap.snap(mostlyOn, transit(), surveyed(track))
        assertNotNull(snap, "0.60 exclusive coverage must be redrawn")
        assertTrue(abs(snap.exclusiveCoverage - 0.6) < 0.01, "coverage was ${snap.exclusiveCoverage}")
    }

    /** 20 fixes: the first [onTrack] on the sawtooth, the rest ~1.4 km east of it. */
    private fun strayAfter(onTrack: Int): List<CapturedFix> =
        sawtooth(20).mapIndexed { i, f ->
            if (i < onTrack) f else f.copy(lngE7 = f.lngE7 + (0.02 * 1e7).toLong())
        }

    @Test
    fun aOnePointPathIsNotSnappable() {
        val fixes = sawtooth(1)
        assertNull(RailRouteSnap.snap(fixes, transit(), surveyed(railLine("rail", sawtooth(20)))))
    }
}
