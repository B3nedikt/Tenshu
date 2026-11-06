package org.tenshu.core.common.util

import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.timeZoneWithName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The file-row / event-feed date follows the locale it is given — never a fixed pattern. */
class TimestampUtilLocaleTest {

    // 2026-10-06T19:12:00Z = 21:12 in Berlin.
    private val t = Instant.fromEpochMilliseconds(1_791_313_920_000)
    private val berlin = NSTimeZone.timeZoneWithName("Europe/Berlin")!!

    private fun format(id: String) =
        TimestampUtil.formatTimestamp(t, NSLocale(localeIdentifier = id), berlin)
            // ICU puts a narrow no-break space before AM/PM; compare on plain spaces.
            .replace(' ', ' ')

    @Test
    fun enUsIsMonthFirstWithTwelveHourTime() {
        assertEquals("Oct 6, 2026 at 9:12 PM", format("en_US"))
    }

    @Test
    fun deDeIsNumericDayFirst() {
        assertEquals("06.10.2026, 21:12", format("de_DE"))
    }

    @Test
    fun englishWithAGermanRegionIsDayFirstAndThatIsCorrect() {
        // What the 2026-10-14 App Store screenshots showed: the simulator inherited the host's
        // Region: Germany under an English language.
        assertEquals("6. Oct 2026 at 21:12", format("en_DE"))
    }
}
