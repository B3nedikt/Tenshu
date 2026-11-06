package org.tenshu.core.common.util

import kotlin.time.Instant

expect object TimestampUtil {
    fun formatTimestamp(timestamp: Instant): String
}
