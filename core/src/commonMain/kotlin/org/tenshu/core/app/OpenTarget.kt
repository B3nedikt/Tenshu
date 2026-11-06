package org.tenshu.core.app

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.entity.DataId
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Show [target] in whichever app owns its type (`control-cross-app-open.md`, TT-1a).
 *
 * ⚠⚠ **The address is a [DataId] — never an app, and never a call.** Which app opens a
 * `tenshu/calendar/event` is Control's resolution today and the *user's* choice once
 * `tenshu-rei.md` §2.2's primary handler lands, so a caller that named Calendar would be wrong the
 * moment someone swapped their calendar app (CO-D1). A *call* was the other near-miss and is worse:
 * it can return a list, it can span two services, and routing on one means parsing a program.
 *
 * ⭐ **One parameter, because [DataId] is a family** — `StringDataId` names a row by a single key,
 * `CompositeEntityDataId` by a multi-part one (a calendar occurrence is `[eventId, recurrenceId]`,
 * which has no stored row at all), and `EntityFieldDataId` names a part of a row. Every future
 * "which part of the thing" case is a variant question rather than a new protocol field (CO-D5).
 *
 * ⚠ Throws [OpenTargetUnavailable] rather than returning quietly. Once a row is tappable, a tap that
 * produces nothing and says nothing is a broken promise (CO-D7) — and the reasons describe the
 * **handler**, never the row, so this cannot be used to probe what exists (CO-D6).
 */
suspend fun AppService.openTarget(target: DataId): TResult<OpenOutcome> =
    findOperation<OpenTarget>(OpenTarget.ID)
        .openTarget(target)
        .asTaskInteractor<OpenOutcome>()
        .execute()

interface OpenTarget : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun openTarget(target: DataId): RequestObject

    companion object {
        const val ID = "tenshu/app/service/openTarget"

        val Schema = operation(
            id = ID,
            receiver = AppService.Schema.toType(),
            params = {
                // ⚠⚠ Typed as the FAMILY's parent, deliberately: a `CompositeEntityDataId` or an
                // `EntityFieldDataId` travels here unchanged. `EntityFieldDataId` already declares
                // a field this way (`parentEntity`, typed `DataId.Schema`), so the polymorphic
                // field is the shape core already relies on rather than one invented here.
                composite(id = Params.TARGET, type = DataId.Schema)
            },
            returnType = OpenOutcome.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val TARGET = "$ID/target"
    }
}
