package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Read up to [length] plaintext bytes at [offset] from the stream [handle] opened by
 * [OpenContentStream] (D2b, `drive-streaming-preview.md` §3.5). The proxy decrypts only the
 * chunk(s) covering the range (bounded memory) and returns them inline. Returns an empty array
 * at/after EOF; a typed failure if the handle is unknown/closed or a covering chunk fails its
 * per-chunk integrity gate.
 *
 * Callers keep [length] small (≤ ~256 KiB) so the inline `TByteArray` stays under the Binder
 * transaction cap — every platform binding (Android proxy-fd `onRead`, iOS resource-loader, web
 * Service-Worker `Range`) issues bounded reads.
 */
suspend fun SyncService.readContentRange(
    handle: String,
    offset: Long,
    length: Int,
): TResult<TByteArray> =
    findOperation<ReadContentRange>(ReadContentRange.ID)
        .readContentRange(handle, offset, length)
        .asTaskInteractor<TByteArray>()
        .execute()

interface ReadContentRange : Operation {

    fun readContentRange(handle: String, offset: Long, length: Int): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/readContentRange"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.HANDLE)
                int(Params.OFFSET)
                int(Params.LENGTH)
            },
            returnType = TByteArray.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val HANDLE = "$ID/handle"
        const val OFFSET = "$ID/offset"
        const val LENGTH = "$ID/length"
    }
}
