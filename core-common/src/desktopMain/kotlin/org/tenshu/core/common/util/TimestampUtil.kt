package org.tenshu.core.common.util

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

actual object TimestampUtil {
    actual fun formatTimestamp(timestamp: Instant): String =
        formatTimestamp(timestamp, Locale.getDefault(Locale.Category.FORMAT), ZoneId.systemDefault())

    /**
     * The locale and zone made explicit so a test can pin them. ⚠ Never hand-roll a pattern
     * here: the user's locale decides the order ("Oct 6, 2026" vs "06.10.2026"), and a
     * region-only override (macOS/iOS "Region: Germany" with English as the language) is a
     * real configuration whose day-first output is correct, not a bug.
     */
    internal fun formatTimestamp(timestamp: Instant, locale: Locale, zone: ZoneId): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(timestamp.toJavaInstant())
}
