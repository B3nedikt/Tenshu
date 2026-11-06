package app.tenshu.timeline.data.capture

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accuracy-weighted dwell centroid (Phase 2): a precise fix pulls the location toward it; with no
 * accuracy data the result is exactly the plain mean (so accuracy-free fixtures stay unchanged).
 */
class CentroidTest {

    @Test
    fun anAccurateFixPullsTheCentroidTowardIt() {
        val coarse = CapturedFix(latE7 = 500_000_000L, lngE7 = 100_000_000L, timestampMs = 0L, accuracyM = 100)
        val precise = CapturedFix(latE7 = 500_100_000L, lngE7 = 100_100_000L, timestampMs = 60_000L, accuracyM = 5)

        val v = listOf(coarse, precise).toCentroidVisit()

        // Weight 1/5² ≫ 1/100², so the centroid lands next to the precise fix, not the plain midpoint.
        assertTrue(abs(v.latE7 - precise.latE7) < abs(v.latE7 - coarse.latE7))
        assertTrue(abs(v.lngE7 - precise.lngE7) < abs(v.lngE7 - coarse.lngE7))
    }

    @Test
    fun noAccuracyDataIsExactlyThePlainMean() {
        val a = CapturedFix(latE7 = 500_000_000L, lngE7 = 100_000_000L, timestampMs = 0L) // accuracyM = 0
        val b = CapturedFix(latE7 = 500_002_000L, lngE7 = 100_002_000L, timestampMs = 60_000L)

        val v = listOf(a, b).toCentroidVisit()

        assertEquals(500_001_000L, v.latE7)
        assertEquals(100_001_000L, v.lngE7)
    }

    @Test
    fun representativeAccuracyIsTheMedianOfReportedFixes() {
        // 4-B: the dwell carries the median of the *reported* per-fix accuracies (typical GPS
        // quality during the stay) — one good fix at the edge doesn't make the whole dwell "precise".
        val fixes = listOf(
            CapturedFix(500_000_000L, 100_000_000L, 0L, accuracyM = 10),
            CapturedFix(500_000_100L, 100_000_100L, 60_000L, accuracyM = 120),
            CapturedFix(500_000_200L, 100_000_200L, 120_000L, accuracyM = 0), // unknown → ignored
            CapturedFix(500_000_300L, 100_000_300L, 180_000L, accuracyM = 150),
        )
        // Known accuracies sorted = [10, 120, 150]; median (index 1) = 120.
        assertEquals(120, fixes.toCentroidVisit().accuracyM)
    }

    @Test
    fun representativeAccuracyIsZeroWhenNoFixReportsIt() {
        val fixes = listOf(
            CapturedFix(500_000_000L, 100_000_000L, 0L),
            CapturedFix(500_000_100L, 100_000_100L, 60_000L),
        )
        assertEquals(0, fixes.toCentroidVisit().accuracyM)
    }

    // ── §32.10: a place's accuracy is the worse of fix quality and cluster spread ──────────────

    @Test
    fun aStayTheUserWalkedThroughDoesNotClaimTheFixesPrecision() {
        // ⚠⚠ The reported defect. A cluster of ±14 m fixes spread along the anchor radius was stamped
        // `accuracyM = 14`, which sent it down PlaceLabelEnricher's *precise* branch and got it named
        // after a bar. Measured on the real stay: the centroid sits 165 m from the first fix of the
        // walk that begins one second later.
        val walkedThrough = (0 until 15).map { fix(northM = it * 11.0, sec = it * 60, acc = 14) }
        val visit = walkedThrough.toCentroidVisit()

        assertTrue(
            visit.accuracyM > 60,
            "a stay spanning ~150 m cannot claim ±14 m, was ${visit.accuracyM}",
        )
    }

    @Test
    fun aStayThatReallyIsAPointKeepsItsPrecision() {
        // ⚠⚠ The twin, and the one that makes the case above evidence: identical fix quality, tight
        // cluster. If this ever coarsens, the rule has stopped measuring spread and started
        // measuring accuracy twice — and every well-fixed café loses its venue name.
        val stayedPut = (0 until 15).map { fix(northM = (it % 3) * 8.0, sec = it * 60, acc = 14) }
        assertEquals(14, stayedPut.toCentroidVisit().accuracyM)
    }

    @Test
    fun anIndoorJitterCloudIsNotCoarsenedTwice() {
        // ⚠ `max`, not a sum: a ±100 m indoor cloud's spread *is* its fix error. Summing would
        // coarsen every ordinary indoor dwell, which is the regression this pins against.
        val indoors = (0 until 15).map { fix(northM = (it % 4) * 40.0, sec = it * 60, acc = 100) }
        assertEquals(100, indoors.toCentroidVisit().accuracyM)
    }

    @Test
    fun aSingleFixHasNoSpreadToSpeakOf() {
        assertEquals(14, listOf(fix(northM = 0.0, sec = 0, acc = 14)).toCentroidVisit().accuracyM)
    }

    /** A fix [northM] metres north of the reference point, reporting [acc] metres. */
    private fun fix(northM: Double, sec: Int, acc: Int) = CapturedFix(
        latE7 = 481_408_823L + (northM / 0.011132).roundToLong(),
        lngE7 = 115_760_761L,
        timestampMs = 1_800_000_000_000L + sec * 1000L,
        accuracyM = acc,
    )
}
