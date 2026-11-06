package org.tenshu.core.common.util

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import kotlin.time.Instant

actual object TimestampUtil {
    actual fun formatTimestamp(timestamp: Instant): String =
        formatTimestamp(timestamp, NSLocale.currentLocale, NSTimeZone.localTimeZone)

    /**
     * The locale and zone made explicit so a test can pin them. ⚠ The current locale is the
     * user's *region* as well as their language: a device set to English with Region: Germany
     * is `en_DE`, which formats "6. Oct 2026 at 21:12" — correct for that user, not a bug. A
     * screenshot run that wants US dates must set the region (`xcodebuild -testRegion US`),
     * not only the language.
     */
    internal fun formatTimestamp(timestamp: Instant, locale: NSLocale, timeZone: NSTimeZone): String {
        val formatter = NSDateFormatter().apply {
            this.locale = locale
            this.timeZone = timeZone
            dateStyle = NSDateFormatterMediumStyle
            timeStyle = NSDateFormatterShortStyle
        }
        val date = NSDate.dateWithTimeIntervalSince1970(
            timestamp.toEpochMilliseconds().toDouble() / 1000.0
        )
        return formatter.stringFromDate(date)
    }
}
