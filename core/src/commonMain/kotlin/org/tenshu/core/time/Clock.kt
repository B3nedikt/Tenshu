package org.tenshu.core.time

import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext

/**
 * Wall-clock source for the platform. Single substitution point for tests and for
 * the threat-model invariant that the proxy needs trustworthy time for HLC ordering.
 *
 * Production registers a `SystemClock` backed by `kotlin.time.Clock.System`. Tests
 * substitute a fake (e.g. `FakeClock` in `:core-test-utils`).
 *
 * Returns epoch-millisecond `Long` directly, not `kotlin.time.Instant`, because the
 * platform's wire unit is millisecond-since-epoch (see [UnixInstant]). Callers that
 * need a `kotlin.time.Instant` (formatters) convert at the consumption layer.
 */
interface Clock : TComponent {

    fun nowEpochMillis(): Long

    companion object {
        const val COMPONENT_ID = "tenshu/core/time/clock"
    }
}

fun TContext.clock(): Clock =
    components[Clock.COMPONENT_ID] as Clock
