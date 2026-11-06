package org.tenshu.core.common.util

import kotlin.js.Date
import kotlin.time.Instant

actual object TimestampUtil {
    // Mirrors the JVM/Android impl that uses DateTimeFormatter with
    // FormatStyle.MEDIUM date + FormatStyle.SHORT time. The browser equivalent
    // is Date.prototype.toLocaleString with `dateStyle: 'medium'` and
    // `timeStyle: 'short'` — going through `js(...)` because the Kotlin/JS
    // stdlib's typed `LocaleOptions` predates those fields.
    actual fun formatTimestamp(timestamp: Instant): String {
        val date = Date(timestamp.toEpochMilliseconds().toDouble())
        return js("date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })") as String
    }
}
