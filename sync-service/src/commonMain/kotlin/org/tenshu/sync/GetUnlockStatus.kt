package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun SyncService.getUnlockStatus(): TResult<UnlockStatus> =
    findOperation<GetUnlockStatus>(GetUnlockStatus.ID)
        .getUnlockStatus()
        .asTaskInteractor<UnlockStatus>()
        .execute()

/**
 * Reads the local-unlock primitive's state per
 * `docs/proposals/control-onboarding-ux.md` §2. The landing-state
 * router calls this on app launch to decide between Active / Locked.
 */
interface GetUnlockStatus : Operation {

    fun getUnlockStatus(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/getUnlockStatus"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {},
            returnType = UnlockStatus.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
