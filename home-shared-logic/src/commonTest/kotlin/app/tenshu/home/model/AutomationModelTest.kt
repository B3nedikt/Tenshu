package app.tenshu.home.model

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationModelTest {

    @Test
    fun minutesOfDayFormatWithLeadingZeroMinutes() {
        assertEquals("22:30", formatMinutesOfDay(22 * 60 + 30))
        assertEquals("8:05", formatMinutesOfDay(8 * 60 + 5))
        assertEquals("0:00", formatMinutesOfDay(0))
        // Out-of-range values wrap instead of rendering nonsense.
        assertEquals("0:30", formatMinutesOfDay(24 * 60 + 30))
    }

    @Test
    fun minutesOfDayParseRoundTripsAndRejectsMalformedInput() {
        assertEquals(22 * 60 + 30, parseMinutesOfDay("22:30"))
        assertEquals(8 * 60 + 5, parseMinutesOfDay(" 8:05 "))
        assertEquals(null, parseMinutesOfDay("24:00"))
        assertEquals(null, parseMinutesOfDay("8:60"))
        assertEquals(null, parseMinutesOfDay("nope"))
        assertEquals(null, parseMinutesOfDay("8"))
    }

    @Test
    fun activityTimestampsFormatInTheGivenZone() {
        val at = Instant.parse("2026-07-22T12:34:00Z")
        assertEquals("12:34", formatTimeOfDay(at, TimeZone.UTC))
        assertEquals("2026-07-22", localDayOf(at, TimeZone.UTC).toString())
    }
}
