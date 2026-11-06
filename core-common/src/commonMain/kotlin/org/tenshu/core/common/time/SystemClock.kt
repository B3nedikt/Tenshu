package org.tenshu.core.common.time

import org.tenshu.core.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Clock as KClock

@OptIn(ExperimentalTime::class)
class SystemClock : Clock {
    override fun nowEpochMillis(): Long = KClock.System.now().toEpochMilliseconds()
}
