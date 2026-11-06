package app.tenshu.timeline.data.capture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Provider-duplicate immunity** (`timeline-movement-capture.md` §22 follow-up).
 *
 * `activeProviders()` registers GPS and NETWORK as two independent listeners, so the same position is
 * routinely delivered twice a second or two apart. Measured over a real 2 587-fix corpus, **33 % of
 * consecutive pairs are ≤ 4 s apart with a median displacement of 0.0 m**. Because the mode verdict is
 * the *median* of the per-leg speeds, one such pair per genuine leg is enough to halve the population
 * with zeroes — and on three real transit legs it put the median at 0.00–0.14 m/s for journeys that
 * covered kilometres, which `classifyMode` then read as `walk`.
 *
 * ⚠ The governing property is **invariance**, not a threshold: interleaving duplicates into a path
 * must not change what that path is classified as. Asserting it that way means the test cannot pass by
 * agreeing with whatever the current constant happens to be.
 */
class ProviderDuplicateLegTest {

    private val t0 = 1_800_000_000_000L
    private val originLat = 525_000_000L
    private val originLng = 134_000_000L

    private fun latE7(m: Double): Long = (m / 0.011132).roundToLong()

    private fun at(north: Double, ms: Long, steps: Int? = null) = CapturedFix(
        latE7 = originLat + latE7(north),
        lngE7 = originLng,
        timestampMs = t0 + ms,
        accuracyM = 10,
        stepsSinceLastFix = steps,
    )

    /** A clean path: [n] fixes at [speedMps], one every [everyMs]. */
    private fun path(n: Int, speedMps: Double, everyMs: Long = 30_000, steps: Int? = null) =
        (0..n).map { i -> at(speedMps * (everyMs / 1000.0) * i, everyMs * i, steps) }

    /**
     * The same path as the device actually delivers it: after each fix, the *other* provider reports
     * the very same position [lagMs] later. No new information, one extra leg each.
     */
    private fun withDuplicates(fixes: List<CapturedFix>, lagMs: Long = 2_000, steps: Int? = null) =
        fixes.flatMap { f ->
            listOf(f, f.copy(timestampMs = f.timestampMs + lagMs, stepsSinceLastFix = steps))
        }

    @Test
    fun aDuplicateForEveryFixDoesNotChangeTheVerdict() {
        val clean = path(n = 12, speedMps = 6.0) // ~21 km/h — squarely `cycle`
        val asDelivered = withDuplicates(clean)

        assertEquals(MovementMode.CYCLE, classifyMode(clean).mode, "precondition")
        assertEquals(
            MovementMode.CYCLE,
            classifyMode(asDelivered).mode,
            "a duplicate per fix must not demote a ride to a walk",
        )
    }

    @Test
    fun theMedianSpeedIsUnchangedByDuplicates() {
        // The mechanism, measured rather than inferred from the label.
        val clean = path(n = 12, speedMps = 1.3) // a walk
        val cleanMedian = classifyMode(clean).medianSpeedMmPerS
        val deliveredMedian = classifyMode(withDuplicates(clean)).medianSpeedMmPerS

        assertTrue(
            abs(cleanMedian - deliveredMedian) < 50, // < 5 cm/s apart
            "median moved from $cleanMedian to $deliveredMedian mm/s under duplicates",
        )
    }

    @Test
    fun absorbedFixesKeepTheirDistance() {
        // ⚠ Absorbed, not dropped: a leg spans across the fixes it swallowed, so the path length the
        // legs describe still matches the path. A `continue` that skipped the leg outright would lose
        // 30 m here and under-report every journey.
        val clean = path(n = 4, speedMps = 5.0)
        val cleanLegs = LegMode.legsOf(clean)
        val deliveredLegs = LegMode.legsOf(withDuplicates(clean))

        assertEquals(cleanLegs.size, deliveredLegs.size, "the duplicates add no legs of their own")
        assertTrue(
            deliveredLegs.all { abs(it.speedMps - 5.0) < 0.1 },
            "every leg should still measure the real 5 m/s, got ${deliveredLegs.map { it.speedMps }}",
        )
    }

    @Test
    fun cadenceSumsAcrossAnAbsorbedSpan() {
        // 30 steps per 30 s base = 60 spm. Delivered as 20 on the real fix + 10 on its duplicate, the
        // span must still read 60 — a leg that counted only the last fix's steps would read 20.
        val clean = path(n = 8, speedMps = 1.3, steps = 20)
        val delivered = withDuplicates(clean, steps = 10)
        val legs = LegMode.legsOf(delivered).filter { it.cadenceSpm != null }

        assertTrue(legs.isNotEmpty(), "cadence should survive the absorption")
        assertTrue(
            legs.all { abs(it.cadenceSpm!! - 60.0) < 1.0 },
            "expected ~60 spm across the span, got ${legs.map { it.cadenceSpm }}",
        )
    }

    @Test
    fun aMissingStepCountInTheSpanYieldsNullNotZero() {
        // ⚠⚠ Zero steps is LegMode's evidence for *wheels*. Reading an uncounted fix as zero would
        // turn a walk whose pedometer missed one sample into a bike ride, so the span must answer
        // "nobody counted" instead.
        val clean = path(n = 8, speedMps = 1.3, steps = 20)
        val delivered = withDuplicates(clean, steps = null) // duplicate carries no count
        assertTrue(
            LegMode.legsOf(delivered).all { it.cadenceSpm == null },
            "a null inside the span must propagate",
        )
        // And the twin that shows why it matters: at this speed a *zero* cadence would still read
        // `walk`, but at cycling speed it is exactly what promotes to `cycle` — so a manufactured zero
        // is not a harmless default.
        assertEquals(MovementMode.CYCLE, LegMode.of(speedMps = 5.0, cadenceSpm = 0.0))
        assertEquals(MovementMode.RUN, LegMode.of(speedMps = 5.0, cadenceSpm = 180.0))
    }

    @Test
    fun aPathShorterThanOneLegStillProducesALeg() {
        // ⚠ A brief input must answer something. Silence here would classify as UNKNOWN, which is a
        // worse claim than an uncertain one.
        val brief = listOf(at(0.0, 0), at(6.0, 3_000)) // 3 s, under the 8 s base
        val legs = LegMode.legsOf(brief)
        assertEquals(1, legs.size)
        assertTrue(abs(legs.single().speedMps - 2.0) < 0.1, "measured over the whole span")
    }

    @Test
    fun nonAdvancingTimestampsStillProduceNoLeg() {
        // The pre-existing guard, kept: identical timestamps cannot measure anything.
        assertTrue(LegMode.legsOf(listOf(at(0.0, 0), at(5.0, 0))).isEmpty())
    }
}
