package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Close the content stream [handle] opened by [OpenContentStream] and release its
 * decrypted-chunk buffers (D2b, `drive-streaming-preview.md` §3.5). Idempotent — closing an
 * unknown/already-closed handle is a no-op. Called on player teardown (`onCleared`).
 */
suspend fun SyncService.closeContentStream(handle: String): TResult<Data?> =
    findOperation<CloseContentStream>(CloseContentStream.ID)
        .closeContentStream(handle)
        .asTaskInteractor<Data?>()
        .execute()

interface CloseContentStream : Operation {

    fun closeContentStream(handle: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/closeContentStream"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.HANDLE)
            },
            returnType = Data.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val HANDLE = "$ID/handle"
    }
}
