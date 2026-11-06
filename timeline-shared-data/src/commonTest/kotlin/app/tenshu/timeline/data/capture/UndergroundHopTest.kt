package app.tenshu.timeline.data.capture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **§27 — the metro hop, at the proportions the real rides actually had.**
 *
 * The discriminator under test is the **frozen anchor**: underground the phone is pinned to one cell,
 * so consecutive NETWORK fixes repeat the same coordinate, while a train above ground hands off
 * between cells and its coarse fixes march. Measured median step between consecutive coarse fixes:
 * **0 m** on both real metro hops, **311–482 m** on all three real train rides.
 *
 * ⚠ The negative controls are the point of this file. A long above-ground train is *also* mostly
 * coarse and must stay unpromoted here (other tiers own it), and so must a phone in a bag on a drive.
 * Every fixture below is shaped to the measured proportions, not to whatever makes the rule pass.
 */
class UndergroundHopTest {

    private val t0 = 1_800_000_000_000L
    private val originLat = 481_400_000L
    private val originLng = 115_800_000L

    private fun lngE7(m: Double): Long = (m / (0.011132 * cos(48.14 * PI / 180.0))).roundToLong()

    private fun fix(eastM: Double, sec: Int, accuracyM: Int) = CapturedFix(
        latE7 = originLat,
        lngE7 = originLng + lngE7(eastM),
        timestampMs = t0 + sec * 1000L,
        accuracyM = accuracyM,
    )

    /** A platform: coarse fixes that do not move at all — the frozen anchor. */
    private fun platform(atM: Double, fromSec: Int, count: Int, accuracyM: Int = 300, everySec: Int = 30) =
        (0 until count).map { fix(atM, fromSec + it * everySec, accuracyM) }

    /** The measured U5 shape: platform → one cell-change jump of ~2.8 km → platform. */
    private fun metroSpan(): List<CapturedFix> =
        platform(600.0, 500, 9) + fix(3400.0, 800, 100) + platform(3400.0, 830, 8, accuracyM = 100)

    /**
     * The move as **capture** sees it, which is not the decimated trace.
     *
     * ⚠⚠ This used to be three fixes — departure platform, jump, arrival platform — and that was
     * never the real shape: §27's own measurement records the stored trace as a **12-fix coarse run**,
     * and the buffer at flush time holds the sub-threshold platform fixes too (they never formed a
     * dwell). Three fixes is two steps, and with the cell-change jump being one of them the median is
     * the mean — the exact averaging failure [UndergroundHop.anchorIsFrozen]'s KDoc refuses. The
     * fixture is now the measured proportions, so §32.7's per-leg test has something to measure.
     */
    private fun metroMove(): List<CapturedFix> =
        platform(600.0, 500, 9).takeLast(6) + fix(3400.0, 800, 100) + platform(3400.0, 830, 8, 100)

    /**
     * A train's **terminal approach** sharing the same flush and the same span — §32.7's live defect.
     * Its coarse anchor marches ~450 m per step, as all three measured train rides did.
     */
    private fun trainTailMove(): List<CapturedFix> =
        (1..6).map { fix(600.0 - it * 450.0, 200 + it * 30, 100) }

    @Test
    fun aMetroHopIsPromoted() {
        assertTrue(UndergroundHop.promotes(metroMove(), metroSpan()))
    }

    @Test
    fun aTrainsTerminalApproachSharingTheSpanIsNotPromoted() {
        // ⚠⚠ §32.7, the live defect: one flush held the train's approach into Ostbahnhof *and* the U5,
        // so both were judged against one span — and `coarseIntervals` builds maximal runs, so an
        // all-coarse flush is a single interval covering everything. `movedInside` is a time-overlap
        // test, so every leg in the flush overlapped it and every leg was promoted.
        val span = trainTailMove() + metroSpan()
        assertTrue(UndergroundHop.anchorIsFrozen(span), "precondition: the shared span reads frozen")
        assertTrue(UndergroundHop.promotes(metroMove(), span), "the real hop must survive the tightening")
        assertFalse(
            UndergroundHop.promotes(trainTailMove(), span),
            "a leg whose own anchor marches is not an underground hop, whatever the span says",
        )
    }

