package app.tenshu.fit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FitFormatTest {

    @Test
    fun durationPartsSplitsForms() {
        assertEquals(DurationParts(0, 42, DurationForm.MinutesOnly), FitFormat.durationParts(42))
        assertEquals(DurationParts(2, 0, DurationForm.HoursOnly), FitFormat.durationParts(120))
        assertEquals(DurationParts(1, 12, DurationForm.HoursAndMinutes), FitFormat.durationParts(72))
        assertEquals(DurationParts(0, 0, DurationForm.MinutesOnly), FitFormat.durationParts(-5))
    }

    @Test
    fun clockPartsShowsHoursFromSixtyMinutes() {
        assertEquals(ClockParts(0, 32, 5, showHours = false), FitFormat.clockParts(32 * 60 + 5))
        assertEquals(ClockParts(1, 12, 0, showHours = true), FitFormat.clockParts(72 * 60))
        assertEquals(ClockParts(0, 0, 0, showHours = false), FitFormat.clockParts(-1))
    }

    @Test
    fun pacePartsSplitsMinSec() {
        // 5:29 /km — the Sunday-run pace in the mock (1920 s / 5.2 km ≈ 369 s).
        assertEquals(PaceParts(6, 9), FitFormat.paceParts(369))
        assertEquals(PaceParts(0, 0), FitFormat.paceParts(0))
    }

    @Test
    fun distancePartsSwitchesAtOneKm() {
        assertEquals(DistanceForm.Meters, FitFormat.distanceParts(830).form)
        assertEquals(830, FitFormat.distanceParts(830).meters)
        val fiveTwo = FitFormat.distanceParts(5200)
        assertEquals(DistanceForm.Kilometers, fiveTwo.form)
        assertEquals(52, fiveTwo.kmTenths)
    }

    @Test
    fun kjToKcalMatchesMockValues() {
        // fit-data.js: 1747 kJ ≈ 417 kcal.
        assertEquals(418, FitFormat.kjToKcal(1747))
        assertEquals(0, FitFormat.kjToKcal(0))
    }

    @Test
    fun weekDeltaPercent() {
        assertEquals(12, FitFormat.weekDeltaPercent(280, 250))
        assertEquals(-20, FitFormat.weekDeltaPercent(200, 250))
        assertNull(FitFormat.weekDeltaPercent(200, 0))
    }
}
