package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §35.8 — **a bicycle is not a run, however many steps the pedometer counts.**
 *
 * Measured over six commuting days: the same ride came back `run` on 6 of 6 mornings and `cycle` on
 * 5 of 5 evenings at overlapping speeds. Cadence was the discriminator and it was working correctly
 * on an input that is wrong — the pedometer counts pedalling, ~90 spm outbound against ~30 home.
 *
 * The check is **stride length**, which is a fact about bodies rather than a threshold to tune.
 */
class LegModeStrideTest {

    /** km/h → m/s, so the cases read as the corpus reported them. */
    private fun kmh(v: Double) = v / 3.6

    @Test
    fun aBicycleAtNinetyStepsPerMinuteIsNotARun() {
        // The measured morning ride: 16 km/h, 719 steps over 8 minutes ⇒ ~90 spm ⇒ a 2.96 m stride.
        val mode = LegMode.withoutImplausibleRun(MovementMode.RUN, kmh(16.0), cadenceSpm = 90.0)

        assertEquals(MovementMode.CYCLE, mode)
    }

    @Test
    fun aRealRunSurvives() {
        // The measured 9.31 km run: 17 km/h at ~157 spm ⇒ a 1.80 m stride, which is a person.
        val mode = LegMode.withoutImplausibleRun(MovementMode.RUN, kmh(17.0), cadenceSpm = 157.0)

        assertEquals(MovementMode.RUN, mode)
    }

    @Test
    fun aFastRunnerAtTheCeilingSurvives() {
        // ⚠ The boundary is generous on purpose: 2.2 m is above every sustained human stride, so a
        // sprinter is never reclassified as a cyclist. 20 km/h at 160 spm ⇒ 2.08 m.
        val mode = LegMode.withoutImplausibleRun(MovementMode.RUN, kmh(20.0), cadenceSpm = 160.0)

        assertEquals(MovementMode.RUN, mode)
    }

    @Test
    fun withNoPedometerNothingIsWithdrawn() {
        // ⚠⚠ `null` is not a small number: with no cadence there is no stride to compute, and the
        // speed-only bands already ran. 13% of the measured corpus is in this state, and Monday's
        // morning ride stays `run` because of it — an honest limit, not a silent one.
        val mode = LegMode.withoutImplausibleRun(MovementMode.RUN, kmh(16.0), cadenceSpm = null)

        assertEquals(MovementMode.RUN, mode)
    }

    @Test
    fun onlyRunIsEverWithdrawn() {
        // ⚠ A walk with an implausible stride is a pedometer that under-counted, not a vehicle —
        // and below the run floor the honest answer is still `walk`. This rule must not touch it.
        assertEquals(
            MovementMode.WALK,
            LegMode.withoutImplausibleRun(MovementMode.WALK, kmh(5.0), cadenceSpm = 20.0),
        )
        assertEquals(
            MovementMode.DRIVE,
            LegMode.withoutImplausibleRun(MovementMode.DRIVE, kmh(80.0), cadenceSpm = 5.0),
        )
    }
}
