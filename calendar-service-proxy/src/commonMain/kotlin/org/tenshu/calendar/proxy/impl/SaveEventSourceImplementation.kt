package org.tenshu.calendar.proxy.impl

import org.tenshu.calendar.EventSource
import org.tenshu.calendar.SaveEventSource
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read

/** Upserts the lossless iCal sidecar for an event (CA-6). */
internal class SaveEventSourceImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val eventId: TString = params.read(SaveEventSource.Params.EVENT_ID)
        val raw: TString = params.read(SaveEventSource.Params.RAW)
        val origin: String? = params.optional(SaveEventSource.Params.ORIGIN)

        executionContext.saveData(
            EventSource.create(
                context = executionContext.context,
                eventId = eventId.get(),
                raw = raw.get(),
                origin = origin.orEmpty(),
            )
        )
        return null as T
    }
}
