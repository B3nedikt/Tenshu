package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Stores the verbatim `VEVENT` an [Event] was imported from (CA-6).
 *
 * ⚠⚠ **[EventSource] shipped in CA-1 with a reader, a cascade-delete and no
 * writer.** The type, its `DataIdProvider`, its DTO/Ro and its service relation
 * all existed; nothing anywhere could create a row. This op is that writer, and
 * it lands with the import that needs it rather than earlier — a sidecar with no
 * importer would have had nothing to hold.
 *
 * ⚠ Keyed on [eventId], so re-importing a file the user already has replaces the
 * sidecar rather than accumulating copies — the same last-writer-wins upsert
 * every other `save*` op here is.
 *
 * ⚠ No validation that the event exists, matching `SaveReminder`: the two rows
 * sync independently, and requiring an order between them would invent a race.
 */
suspend fun CalendarService.saveEventSource(
    eventId: String,
    raw: String,
    origin: String = "",
): TResult<Data?> =
    findOperation<SaveEventSource>(SaveEventSource.ID)
        .saveEventSource(eventId, raw, origin)
        .asTaskInteractor<Data?>()
        .execute()

interface SaveEventSource : Operation {

    override val schema: OperationSchema get() = Schema

    fun saveEventSource(eventId: String, raw: String, origin: String): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/saveEventSource"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.EVENT_ID)
                string(id = Params.RAW)
                string(id = Params.ORIGIN, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val EVENT_ID = "$ID/eventId"
        const val RAW = "$ID/raw"
        const val ORIGIN = "$ID/origin"
    }
}
