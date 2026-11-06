package org.tenshu.core.file.system

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.file.CapturedPhoto
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Every image file captured in `[startMs, endMs)`, best first, capped at [limit]
 * (`docs/proposals/timeline-photos.md` T-P2). The read behind Timeline's photo hero and, once T-P3
 * lands, its feed.
 *
 * **Why an op, not the chain DSL.** The DSL has no comparison predicate — `equalTo` and `contains`
 * are the only two — so a time *window* is not expressible in a chain at all, and the fallback
 * (`traverse()` then filter client-side) ships every file row in the realm across IPC to discard
 * almost all of them. Timeline did exactly that in T-P1, deliberately, to get one hero on screen
 * before committing to a shape. This op moves the scan to where the rows already are: the proxy
 * walks its own store and only the matches cross the wire.
 *
 * ⚠ It is still a **scan**, not an index — O(files in the realm) per call, in-process. That is the
 * honest trade for adding no stored rows, no backfill and no invalidation: a caller asks once for
 * the whole span it is about to render rather than once per row. A memoised index behind this
 * signature would be invisible to callers if it is ever worth building.
 *
 * **Ordering** puts a photograph you took ahead of an image that merely shares the minute:
 * [CapturedPhoto.backedUp] first, then newest capture time. So when [limit] truncates, it drops the
 * least-likely-to-be-wanted first rather than an arbitrary slice.
 *
 * ⚠ A file with no `File.CreatedAt` is **omitted**, not treated as epoch-zero: without a timestamp
 * it cannot be placed in time, and reading absence as `0` would file every such file under whichever
 * window happens to reach back that far.
 *
 * @param startMs inclusive lower bound, epoch millis
 * @param endMs exclusive upper bound, epoch millis
 * @param limit maximum results; `0` or less returns nothing rather than everything
 */
suspend fun FileSystem.photosCapturedBetween(
    startMs: Long,
    endMs: Long,
    limit: Int,
): TResult<TList<CapturedPhoto>> =
    findOperation<PhotosCapturedBetween>(PhotosCapturedBetween.ID)
        .photosCapturedBetween(startMs, endMs, limit)
        .asTaskInteractor<TList<CapturedPhoto>>()
        .execute()

interface PhotosCapturedBetween : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun photosCapturedBetween(startMs: Long, endMs: Long, limit: Int): RequestObject

    companion object {
        const val ID = "tenshu/file/system/photosCapturedBetween"

        val Schema = operation(
            id = ID,
            receiver = FileSystem.Schema.toType(),
            params = {
                int(id = Params.START_MS)
                int(id = Params.END_MS)
                int(id = Params.LIMIT)
            },
            returnType = listType(CapturedPhoto.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val START_MS = "$ID/startMs"
        const val END_MS = "$ID/endMs"
        const val LIMIT = "$ID/limit"
    }
}
