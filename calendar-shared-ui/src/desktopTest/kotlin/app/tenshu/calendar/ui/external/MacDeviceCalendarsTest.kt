package app.tenshu.calendar.ui.external

import app.tenshu.calendar.data.external.ExternalCalendarReader
import app.tenshu.macos.MacEventKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/** *Copy from this Mac* (CX-2c): the unbundled guard, and EventKit's numbering into AppleConversion's. */
class MacDeviceCalendarsTest {

    /**
     * ⚠⚠ A test JVM is unbundled, like `gradle run`: no calendar usage string, where touching
     * EventKit makes TCC kill the process. The module must bind no reader, so nothing offers the flow.
     */
    @Test
    fun an_unbundled_jvm_binds_no_calendar_reader() {
        assertFalse(macCalendarsUsable())
        val koin = startKoin { modules(desktopDeviceCalendarModule()) }.koin
        try {
            assertNull(koin.getOrNull<ExternalCalendarReader>())
        } finally {
            stopKoin()
        }
    }

    @Test
    fun eventkit_weekdays_and_week_start_become_iso() {
        val rule = MacEventKit.Recurrence(
            frequency = 1, interval = 2,
            daysOfTheWeek = listOf(1 to 0, 2 to 0, 7 to -1), // Sunday, Monday, last Saturday
            daysOfTheMonth = emptyList(), monthsOfTheYear = emptyList(), setPositions = emptyList(),
            daysOfTheYear = emptyList(), weeksOfTheYear = emptyList(),
            firstDayOfTheWeek = 1, count = 0, untilMs = null,
        )

        val row = MacCalendarReader().recurrenceOf(rule)

        assertEquals(listOf(7 to 0, 1 to 0, 6 to -1), row.daysOfTheWeek)
        assertEquals(7, row.firstDayOfWeek, "Sunday-start weeks")
        assertEquals(0, MacCalendarReader().recurrenceOf(rule.copy(firstDayOfTheWeek = 0)).firstDayOfWeek, "unset stays unset")
    }

    /** ⚠ The iOS names, so one iCloud calendar has one sourceKey on a Mac and an iPhone. */
    @Test
    fun source_types_use_the_ios_names() {
        val reader = MacCalendarReader()
        assertEquals(listOf("local", "exchange", "calDAV", "subscribed", "birthdays", "other"),
            listOf(0L, 1L, 2L, 4L, 5L, 99L).map(reader::sourceTypeName))
    }
}
