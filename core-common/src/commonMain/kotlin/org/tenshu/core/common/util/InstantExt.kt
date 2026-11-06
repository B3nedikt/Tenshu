package org.tenshu.core.common.util

import org.tenshu.core.time.Instant
import kotlin.time.ExperimentalTime
import kotlin.time.Instant as KInstant

@OptIn(ExperimentalTime::class)
fun Instant.toKotlinInstant(): KInstant =
    KInstant.fromEpochMilliseconds(toEpochMilliseconds())

@OptIn(ExperimentalTime::class)
fun TimestampUtil.formatTimestamp(timestamp: Instant): String =
    formatTimestamp(timestamp.toKotlinInstant())

/** Convenience for epoch-millis timestamps (e.g. the `File.LastModifiedAt` value property). */
@OptIn(ExperimentalTime::class)
fun TimestampUtil.formatTimestamp(epochMillis: Long): String =
    formatTimestamp(KInstant.fromEpochMilliseconds(epochMillis))
