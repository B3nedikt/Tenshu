package org.tenshu.core.common.util

import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The file-row / event-feed date follows the locale it is given — never a fixed pattern. */
class TimestampUtilLocaleTest {

    // 2026-10-06T19:12:00Z = 21:12 in Berlin.
    private val t = Instant.fromEpochMilliseconds(1_791_313_920_000)
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun format(locale: Locale) =
        TimestampUtil.formatTimestamp(t, locale, berlin)
            // CLDR puts a narrow no-break space before AM/PM; compare on plain spaces.
            .replace(' ', ' ')

    @Test
    fun enUsIsMonthFirstWithTwelveHourTime() {
        assertEquals("Oct 6, 2026, 9:12 PM", format(Locale.US))
    }

    @Test
    fun deDeIsNumericDayFirst() {
        assertEquals("06.10.2026, 21:12", format(Locale.GERMANY))
    }
}
