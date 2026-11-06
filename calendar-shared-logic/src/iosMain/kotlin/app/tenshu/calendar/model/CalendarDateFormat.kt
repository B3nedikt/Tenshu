package app.tenshu.calendar.model

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import platform.Foundation.NSCalendar
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

/**
 * `NSDateFormatter`, which is the same table iOS's own Calendar reads —
 * so Tenshu's month header says what Apple's does, in whatever language the
 * device is set to.
 *
 * ⚠ A fresh formatter per call, like the JVM twin: `NSDateFormatter` caches the
 * locale it was created with, so a long-lived one keeps rendering the old
 * language after an in-place language switch.
 */
actual object CalendarDateFormat {

    actual fun weekdayShort(day: DayOfWeek): String {
        val symbols = NSDateFormatter().shortWeekdaySymbols
        // ⚠ Cocoa's array is Sunday-first (index 0); ISO is Monday 1 … Sunday 7.
        val index = day.isoDayNumber % 7
        return (symbols.getOrNull(index) as? String) ?: day.name.take(3)
    }

    actual fun monthName(month: Int): String {
        val symbols = NSDateFormatter().monthSymbols
        return (symbols.getOrNull(month - 1) as? String) ?: month.toString()
    }

    actual fun mediumDate(date: LocalDate): String {
        val formatter = NSDateFormatter().apply {
            dateStyle = NSDateFormatterMediumStyle
            timeStyle = NSDateFormatterNoStyle
        }
        val components = NSDateComponents().apply {
            year = date.year.toLong()
            month = date.month.number.toLong()
            day = date.day.toLong()
            hour = 12   // midday: never near a DST edge
        }
        val instant = NSCalendar.currentCalendar.dateFromComponents(components)
            ?: return date.toString()
        return formatter.stringFromDate(instant)
    }

    actual fun shortTime(time: LocalTime): String {
        val formatter = NSDateFormatter().apply {
            dateStyle = NSDateFormatterNoStyle
            timeStyle = NSDateFormatterShortStyle
        }
        val components = NSDateComponents().apply {
            year = 2000
            month = 1
            day = 1
            hour = time.hour.toLong()
            minute = time.minute.toLong()
        }
        val instant = NSCalendar.currentCalendar.dateFromComponents(components)
            ?: return "${time.hour}:${time.minute.toString().padStart(2, '0')}"
        return formatter.stringFromDate(instant)
    }

    /**
     * ⚠ `dateFormatFromTemplate` resolves the skeleton `"j"` — ICU's
     * "locale-preferred hour" — into whichever concrete hour field this locale
     * uses. That is the same lookup `NSDateFormatter` itself performs, so the
     * answer cannot disagree with what [shortTime] rendered.
     */
    actual fun uses24HourClock(): Boolean {
        val pattern = NSDateFormatter.dateFormatFromTemplate(
            tmplate = "j",
            options = 0u,
            locale = NSLocale.currentLocale,
        ) ?: return true
        return pattern.contains("H") || pattern.contains("k")
    }
}
