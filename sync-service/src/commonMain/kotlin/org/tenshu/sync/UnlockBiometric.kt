package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun SyncService.unlockBiometric(wrapKey: ByteArray): TResult<TBoolean> =
    findOperation<UnlockBiometric>(UnlockBiometric.ID)
        .unlockBiometric(wrapKey)
        .asTaskInteractor<TBoolean>()
        .execute()

/**
 * Decrypts the persisted biometric wrapper with [wrapKey] and loads
 * the recovery phrase + master keys back into the SyncManager. The
 * caller obtains [wrapKey] by running the UI-side
 * `BiometricUnlockDelegate.unwrapWrapKey()` path (BiometricPrompt →
 * Keystore-backed Cipher).
 *
 * Returns `Success(true)` on a successful unlock, `Success(false)`
 * when no biometric wrapper is persisted or the wrap-key doesn't
 * decrypt it.
 */
interface UnlockBiometric : Operation {

    fun unlockBiometric(wrapKey: ByteArray): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/unlockBiometric"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                byteArray(Params.WRAP_KEY)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val WRAP_KEY = "$ID/wrapKey"
    }
}
