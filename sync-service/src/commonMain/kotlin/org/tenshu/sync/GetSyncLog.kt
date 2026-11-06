package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Pulls one page of the **merged sync log** — blocks and tombstones together, under one
 * cursor (`sync-lane-cursor-split.md` Option C / L3).
 *
 * Supersedes the [GetChanges] + [GetTombstones] pair: it carries the same signed HLC tip
 * and returns the same peer tips, so a caller on this op needs neither of the other two.
 *
 * ## ⚠ A new op id, not a widened [GetChanges]
 *
 * The platform rule for a changed operation contract is a **new op id** (the
 * `RenewTaskLease` precedent, schema-evolution §"Ops"): callstack params are
 * client-serialised positional composites, so widening an existing op's params or return
 * type garbles peers built against the old shape. A new id is additive — a peer that never
 * calls it cannot be affected — which is what keeps this slice free of a
 * `TENSHU_STORAGE_FORMAT_VERSION` bump.
 *
 * ## ⚠ The relay may not implement it, and that is a supported state
 *
 * `/sync/log` is a purely additive route, so an older relay answers it with an unmatched-
 * route 404 rather than an error. `RestSyncRelay` maps that to
 * [org.tenshu.sync.common.SyncRelay.SyncLogFetch.RelayUnsupported] and `SyncScheduler`
 * falls back to the two-lane path for the rest of the process' life. That is the whole
 * reason no `RELAY_PROTOCOL_VERSION` bump is owed and no coordinated redeploy is needed —
 * see `RelayProtocol.kt`, "Purely additive routes do NOT need a bump".
 */
suspend fun SyncService.getSyncLog(
    sinceTimestamp: Long,
    tip: SignedHlcTip
): TResult<SyncLogResponse> {
    val operation = findOperation<GetSyncLog>(GetSyncLog.ID)
    return operation.getSyncLog(sinceTimestamp, tip).asTaskInteractor<SyncLogResponse>().execute()
}

interface GetSyncLog : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getSyncLog(sinceTimestamp: Long, tip: SignedHlcTip): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/getSyncLog"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            traits = listOf(TerminalOperation.Schema, IoOperation.Schema),
            params = {
                int(Params.SINCE_TIMESTAMP)
                composite(Params.TIP, type = SignedHlcTip.Schema)
            },
            returnType = SyncLogResponse.Schema.toType()
        )
    }

    object Params {
        const val SINCE_TIMESTAMP = "$ID/sinceTimestamp"
        const val TIP = "$ID/tip"
    }
}