    @Test
    fun aLegTooThinToMeasureIsNotPromoted() {
        // ⚠ The accepted cost, pinned so it is a decision and not a surprise: three fixes is two steps
        // and one of them is the jump, so the median cannot separate pinned from marching. §27's
        // posture is that a missed promotion leaves the speed label while a wrong one is a confident
        // lie — and the rail sweep still gets a second look with tiles.
        val thin = listOf(platform(600.0, 500, 9).last(), fix(3400.0, 800, 100), platform(3400.0, 830, 8, 100).first())
        assertFalse(UndergroundHop.promotes(thin, metroSpan()))
    }

    @Test
    fun anAboveGroundTrainIsNotPromoted() {
        // ⚠⚠ The decisive control. Equally coarse, equally long, genuinely travelling — but its cell
        // anchor moves ~350 m between fixes, as all three real train rides did. If this ever passes,
        // the rule has stopped measuring "pinned" and started measuring "coarse".
        val span = (0 until 20).map { fix(1000.0 + it * 350.0, 500 + it * 30, accuracyM = 300) }
        val move = listOf(span.first(), span[10], span.last())
        assertFalse(UndergroundHop.promotes(move, span), "a moving coarse anchor is not a tunnel")
    }

    @Test
    fun aPhoneInABagOnADriveIsNotPromoted() {
        // Never any GPS at all, and the anchor marches: the fine-share guard used to be what refused
        // this; the frozen-anchor test refuses it for a stronger reason.
        val span = (0 until 16).map { fix(it * 600.0, 400 + it * 30, accuracyM = 500) }
        assertFalse(UndergroundHop.promotes(listOf(span.first(), span.last()), span))
    }

    @Test
    fun aStandStillWithNoTravelIsNotPromoted() {
        // ⚠ The real stored trace looked exactly like this — 12 coarse fixes, zero displacement. The
        // anchor is frozen, but nothing travelled, so there is no ride to promote. This is why the
        // frozen test alone is not the rule and the kinematic gates still have to pass.
        val stuck = platform(600.0, 500, 12)
        assertFalse(UndergroundHop.promotes(stuck, stuck))
    }

    @Test
    fun aBriefSignalDipIsNotAMetroRide() {
        val span = platform(600.0, 500, 3, everySec = 20) + fix(640.0, 570, 12)
        assertFalse(UndergroundHop.promotes(span, span))
    }

    @Test
    fun theFrozenAnchorTestSeparatesAtTheMeasuredProportions() {
        // The measurement, pinned directly: 0 m steps (metro) vs ~350 m steps (train).
        assertTrue(UndergroundHop.anchorIsFrozen(platform(600.0, 0, 8)), "0 m steps must read frozen")
        val marching = (0 until 8).map { fix(it * 350.0, it * 30, accuracyM = 300) }
        assertFalse(UndergroundHop.anchorIsFrozen(marching), "350 m steps must not read frozen")
    }

    @Test
    fun oneCellChangeJumpDoesNotUnfreezeTheAnchor() {
        // ⚠ Median, not mean: the single large jump *is* the ride, and a mean would let it mask an
        // otherwise-marching stream. Removing the median here is the mutation this catches.
        assertTrue(UndergroundHop.anchorIsFrozen(metroSpan()))
    }

    /**
     * The device delivers the same moment twice, seconds apart, at zero displacement (§21.3) — so when
     * both providers are coarse, every second raw step is a `0` whatever the traveller did.
     */
    private fun twinned(fromSec: Int, fromM: Double, stepM: Double, count: Int) =
        (0 until count).flatMap {
            listOf(fix(fromM + it * stepM, fromSec + it * 20, 100), fix(fromM + it * stepM, fromSec + it * 20 + 4, 94))
        }

    @Test
    fun providerDuplicatesDoNotFreezeAMarchingAnchor() {
        // ⚠⚠ §34.1, measured on the 2026-08-14 walk from the train to the U5 platform: the raw coarse
        // steps are `0, 3, 0, 76, 0, 111, 0, 48, 0` — median 0 m, "frozen", for a leg that is plainly
        // walking. Reading the steps between samples ≥ MIN_LEG_MS apart gives `13, 76, 111, 48`,
        // median 62 m, and refuses it. The false reading promoted the walk alongside the ride and the
        // two then coalesced back into the single 4.9 km row §34 set out to split.
        val walking = twinned(fromSec = 0, fromM = 0.0, stepM = 70.0, count = 8)
        assertFalse(
            UndergroundHop.anchorIsFrozen(walking),
            "70 m per tick is walking — only the duplicate samples make it look pinned",
        )
        // The twin: the identical cadence and duplication, going nowhere, must still read frozen.
        assertTrue(UndergroundHop.anchorIsFrozen(twinned(0, 600.0, stepM = 0.0, count = 8)))
    }

