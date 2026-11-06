package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Records when [deviceId] last confirmed a mirror matches its source — a [CalendarMirrorStamp]
 * (`calendar-external-sources.md` §11 P2-a).
 *
 * ⚠⚠ Writes the stamp row and **nothing else**: never the [CalendarMirror] row, which is how a
 * refresh on a phone that missed a stop used to bring the mirror back. `0` means "reconcile at the
 * next refresh, whatever the digest says".
 */
suspend fun CalendarService.saveCalendarMirrorStamp(
    mirrorId: String,
    deviceId: String,
    lastRefreshedEpochMs: Long,
): TResult<Data?> =
    findOperation<SaveCalendarMirrorStamp>(SaveCalendarMirrorStamp.ID)
        .saveCalendarMirrorStamp(mirrorId, deviceId, lastRefreshedEpochMs)
        .asTaskInteractor<Data?>()
        .execute()

interface SaveCalendarMirrorStamp : Operation {

    override val schema: OperationSchema get() = Schema

    fun saveCalendarMirrorStamp(mirrorId: String, deviceId: String, lastRefreshedEpochMs: Long): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/saveCalendarMirrorStamp"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.MIRROR_ID)
                string(id = Params.DEVICE_ID)
                int(id = Params.LAST_REFRESHED_EPOCH_MS)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val MIRROR_ID = "$ID/mirrorId"
        const val DEVICE_ID = "$ID/deviceId"
        const val LAST_REFRESHED_EPOCH_MS = "$ID/lastRefreshedEpochMs"
    }
}
