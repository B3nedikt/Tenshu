package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Upserts a [CalendarMirror] (`calendar-external-sources.md` CX-1b).
 *
 * ⚠ The whole row, every time — no "absent means keep". A mirror is written by one device about
 * itself, so the caller always holds the full row, and an optional field would only add a way to
 * write half of one. Rebinding to another device is this op with that device's id.
 *
 * ⚠⚠ **Never for routine bookkeeping.** Every save of this row is a newer version of it, and a newer
 * version beats a stop made elsewhere (`docs/sync/design.md` §2.6) — so only a user's own choice
 * (start, rebind, the reminder switch) may call this. A refresh's freshness goes through
 * `SaveCalendarMirrorStamp` (P2-a, v4).
 */
suspend fun CalendarService.saveCalendarMirror(
    mirrorId: String,
    calendarId: String,
    deviceId: String,
    sourceKind: String,
    sourceKey: String,
    sourceLabel: String,
    remindersEnabled: Boolean,
): TResult<Data?> =
    findOperation<SaveCalendarMirror>(SaveCalendarMirror.ID)
        .saveCalendarMirror(
            mirrorId, calendarId, deviceId, sourceKind, sourceKey, sourceLabel,
            remindersEnabled,
        )
        .asTaskInteractor<Data?>()
        .execute()

interface SaveCalendarMirror : Operation {

    override val schema: OperationSchema get() = Schema

    @Suppress("LongParameterList")
    fun saveCalendarMirror(
        mirrorId: String,
        calendarId: String,
        deviceId: String,
        sourceKind: String,
        sourceKey: String,
        sourceLabel: String,
        remindersEnabled: Boolean,
    ): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/saveCalendarMirror"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.MIRROR_ID)
                string(id = Params.CALENDAR_ID)
                string(id = Params.DEVICE_ID)
                string(id = Params.SOURCE_KIND)
                string(id = Params.SOURCE_KEY)
                string(id = Params.SOURCE_LABEL)
                boolean(id = Params.REMINDERS_ENABLED)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val MIRROR_ID = "$ID/mirrorId"
        const val CALENDAR_ID = "$ID/calendarId"
        const val DEVICE_ID = "$ID/deviceId"
        const val SOURCE_KIND = "$ID/sourceKind"
        const val SOURCE_KEY = "$ID/sourceKey"
        const val SOURCE_LABEL = "$ID/sourceLabel"
        const val REMINDERS_ENABLED = "$ID/remindersEnabled"
    }
}
