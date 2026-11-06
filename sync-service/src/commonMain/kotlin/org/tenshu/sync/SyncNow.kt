package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The **user-initiated** "Sync now over cellular" override (sub-slice A,
 * `sync-metered-deferred-status.md` §2.3). When the sync cycle is deferred by the
 * user's own `WifiOnly` policy on a metered network (the deferred banner), this drains
 * the waiting backlog **once** past that policy — the escape hatch the user tapped.
 *
 * Delegates to the live [org.tenshu.sync.proxy.SyncScheduler]'s `forceDrainOnce()`: it
 * sets a single-use flag and wakes the loop, so the next tick runs one full drain even
 * though the metered gate is closed. Fire-and-forget — the op returns once the drain is
 * *signalled*; the banner clears as the backlog actually drains.
 *
 * **Not** [org.tenshu.core.trait.HeadlessSafe]: unlike `DrainSync` (which runs headless,
 * honouring the gate), this deliberately overrides a user policy and therefore requires a
 * user present to have requested it. It is the client-callable sibling of `DrainSync`.
 * The `storageGate` / entitlement gates inside the drain still apply — this bypasses the
 * network-policy gate only.
 */
suspend fun SyncService.syncNow(): TResult<Data?> =
    findOperation<SyncNow>(SyncNow.ID)
        .syncNow()
        .asTaskInteractor<Data?>()
        .execute()

interface SyncNow : Operation {

    fun syncNow(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/syncNow"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = { },
            returnType = Data.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }
}
