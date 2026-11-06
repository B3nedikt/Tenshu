package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun SyncService.unlockLocal(passphrase: String): TResult<TBoolean> =
    findOperation<UnlockLocal>(UnlockLocal.ID)
        .unlockLocal(passphrase)
        .asTaskInteractor<TBoolean>()
        .execute()

/**
 * Decrypts the persisted unlock wrapper with [passphrase] and loads
 * the recovery phrase + master keys back into the SyncManager. Per
 * `docs/proposals/control-onboarding-ux.md` §3.2 — the device-locked
 * flow's verification step.
 *
 * Returns `Success(true)` on a successful unlock, `Success(false)`
 * when the passphrase doesn't decrypt the wrapper (wrong passphrase
 * or no wrapper persisted).
 */
interface UnlockLocal : Operation {

    fun unlockLocal(passphrase: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/unlockLocal"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.PASSPHRASE)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PASSPHRASE = "$ID/passphrase"
    }
}
