package org.tenshu.routine.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.TInteger
import org.tenshu.routine.Routine
import org.tenshu.routine.SaveRoutine
import org.tenshu.routine.common.program.JsonRoutineProgramCodec
import org.tenshu.routine.common.program.ProgramParse

/**
 * Creates or replaces one [Routine] row on the caller's proxy.
 *
 * ⚠ **The program is validated here, at authoring time.** A routine whose
 * program cannot be parsed would otherwise become one that silently never runs
 * — the failure mode this whole design exists to avoid. A refusal is a typed
 * error the editor can show; silence is not.
 *
 * ⚠⚠ An [ProgramParse.UnsupportedVersion] on *save* is a different thing from
 * one on *execute*: on execute it means "a newer device authored this, stand
 * down" (RE-D10's fence); here it would mean this proxy was handed a program it
 * cannot even represent, which is a caller bug.
 */
internal class SaveRoutineImplementation(
    private val nowEpochMs: () -> Long,
    private val authorDeviceId: () -> String?,
) : SyncOperationImplementation {

    private val codec = JsonRoutineProgramCodec()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val routineId: TString = params.read(SaveRoutine.Params.ROUTINE_ID)
        val name: TString = params.read(SaveRoutine.Params.NAME)
        val timeZoneId: TString = params.read(SaveRoutine.Params.TIME_ZONE_ID)
        val programJson: TString = params.read(SaveRoutine.Params.PROGRAM_JSON)
        val programVersion: TInteger = params.read(SaveRoutine.Params.PROGRAM_VERSION)
        val homeLatE5: TInteger = params.read(SaveRoutine.Params.HOME_LAT_E5)
        val homeLonE5: TInteger = params.read(SaveRoutine.Params.HOME_LON_E5)

        val context = executionContext.context

        if (name.get().isBlank()) {
            throw TenshuException(GenericError.create(context, "A routine needs a name"))
        }

        when (val parse = codec.parse(programJson.get(), programVersion.get())) {
            is ProgramParse.Parsed -> Unit
            is ProgramParse.Malformed -> throw TenshuException(
                GenericError.create(context, "Invalid routine program: ${parse.reason}")
            )

            is ProgramParse.UnsupportedVersion -> throw TenshuException(
                GenericError.create(
                    context,
                    "Cannot author program version ${parse.version} on this device",
                )
            )
        }

        val existing = executionContext.database.getData(
            DataId.StringDataId.create(context = context, type = Routine.ID, keyValue = routineId.get())
        ) as? Routine

        val now = nowEpochMs()
        val saved = Routine.create(
            context = context,
            routineId = routineId.get(),
            name = name.get().trim(),
            // ⚠ An edit must not silently re-enable a routine the user turned
            // off, nor clear an engine suspension — SetRoutineEnabled owns both.
            enabled = existing?.enabled?.get() ?: true,
            state = existing?.state?.get() ?: Routine.STATE_ACTIVE,
            suspendedReason = existing?.suspendedReason?.get() ?: "",
            timeZoneId = timeZoneId.get(),
            homeLatE5 = homeLatE5.get(),
            homeLonE5 = homeLonE5.get(),
            authorDeviceId = existing?.authorDeviceId?.get() ?: authorDeviceId() ?: "",
            createdAtEpochMs = existing?.createdAtEpochMs?.get() ?: now,
            updatedAtEpochMs = now,
            programJson = programJson.get(),
            programVersion = programVersion.get(),
            extras = existing?.extras,
        )

        executionContext.saveData(saved)

        return null as T
    }
}
