package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FA-4: the step total `MovementMapper` persists on the trace (`MovementTrace.steps`).
 *
 * ⚠ `stepsSinceLastFix` is the interval *ending* at a fix, so a move owns `fixes[1..]` only. The
 * boundary test is the one that matters: [ModeSplitter] hands neighbouring moves a shared fix, and
 * counting it on both sides inflates every split journey.
 */
class MovementMapperStepsTest {

    private val t0 = 1_800_000_000_000L

    private fun fix(sec: Int, steps: Int?) = CapturedFix(
        latE7 = 525_000_000L + sec * 100L,
        lngE7 = 134_000_000L,
        timestampMs = t0 + sec * 1000L,
        accuracyM = 10,
        stepsSinceLastFix = steps,
    )

    private fun move(fixes: List<CapturedFix>) = CapturedMove(
        fixes = fixes,
        startMs = fixes.first().timestampMs,
        endMs = fixes.last().timestampMs,
        distanceM = 100.0,
        classification = ModeClassification(mode = MovementMode.WALK, confidence = 80, medianSpeedMmPerS = 1_400),
    )

    private fun stepsOf(vararg moves: CapturedMove): List<Long> =
        MovementMapper.capturedMovesToTimeline(moves.toList()).traces.map { it.steps }

    @Test
    fun theTraceCarriesTheSumOfItsFixesSteps() {
        val walk = move(listOf(fix(0, 7), fix(10, 18), fix(20, 20), fix(30, 19)))
        // The first fix's 7 belong to the interval *before* the move started.
        assertEquals(listOf(57L), stepsOf(walk))
    }

    @Test
    fun aSharedBoundaryFixIsCountedOnce() {
        val boundary = fix(20, 20)
        val first = move(listOf(fix(0, null), fix(10, 18), boundary))
        val second = move(listOf(boundary, fix(30, 0), fix(40, 0)))

        assertEquals(listOf(38L, 0L), stepsOf(first, second))
        // The split journey's total equals the unsplit one's — no fix counted on both sides.
        val whole = move(listOf(fix(0, null), fix(10, 18), boundary, fix(30, 0), fix(40, 0)))
        assertEquals(stepsOf(whole).single(), stepsOf(first, second).sum())
    }

    /** ⚠⚠ Unknown is not zero: one missing reading makes the whole total unknown, not short. */
    @Test
    fun anyUncountedFixMakesTheTotalUnknown() {
        val gap = move(listOf(fix(0, 5), fix(10, 18), fix(20, null), fix(30, 19)))
        val noPedometer = move(listOf(fix(0, null), fix(10, null), fix(20, null)))

        assertEquals(listOf(-1L, -1L), stepsOf(gap, noPedometer))
    }

    /** The twin that keeps `-1` honest: a *counted* zero (wheels) stays a zero. */
    @Test
    fun aCountedZeroIsZeroNotUnknown() {
        val ride = move(listOf(fix(0, null), fix(10, 0), fix(20, 0)))
        assertEquals(listOf(0L), stepsOf(ride))
    }

    @Test
    fun aMoveWithNoIntervalIsUnknown() {
        assertEquals(listOf(-1L), stepsOf(move(listOf(fix(0, 12)))))
    }

    // ── §22 hygiene drops fixes; their steps must still reach the total ─────────────────────

    private fun coarse(sec: Int, steps: Int?) = fix(sec, steps).copy(accuracyM = 900)

    @Test
    fun aCoarseInteriorFixDroppedFromTheGeometryKeepsItsSteps() {
        // The reported case: deltas [null, 10, 20, 30] with the second fix coarse report 60, not 50.
        val fixes = listOf(fix(0, null), coarse(10, 10), fix(20, 20), fix(30, 30))
        val path = FixHygiene.geometryPath(fixes, maxAccuracyM = 200)

        assertEquals(3, path.size, "precondition: the coarse vertex is dropped from the geometry")
        assertEquals(20, path[1].stepsSinceLastFix, "the classifier's per-fix reading is untouched")
        assertEquals(listOf(60L), stepsOf(move(path)))
    }

    @Test
    fun anOutOfOrderFixDroppedByTimeOrderKeepsItsSteps() {
        val fixes = listOf(fix(0, 5), fix(20, 10), fix(15, 4), fix(30, 6))
        assertEquals(listOf(20L), stepsOf(move(FixHygiene.inTimeOrder(fixes))))
    }

    @Test
    fun theStreamOperatorCarriesADroppedFixsStepsToTheNextOneItEmits() = kotlinx.coroutines.test.runTest {
        val emitted = kotlinx.coroutines.flow.flowOf(fix(0, 5), fix(20, 10), fix(15, 4), fix(30, 6))
            .inTimeOrder()
            .let { flow -> buildList { flow.collect { add(it) } } }

        assertEquals(listOf(0, 0, 4), emitted.map { it.stepsDroppedBefore })
        assertEquals(listOf(20L), stepsOf(move(emitted)))
    }

    @Test
    fun aDroppedUncountedFixMakesTheTotalUnknown_notShort() {
        val fixes = listOf(fix(0, 5), coarse(10, null), fix(20, 20), fix(30, 30))
        assertEquals(listOf(-1L), stepsOf(move(FixHygiene.geometryPath(fixes, maxAccuracyM = 200))))
    }
}
