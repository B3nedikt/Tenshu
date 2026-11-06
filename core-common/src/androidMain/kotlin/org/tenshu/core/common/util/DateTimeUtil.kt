package org.tenshu.core.common.util

import android.os.Build
import java.text.DateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import kotlin.time.Instant
import kotlin.time.toJavaInstant

actual object TimestampUtil {
    actual fun formatTimestamp(timestamp: Instant): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val formatter =
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .withZone(ZoneId.systemDefault())
            formatter.format(timestamp.toJavaInstant())
        } else {
            val date = Date(timestamp.toEpochMilliseconds())
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)
        }
    }
}
