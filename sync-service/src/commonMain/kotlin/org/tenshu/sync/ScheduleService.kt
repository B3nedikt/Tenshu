package org.tenshu.sync

import org.tenshu.core.Service
import org.tenshu.core.schema.service

/**
 * Phase 5 (docs/proposals/core-background-processing.md §3.2(b)): owns the
 * persisted [ScheduleItem] set — the §6.3 `BackgroundJobRegistry` in concrete,
 * queryable form. The in-process drain loop ([org.tenshu.sync.proxy
 * .ScheduleDrainLoop]) drains it while the proxy is alive; a per-platform
 * `PlatformScheduler` (Phase 6) will drain it on OS wakes. One store, two
 * drivers.
 *
 * Only ops whose schema `isHeadlessSafe()` may be scheduled — enforced at
 * `Schedule` (the service op) **and** re-checked by the drain loop before
 * dispatch (defence in depth covering wire-deserialised / older-build items),
 * per §6.4. A runtime denylist would fail open; this fails closed.
 */
interface ScheduleService : Service {

    companion object {
        const val ID = "tenshu/sync/scheduleService"

        val Schema = service(id = ID)
    }

    object Collections {
        /** Persisted [ScheduleItem] queue, keyed by `dedupKey`. */
        const val SCHEDULED_ITEMS = "$ID/scheduledItems"
    }
}
