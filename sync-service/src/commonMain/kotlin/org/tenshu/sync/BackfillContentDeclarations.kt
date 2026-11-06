package org.tenshu.sync

import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The **content-declaration backfill** (`content-plane-refcount.md` §9.7, CR-4d): describe to the
 * relay every content object this realm already holds, so bytes uploaded before the declaration
 * seam existed stop being unreachable.
 *
 * ⚠⚠ **Earns its idle+charging schedule by cost, not by unimportance** — the opposite of
 * [WriteSliceSnapshots]. A ref is `sha256(ciphertext)`, so describing one content object means
 * fetching and decrypting its manifest: one `GET` per live content object. That is fine **once**
 * and ruinous per tick, which is why the pass carries a completion marker and this op carries the
 * constraints.
 *
 * ⚠ It frees nothing and can free nothing. It only *describes*, and declaring more is always the
 * safe direction — an edge protects content from becoming a candidate (§9.5), and an edge naming
 * bytes the relay does not have is inert.
 *
 * Carries [HeadlessSafe] (fail-closed: `ScheduleService` refuses to schedule an op without it).
 * Never called from a client — only the scheduler dispatches it by id — so it needs no receiver,
 * params or client RO, mirroring [WriteSliceSnapshots] and `DrainSync`.
 */
object BackfillContentDeclarations {

    const val ID = "${SyncService.ID}/backfillContentDeclarations"

    val Schema: OperationSchema = operation(
        id = ID,
        traits = listOf(
            IoOperation.Schema,
            TerminalOperation.Schema,
            HeadlessSafe.Schema,
        ),
    )
}
