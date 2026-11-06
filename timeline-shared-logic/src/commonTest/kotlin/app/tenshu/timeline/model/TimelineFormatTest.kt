package app.tenshu.timeline.model

import org.tenshu.preference.MeasurementSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineFormatTest {

    @Test
    fun underAnHour_isMinutesOnly() {
        val p = TimelineFormat.durationParts(32)
        assertEquals(DurationParts(hours = 0, minutes = 32, form = DurationForm.MinutesOnly), p)
    }

    @Test
    fun wholeHours_dropMinutes() {
        assertEquals(
            DurationParts(hours = 1, minutes = 0, form = DurationForm.HoursOnly),
            TimelineFormat.durationParts(60),
        )
        assertEquals(
            DurationParts(hours = 3, minutes = 0, form = DurationForm.HoursOnly),
            TimelineFormat.durationParts(180),
        )
    }

    @Test
    fun hoursAndMinutes() {
        assertEquals(
            DurationParts(hours = 1, minutes = 22, form = DurationForm.HoursAndMinutes),
            TimelineFormat.durationParts(82),
        )
    }

    @Test
    fun nonPositive_clampsToZeroMinutes() {
        val expected = DurationParts(hours = 0, minutes = 0, form = DurationForm.MinutesOnly)
        assertEquals(expected, TimelineFormat.durationParts(0))
        assertEquals(expected, TimelineFormat.durationParts(-5))
    }

    // ── distanceParts (M5-lite): whole metres below a km, one decimal above ──────────

    @Test
    fun metresBelowAKilometre() {
        assertEquals(
            DistanceParts(kilometres = 0, tenths = 0, metres = 850, form = DistanceForm.Metres),
            TimelineFormat.distanceParts(850),
        )
    }

    @Test
    fun kilometresWithOneDecimal() {
        assertEquals(
            DistanceParts(kilometres = 12, tenths = 4, metres = 0, form = DistanceForm.Kilometres),
            TimelineFormat.distanceParts(12_400),
        )
        // Rounds to the nearest tenth rather than truncating: 2.46 km → 2.5, 2.44 km → 2.4.
        assertEquals(5, TimelineFormat.distanceParts(2_460).tenths)
        assertEquals(4, TimelineFormat.distanceParts(2_440).tenths)
    }

    @Test
    fun roundingCarriesIntoTheWholePart() {
        // 999.96 km must read "1000.0 km", not "999.10 km".
        val parts = TimelineFormat.distanceParts(999_960)
        assertEquals(1000, parts.kilometres)
        assertEquals(0, parts.tenths)
    }

    @Test
    fun exactlyOneKilometreCrossesToTheKilometreForm() {
        assertEquals(DistanceForm.Metres, TimelineFormat.distanceParts(999).form)
        assertEquals(DistanceForm.Kilometres, TimelineFormat.distanceParts(1_000).form)
    }

    @Test
    fun negativeDistanceClampsToZeroMetres() {
        assertEquals(
            DistanceParts(kilometres = 0, tenths = 0, metres = 0, form = DistanceForm.Metres),
            TimelineFormat.distanceParts(-1),
        )
    }

    // ── FP-6: the same distances in the user's units (fit-app.md §18.13) ────────────────

    @Test
    fun imperialDistancesReadInFeetThenMiles() {
        // 120 m ≈ 394 ft — below the tenth-of-a-mile changeover, so whole feet.
        val short = TimelineFormat.distanceParts(120, MeasurementSystem.IMPERIAL)
        assertEquals(DistanceForm.Feet, short.form)
        assertEquals(393, short.metres)

        // 12.4 km ≈ 7.7 mi.
        val long = TimelineFormat.distanceParts(12_400, MeasurementSystem.IMPERIAL)
        assertEquals(DistanceForm.Miles, long.form)
        assertEquals(7, long.kilometres)
        assertEquals(7, long.tenths)
    }

    @Test
    fun theImperialChangeoverIsATenthOfAMileNotAMile() {
        // ⚠ The direct mirror of the metric rule (switch at 1 mi) would print "1,847 ft" for the
        // walk the metric build renders as "1.8 km" — precision nobody asked for on an estimate.
        // A tenth of a mile (~161 m) is Google Maps' rule and reads the way people speak: an
        // 850 m stroll is "half a mile", not "2,788 feet".
        assertEquals(DistanceForm.Feet, TimelineFormat.distanceParts(160, MeasurementSystem.IMPERIAL).form)
        val stroll = TimelineFormat.distanceParts(850, MeasurementSystem.IMPERIAL)
        assertEquals(DistanceForm.Miles, stroll.form)
        assertEquals(0, stroll.kilometres)
        assertEquals(5, stroll.tenths)
    }

    @Test
    fun metricRemainsTheDefaultSoNoCallerChangesMeaningByOmission() {
        assertEquals(
            TimelineFormat.distanceParts(12_400, MeasurementSystem.METRIC),
            TimelineFormat.distanceParts(12_400),
        )
    }
}