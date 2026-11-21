package org.tenshu.inference.proxy

import kotlinx.coroutines.flow.StateFlow
import org.tenshu.core.Data
import org.tenshu.core.common.impl.StreamElementValueDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.inference.PeopleIndexChanges

/**
 * Serves the [PeopleIndexChanges] **stream** op (`photos-pets.md` §10.19): pushes [revision]'s
 * current value at subscribe, then every later one, for as long as the subscriber keeps the stream.
 *
 * ⚠⚠ Unlike [MediaIndexProgressImplementation] this never ends by itself — a change can come at any
 * time — so its feeder is bound to the stream id ([ExecutionContext.launchStreamJob]): the
 * subscriber's `EndStream`, a resubscribe of the same id, or the session's end cancels it. A plain
 * `sessionScope.launch` would leak one collector per subscribe for the life of the session.
 *
 * ⚠ [revision] is [MediaIndexRunner.faceChanges] — the one counter iOS Collections follows
 * in-process — so both platforms are told by the same writes.
 */
internal class PeopleIndexChangesImplementation(
    private val revision: StateFlow<Long>,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val streamId: Long = params.read<TInteger>(PeopleIndexChanges.Params.STREAM_ID).get()
        val context = executionContext.context
        val connection = executionContext.clientConnection

        executionContext.launchStreamJob(streamId) {
            // ⚠ A `StateFlow` replays its value, so the subscriber's baseline is the first element,
            // and it conflates: a burst of passes is one element with the latest count, never a lost one.
            revision.collect { value -> connection.send(StreamElementValueDto(streamId, value.toTInteger(context))) }
        }

        return null as T
    }
}
