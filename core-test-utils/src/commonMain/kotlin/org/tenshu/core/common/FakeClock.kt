package org.tenshu.core.common

import org.tenshu.core.time.Clock

/**
 * Test clock with virtual time. Use [advance] to move time forward; use [setTo] to
 * jump to a specific epoch-millisecond. NOT thread-safe by design — tests should
 * drive it from a single thread or wrap calls externally if simulating concurrency.
 */
class FakeClock(initial: Long = 0L) : Clock {
    private var current: Long = initial

    override fun nowEpochMillis(): Long = current

    fun advance(deltaMillis: Long) {
        current += deltaMillis
    }

    fun setTo(epochMillis: Long) {
        current = epochMillis
    }
}
