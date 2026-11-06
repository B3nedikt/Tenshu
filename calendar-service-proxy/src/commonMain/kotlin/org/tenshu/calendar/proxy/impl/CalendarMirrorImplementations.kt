package org.tenshu.calendar.proxy.impl

import org.tenshu.calendar.CalendarMirror
import org.tenshu.calendar.CalendarMirrorStamp
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.DeleteCalendarMirror
import org.tenshu.calendar.SaveCalendarMirror
import org.tenshu.calendar.SaveCalendarMirrorStamp
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.TInteger
import org.tenshu.sync.common.TombstoneEmitter

/** Upserts a [CalendarMirror] whole (CX-1b); see `SaveCalendarMirror`. */
internal class SaveCalendarMirrorImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val string = { id: String -> params.read<TString>(id).get() }
        executionContext.saveData(
            CalendarMirror.create(
                context = executionContext.context,
                mirrorId = string(SaveCalendarMirror.Params.MIRROR_ID),
                calendarId = string(SaveCalendarMirror.Params.CALENDAR_ID),
                deviceId = string(SaveCalendarMirror.Params.DEVICE_ID),
                sourceKind = string(SaveCalendarMirror.Params.SOURCE_KIND),
                sourceKey = string(SaveCalendarMirror.Params.SOURCE_KEY),
                sourceLabel = string(SaveCalendarMirror.Params.SOURCE_LABEL),
                remindersEnabled = params.read<TBoolean>(SaveCalendarMirror.Params.REMINDERS_ENABLED).get(),
            )
        )
        return null as T
    }
}

/**
 * Writes one device's [CalendarMirrorStamp] (P2-a) — and never the mirror row, which is the point:
 * see `SaveCalendarMirrorStamp`.
 */
internal class SaveCalendarMirrorStampImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        executionContext.saveData(
            CalendarMirrorStamp.create(
                context = executionContext.context,
                mirrorId = params.read<TString>(SaveCalendarMirrorStamp.Params.MIRROR_ID).get(),
                deviceId = params.read<TString>(SaveCalendarMirrorStamp.Params.DEVICE_ID).get(),
                lastRefreshedEpochMs =
                    params.read<TInteger>(SaveCalendarMirrorStamp.Params.LAST_REFRESHED_EPOCH_MS).get(),
            )
        )
        return null as T
    }
}

/**
 * Removes the mirror row (and its stamps) and **nothing else** — the calendar and its events stay,
 * as an ordinary calendar. See `DeleteCalendarMirror` for why this is the opposite of
 * `DeleteSubscription`.
 *
 * ⚠⚠ With a tombstone, or the stop never leaves this device (P2-a): see [deleteMirrorRow].
 */
internal class DeleteCalendarMirrorImplementation(
    private val tombstoneEmitter: TombstoneEmitter? = null,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val mirrorId: TString = params.read(DeleteCalendarMirror.Params.MIRROR_ID)
        executionContext.deleteMirrorRow(mirrorId.get(), tombstoneEmitter)
        return null as T
    }
}

/**
 * Deletes a mirror row and its devices' stamps, each with a tombstone.
 *
 * ⚠⚠ The tombstone is the stop. `SyncObserver.onDataDeleted` is a deliberate no-op, so without it
 * *Stop syncing* on any device but the bound one never reached the bound phone, which kept the
 * mirror and re-sent the row at its next write — the stop undone within the hour (P2-a,
 * `CalendarMirrorStopSystemTest`). With it, receivers drop any block for the row at an HLC at or
 * below the stop's; what the bound phone may still write after it is its own stamp row, which carries
 * nothing of the mirror (`CalendarMirrorStamp`).
 *
 * ⚠ The stamps go too, as tidiness rather than safety: a stale bound phone may stamp once more
 * before it hears of the stop, leaving an orphan nothing reads (a stamp is only read beside its
 * mirror), and a restarted mirror writes its own device's stamp first.
 */
internal fun ExecutionContext.deleteMirrorRow(mirrorId: String, tombstoneEmitter: TombstoneEmitter?) {
    deleteRow(CalendarMirror.ID, mirrorId, tombstoneEmitter)
    allMirrorStamps()
        .filter { it.mirrorId.get() == mirrorId }
        .forEach { deleteRow(CalendarMirrorStamp.ID, it.stampId.get(), tombstoneEmitter) }
}

/** Every locally-known [CalendarMirrorStamp]; a full scan, like [allMirrors]. */
internal fun ExecutionContext.allMirrorStamps(): List<CalendarMirrorStamp> {
    val arrayRef = resolveServiceData(
        service = ServiceTypeReferenceDto(CalendarService.ID),
        ref = EntityListReferenceDto(CalendarService.Relations.MIRROR_STAMPS),
    )
    return resolveList(arrayRef)?.filterIsInstance<CalendarMirrorStamp>()?.toList().orEmpty()
}

/** Every locally-known [CalendarMirror]; a full scan, like [allEvents]. */
internal fun ExecutionContext.allMirrors(): List<CalendarMirror> {
    val arrayRef = resolveServiceData(
        service = ServiceTypeReferenceDto(CalendarService.ID),
        ref = EntityListReferenceDto(CalendarService.Relations.MIRRORS),
    )
    return resolveList(arrayRef)?.filterIsInstance<CalendarMirror>()?.toList().orEmpty()
}
