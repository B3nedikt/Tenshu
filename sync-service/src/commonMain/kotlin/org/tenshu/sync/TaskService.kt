package org.tenshu.sync

import org.tenshu.core.Service
import org.tenshu.core.schema.service

/**
 * The task-composite service (`core-task-composite.md` §5–§6): registers and
 * manages client-orchestrated bulk tasks. The §10 equation, structurally:
 *
 * > 1 `BackgroundTask` == 1 liveness op == 1 sync lane ==
 * > 1 batch block per checkpoint == N content-plane blobs.
 *
 * `register(spec)` mints the proxy-side task row, opens its sync lane
 * (TS-2 `SyncObserver` lane keyed by the taskId), and starts its lease;
 * constituent one-shot `IoOperation`s join via `withTask(task) { }`;
 * `complete`/`fail`/`cancel` seal the final checkpoint and close the lane.
 * The registry's live-task count feeds the platform liveness sum
 * (`ForegroundTaskLiveness` on Android).
 *
 * v1 durability is **Layer 2**: task rows are in-memory and die with the
 * proxy (staged deltas survive via the lane journal); recovery =
 * re-register + consumer idempotency. A durable Layer-3 task is TS-6.
 */
interface TaskService : Service {

    companion object {
        const val ID = "tenshu/task/service"

        val Schema = service(id = ID)
    }
}
