package org.tenshu.sync

import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The head-less **slice-snapshot** maintenance pass (`sync-device-catch-up.md` §5.7, C8): seal
 * each registered service's relation slices onto the content plane so a *joining* device can
 * render its first screen from one fetch instead of replaying history.
 *
 * ⚠⚠ **Never correctness-bearing, and that is what earns it the idle+charging schedule.** Every
 * device that reads a slice already falls back to an ordinary relation read when one is missing,
 * stale or unreadable — so a pass that never runs costs a joiner time, never data. That is the
 * same posture compaction takes (§8.3), and it is why this can be scheduled on constraints that
 * may not be met for days.
 *
 * ⚠ The work is done *for other devices*. The device running this pass gains nothing from it,
 * which is exactly why it must not compete with anything the user is waiting for.
 *
 * Carries [HeadlessSafe] (fail-closed: `ScheduleService` refuses to schedule an op without it,
 * and `ScheduleDrainLoop` re-checks before dispatch). Never called from a client — only the
 * scheduler dispatches it by id — so it needs no receiver, params, or client RO, mirroring
 * [DrainSync] and `org.tenshu.core.file.system.OfflineKeeper`.
 */
object WriteSliceSnapshots {

    const val ID = "${SyncService.ID}/writeSliceSnapshots"

    val Schema: OperationSchema = operation(
        id = ID,
        traits = listOf(
            IoOperation.Schema,
            TerminalOperation.Schema,
            HeadlessSafe.Schema,
        ),
    )
}
