package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.composite

/**
 * Functional interface for a pending asynchronous operation call. Phase 4 of
 * `docs/proposals/core-error-channel.md`: the call returns `Data` directly and
 * throws a [org.tenshu.core.TenshuException] on typed failure; the
 * `LocalTaskExecutor` catch-all at the wire boundary turns that into a
 * `TResult.Failure` for the reply payload.
 */
fun interface AsyncCall {
    suspend fun await(): Data?
}

/**
 * A marker object returned by evaluateCallstack when it encounters an operation that
 * needs asynchronous execution.
 */
interface AsyncReference : Data {
    val asyncCall: AsyncCall
    val remainingCallstack: List<Data>

    companion object {
        const val ID = "tenshu/core/proxy/asyncReference"
        val Schema: CompositeSchema = composite(id = ID)

        fun create(
            asyncCall: AsyncCall,
            remainingCallstack: List<Data>
        ): AsyncReference = AsyncReferenceDto(asyncCall, remainingCallstack)
    }
}

internal class AsyncReferenceDto(
    override val asyncCall: AsyncCall,
    override val remainingCallstack: List<Data>
) : AsyncReference {
    override val schema: DataSchema get() = AsyncReference.Schema
}
