package org.tenshu.core.file.system

import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The offline **keeper** (`docs/proposals/drive-offline-online.md` §3.2, the
 * background half). A param-less, self-driving background op that reconciles this
 * device's local file bytes with its sync rules: it walks the file tree and, for
 * each file whose effective policy is [org.tenshu.core.file.sync.SyncPolicy.KEEP_OFFLINE]
 * but whose bytes are **not** on local disk, proactively fetches them (the OnDemand
 * materialise path) — so `KEEP_OFFLINE` content that arrived via sync while the app
 * was closed is present the next time it's opened.
 *
 * **Three halves, one pass.** The download above, then two ceilings: the file cache's
 * (`retention-quota-model.md` Face B, off by default) and the stored thumbnail classes'
 * (`drive-xxlarge-thumbnail-rung.md` D-20, **on** at 1 GiB — those blobs are re-fetchable
 * derivatives nobody asked for by name). ⚠ Both evict coldest-first, and neither touches a
 * `KEEP_OFFLINE` file — nor, since the thumbnail half, that file's stored classes.
 *
 * **The first production `HeadlessSafe` op** — it runs with no user present, so
 * it carries [HeadlessSafe] (fail-closed: `ScheduleService` refuses to schedule
 * an op without it, and `ScheduleDrainLoop` re-checks before dispatch). Never
 * called from a client; only the scheduler dispatches it by id (in-process
 * `ScheduleDrainLoop`, or Android `WorkManager` when Control is closed), so it
 * needs no receiver, params, or client RO.
 */
object OfflineKeeper {

    const val ID = "tenshu/file/system/offlineKeeper"

    val Schema: OperationSchema = operation(
        id = ID,
        traits = listOf(
            IoOperation.Schema,
            TerminalOperation.Schema,
            HeadlessSafe.Schema,
        ),
    )
}
