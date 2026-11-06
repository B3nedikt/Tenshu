package org.tenshu.health.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.health.DailyActivity
import org.tenshu.sync.common.SemanticMerger

/**
 * The **cross-device half of [DailyActivityFold]** — the same per-field rule applied when a peer's
 * [DailyActivity] block lands on a day this device already has.
 *
 * Without it the inbound path is whole-row last-writer-wins, which reproduces the local clobber
 * across devices: the phone (Health Connect, steps only) and the laptop (a Takeout import, distance
 * + energy + active minutes) each hold a partial view of the same day, and whichever block arrives
 * later erases the other's fields. Folding instead makes the day the *union* of what the two
 * devices actually know.
 *
 * ⚠ **Must stay registered in production wiring** — every platform's `PlatformProxyModule`, the
 * system-test module and the JS plugin. An unregistered merger does not fail loudly; it silently
 * degrades this row back to last-writer-wins, which is exactly the bug. `MomentMerger` /
 * `TripMerger` are the standing cautionary tale (test-registered only).
 */
class DailyActivityMerger : SemanticMerger {

    override val typeId: String = DailyActivity.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data =
        DailyActivityFold.fold(executionContext.context, existing = existing, incoming = incoming)
}
