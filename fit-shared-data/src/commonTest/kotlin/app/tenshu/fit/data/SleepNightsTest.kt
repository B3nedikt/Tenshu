package app.tenshu.fit.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SleepNightsTest {
    private val hour = 60 * 60 * 1000L
    private val min = 60 * 1000L

    /** Hours since the start of "day 0"; days are 24 h buckets named d0, d1, … */
    private fun at(h: Double) = (h * hour).toLong()
    private val dayOf: (Long) -> String = { ms -> "d${ms / (24 * hour)}" }
    private fun s(from: Double, to: Double, v: String = "core") = SleepNights.Sample(at(from), at(to), v)

    @Test
    fun aNightAcrossMidnightIsOneNightOnItsWakeDay() {
        // 23:10 → 07:00, stages on either side of midnight, plus the in-bed span.
        val nights = SleepNights.group(
            listOf(s(23.1, 31.0, "inBed"), s(23.3, 23.9, "deep"), s(23.9, 25.5), s(25.5, 26.0, "rem"), s(26.0, 31.0)),
            dayOf,
        )
        assertEquals(1, nights.size)
        assertEquals("d1", nights.single().date)
        assertEquals(at(31.0) - at(23.1), nights.single().endMs - nights.single().startMs)
        assertEquals(5, nights.single().samples.size)
    }

    @Test
    fun consecutiveNightsDoNotMergeIntoADayLongSession() {
        // The defect: grouping by each sample's end day made d1's group run from 23:20 on d0 to 23:59 on d1.
        val night1 = listOf(s(23.3, 23.9), s(23.9, 31.0))
        val night2 = listOf(s(47.2, 47.9), s(47.9, 54.8))
        val nights = SleepNights.group(night1 + night2, dayOf)
        assertEquals(listOf("d1", "d2"), nights.map { it.date })
        nights.forEach { assertEquals(true, it.endMs - it.startMs < 9 * hour, "a night longer than 9 h: $it") }
    }

    @Test
    fun shortAwakeningsStayInTheNight() {
        val nights = SleepNights.group(listOf(s(23.0, 26.0), s(26.0, 26.1, "awake"), s(26.5, 30.0)), dayOf)
        assertEquals(1, nights.size)
    }

    @Test
    fun aNapOnTheSameDayKeepsTheNightNotTheNap() {
        val night = listOf(s(23.0, 31.0))
        val nap = listOf(s(38.0, 38.0 + 40.0 * min / hour))
        val nights = SleepNights.group(night + nap, dayOf)
        assertEquals(1, nights.size)
        assertEquals(at(23.0), nights.single().startMs)
    }

    @Test
    fun overlappingSourcesAreOneNight() {
        // A phone's in-bed span and a watch's stages for the same night, in any order.
        val nights = SleepNights.group(listOf(s(23.5, 30.5, "core"), s(23.0, 31.0, "inBed")), dayOf)
        assertEquals(1, nights.size)
        assertEquals(at(23.0), nights.single().startMs)
        assertEquals(at(31.0), nights.single().endMs)
    }

    @Test
    fun nothingIsNoNights() {
        assertEquals(emptyList(), SleepNights.group(emptyList<SleepNights.Sample<String>>(), dayOf))
    }
}
