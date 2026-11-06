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
 * Pulls one page of the merged log walking **backwards** from [Params.BEFORE_SEQ], newest first —
 * the catch-up direction (`sync-device-catch-up.md` C2 / §5.4).
 *
 * Ascending replay shows a rebuilding device July before it shows yesterday. Descending, recent
 * state arrives first and the device is useful in seconds rather than hours. Matrix's `prev_batch`
 * is the same idea, and is why Matrix never needed an index over its log.
 *
 * ## ⚠ A new op id rather than a `before` parameter on [GetSyncLog]
 *
 * Callstack params are client-serialised **positional** composites, so adding a parameter to an
 * existing op garbles every peer built against the old shape (schema-evolution §2). The platform
 * rule for a changed operation contract is therefore a new id — the `RenewTaskLease` precedent —
 * and that is also what keeps this slice free of a `TENSHU_STORAGE_FORMAT_VERSION` bump.
 *
 * ⚠ It carries the signed HLC tip like [GetSyncLog] even though a backward read has no use for the
 * piggyback. The relay route authenticates *through* the tip (self-attribution + signature), so
 * dropping it would mean a second auth shape on one route — more surface, for nothing.
 *
 * The returned [SyncLogResponse] is the same shape as the forward one: `nextCursor` is the value to
 * pass back **in the direction you asked** (here, the page's lowest seq) and `hasMore` means more
 * exists that way.
 */
suspend fun SyncService.getSyncLogBefore(
    beforeSeq: Long,
    tip: SignedHlcTip
): TResult<SyncLogResponse> {
    val operation = findOperation<GetSyncLogBefore>(GetSyncLogBefore.ID)
    return operation.getSyncLogBefore(beforeSeq, tip)
        .asTaskInteractor<SyncLogResponse>()
        .execute()
}

interface GetSyncLogBefore : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getSyncLogBefore(beforeSeq: Long, tip: SignedHlcTip): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/getSyncLogBefore"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            traits = listOf(TerminalOperation.Schema, IoOperation.Schema),
            params = {
                int(Params.BEFORE_SEQ)
                composite(Params.TIP, type = SignedHlcTip.Schema)
            },
            returnType = SyncLogResponse.Schema.toType()
        )
    }

    object Params {
        /** Exclusive upper bound: serve the highest entries with `seq < beforeSeq`. */
        const val BEFORE_SEQ = "$ID/beforeSeq"
        const val TIP = "$ID/tip"
    }
}
