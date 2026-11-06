package app.tenshu.fit.data

import app.tenshu.fit.data.ActiveMinutes.HrPoint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure coverage for the HR-zone active-minutes derivation. No client, no platform — the importer
 * feeds it a day's raw HR series and stores the resulting per-day figure.
 */
class ActiveMinutesTest {

    private val min = 60_000L

    // maxHr 190 (age 30) → moderate floor = 0.64 * 190 = 121.6 bpm.

    @Test
    fun maxHeartRateIsAgePredicted() {
        assertEquals(190, ActiveMinutes.maxHeartRate(30))
        assertEquals(180, ActiveMinutes.maxHeartRate(40))
    }

    @Test
    fun countsMinutesAtOrAboveTheModerateFloor() {
        // Five samples one minute apart, all at 130 bpm (> 121.6 floor). Four intervals × 1 min.
        val samples = (0..4).map { HrPoint(it * min, 130) }
        assertEquals(4, ActiveMinutes.hrZoneMinutes(samples, maxHr = 190))
    }

    @Test
    fun excludesBelowFloorIntervals() {
        // Alternating above/below: only the intervals whose LEADING sample is in-zone count.
        val samples = listOf(
            HrPoint(0 * min, 130),   // in-zone → interval [0,1) counts
            HrPoint(1 * min, 90),    // below → interval [1,2) excluded
            HrPoint(2 * min, 130),   // in-zone → interval [2,3) counts
            HrPoint(3 * min, 90),    // below → interval [3,4) excluded
            HrPoint(4 * min, 130),   // in-zone but last sample → no trailing interval
        )
        assertEquals(2, ActiveMinutes.hrZoneMinutes(samples, maxHr = 190))
    }

    @Test
    fun capsCreditForALongGapAfterAnInZoneSample() {
        // One in-zone sample, then a 10-minute gap: credited at most the 1-minute cap, not 10.
        val samples = listOf(HrPoint(0, 130), HrPoint(10 * min, 130))
        assertEquals(1, ActiveMinutes.hrZoneMinutes(samples, maxHr = 190))
    }

    @Test
    fun ordersUnsortedSamplesBeforeCounting() {
        val samples = listOf(HrPoint(2 * min, 130), HrPoint(0, 130), HrPoint(1 * min, 130))
        assertEquals(2, ActiveMinutes.hrZoneMinutes(samples, maxHr = 190))
    }

    @Test
    fun degenerateInputsYieldZero() {
        assertEquals(0, ActiveMinutes.hrZoneMinutes(emptyList(), maxHr = 190))
        assertEquals(0, ActiveMinutes.hrZoneMinutes(listOf(HrPoint(0, 150)), maxHr = 190))   // single sample
        assertEquals(0, ActiveMinutes.hrZoneMinutes(listOf(HrPoint(0, 150), HrPoint(min, 150)), maxHr = 0))
    }

    @Test
    fun higherMaxHrRaisesTheFloor() {
        // 130 bpm is in-zone at maxHr 190 (floor 121.6) but below it at maxHr 210 (floor 134.4).
        val samples = listOf(HrPoint(0, 130), HrPoint(min, 130))
        assertEquals(1, ActiveMinutes.hrZoneMinutes(samples, maxHr = 190))
        assertEquals(0, ActiveMinutes.hrZoneMinutes(samples, maxHr = 210))
    }
}
