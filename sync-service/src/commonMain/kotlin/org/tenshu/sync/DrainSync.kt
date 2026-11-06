package org.tenshu.sync

import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The head-less **sync drain** op (bgproc Phase 1, `bgproc-scheduling-roles.md`
 * §6). A param-less, self-driving background op that runs one full drain/pull
 * cycle of the in-process [org.tenshu.sync.proxy.SyncScheduler] via its
 * single-flight `drainNow()`. It is the *registry-addressable face* of the same
 * drain the realtime loop runs — so both drivers converge on one op over one
 * `ScheduleItem` registry:
 *
 *  - `ScheduleDrainLoop` dispatches it in-process while the proxy is alive.
 *  - on Android, `WorkManager` (`PlatformScheduler`) wakes a closed Control and
 *    `TenshuOperationWorker` dispatches it by id — so a `WifiOnly` /
 *    `MaxMonthlyDataMb` `ConstraintSpec` (via `BackgroundSyncScheduler`) finally
 *    gates *app-closed* sync, which Path A's in-process gate cannot reach.
 *
 * Carries [HeadlessSafe] (fail-closed: `ScheduleService` refuses to schedule an
 * op without it, and `ScheduleDrainLoop` re-checks before dispatch) — it runs
 * with no user present. Never called from a client; only the scheduler dispatches
 * it by id, so it needs no receiver, params, or client RO (mirrors
 * `org.tenshu.core.file.system.OfflineKeeper`, the first production `HeadlessSafe`
 * op). Re-entry with the realtime loop is safe: `drainNow()` is single-flight
 * behind the scheduler's `tickMutex`, so a WorkManager wake that overlaps the
 * in-process tick simply serialises.
 */
object DrainSync {

    const val ID = "${SyncService.ID}/drainSync"

    val Schema: OperationSchema = operation(
        id = ID,
        traits = listOf(
            IoOperation.Schema,
            TerminalOperation.Schema,
            HeadlessSafe.Schema,
        ),
    )
}