    @Test
    fun theSpanIsJudgedByTheBlackoutNotByTheWholeFlush() {
        // ⚠⚠ §34.1, the live defect. §32.7 reasoned the span test was "close to unconditionally true"
        // because the dwell tail dominates the median — which holds only while the flush is short. One
        // flush carrying a 57 km surface train *then* the U5 measured 200.9 m median over the span and
        // 0.0 m over the hop, so the span test vetoed the very ride this tier exists for.
        // ⚠ The train marches ~450 m per coarse fix and then **arrives above ground**, where GPS comes
        // back — which is what makes its blackout a *different* interval from the hop's. See
        // [UndergroundHop.promotes] on the all-coarse flush this cannot help.
        val train = (0 until 30).map { fix(-13_500.0 + it * 450.0, -500 + it * 30, accuracyM = 100) } +
            (0 until 4).map { fix(400.0 + it * 50.0, 400 + it * 20, accuracyM = 12) }
        val span = train + metroSpan()

        assertFalse(UndergroundHop.anchorIsFrozen(span), "precondition: a long surface ride unfreezes the span")
        assertTrue(
            UndergroundHop.promotes(metroMove(), span),
            "the hop must be judged by the blackout it sits in, not by everything the flush buffered",
        )
        // ⚠ And the tightening §32.7 bought is untouched: the train's own leg still marches.
        assertFalse(UndergroundHop.promotes(trainTailMove(), span))
    }

    @Test
    fun aPinnedLegInsideAMarchingBlackoutIsNotAHop() {
        // ⚠⚠ §34.1 asks the frozen-anchor test of the **interval**, not of the whole flush — and the
        // interval half is not decoration. A short leg can be pinned inside a blackout that is plainly
        // travelling: a pause at a signal, a platform, a wait in a queue. Measured on 08-11, dropping
        // this conjunct promoted a 467 m walk after a train into the train's own `Transit` row.
        val approach = (0 until 10).map { fix(it * 300.0, it * 30, accuracyM = 300) }
        val pause = (0 until 5).map { fix(3_000.0, 300 + it * 30, accuracyM = 300) }
        val onward = (0 until 10).map { fix(3_000.0 + it * 300.0, 450 + it * 30, accuracyM = 300) }
        val span = approach + pause + onward

        assertTrue(UndergroundHop.anchorIsFrozen(pause), "precondition: the pause's own anchor is frozen")
        assertFalse(
            UndergroundHop.promotes(pause, span),
            "a pinned leg inside a blackout that is marching is a pause, not an underground hop",
        )
    }

    @Test
    fun aLegIsPinnedOnlyWhenItIsAlsoBlind() {
        // ⚠⚠ The frozen anchor alone does not mean "underground" — it fires wherever the traveller
        // stays inside one cell. Measured on this corpus a real 4.8 km RUN reads frozen over its own
        // fixes, because its NETWORK samples repeat while its GPS samples march. What makes the
        // reading mean a tunnel is §25's other half: GPS stopped reporting at all.
        val dark = pinned(600.0, 0, 8)
        val window = platform(600.0, 0, 8)
        assertTrue(UndergroundHop.legIsPinned(dark, window))

        val litLeg = listOf(fix(600.0, 200, 12)) + pinned(600.0, 210, 4)
        assertFalse(UndergroundHop.legIsPinned(litLeg, window), "one fine fix means we COULD look")

        val marching = (0 until 8).map { fix(it * 350.0, it * 30, accuracyM = 300) }
        assertFalse(UndergroundHop.legIsPinned(dark, marching), "a marching window is not a pinned one")
    }

    /** A leg's own fixes, coarse and going nowhere. */
    private fun pinned(atM: Double, fromSec: Int, count: Int) =
        (0 until count).map { fix(atM, fromSec + it * 10, accuracyM = 100) }

    @Test
    fun promotionSitsBelowTheTileTierSoItCanStillBeDemoted() {
        assertTrue(
            MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND < MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED,
            "a capture-time promotion must still look un-swept to the tile tier",
        )
    }
}
