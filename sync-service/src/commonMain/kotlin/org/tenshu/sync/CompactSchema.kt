package org.tenshu.sync

import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Re-emit current state for every service whose history still trails its current
 * version (E4-5, `core-schema-evolution.md` §8.3) — the maintenance pass the
 * scheduler runs.
 *
 * ⚖️ **Never correctness-bearing, by design.** §8.3 puts compaction under
 * idle + charging + unmetered constraints precisely because nothing may depend on
 * it having run: OEM throttling (the ColorOS class) can only *delay* a floor
 * retirement, never break a read. If this op never fires, history simply stays
 * readable through its upcasters for longer.
 *
 * Carries [HeadlessSafe] because `ScheduleService` fail-closed refuses to
 * schedule anything else (§6.4) — and it genuinely is: the pass needs no UI, no
 * user decision, and takes no input.
 */
object CompactSchema {

    const val ID = "${SyncService.ID}/compactSchema"

    /** The scheduler's dedup key — one standing maintenance item, never a queue. */
    const val DEDUP_KEY = "tenshu/sync/schema-compaction"

    val Schema: OperationSchema = operation(
        id = ID,
        traits = listOf(
            IoOperation.Schema,
            TerminalOperation.Schema,
            HeadlessSafe.Schema,
        ),
    )
}
