package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun SyncService.clearBiometricSetup(): TResult<TBoolean> =
    findOperation<ClearBiometricSetup>(ClearBiometricSetup.ID)
        .clearBiometricSetup()
        .asTaskInteractor<TBoolean>()
        .execute()

/**
 * Wipes the persisted biometric unlock wrapper. Used when the user
 * disables biometric unlock from Settings, or when the platform
 * delegate's blob has been invalidated (e.g. by a biometric
 * re-enrollment that tripped `setInvalidatedByBiometricEnrollment(true)`
 * on the Keystore key) and the UI wants to start fresh. Idempotent;
 * the passphrase wrapper (if any) is untouched.
 */
interface ClearBiometricSetup : Operation {

    fun clearBiometricSetup(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/clearBiometricSetup"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = { },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
