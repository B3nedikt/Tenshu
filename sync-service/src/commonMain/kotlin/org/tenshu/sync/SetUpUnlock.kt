package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Atomic **passphrase-floor** unlock setup: wraps the device's recovery phrase
 * under [passphrase] (always) and, when [biometricWrapKey] is non-null, *also*
 * under that key — in a single call, so the plaintext is read once and cleared
 * once. The mandatory passphrase is the structural floor: biometric unlock can
 * never exist without a recovery passphrase (the way back in if the biometric
 * credential is ever lost). See `SyncManager.setUpUnlock`.
 *
 * Why one op rather than `setUpLocalUnlock` + `setUpBiometricUnlock`: the former
 * clears the plaintext, which would starve the latter. First-time setup goes
 * through here; the standalone `setUpBiometricUnlock` is the Settings
 * "enable biometric later" path (it requires a passphrase wrapper to exist).
 *
 * Wire shape: the wrap key is always sent as a byte array; an **empty** array
 * means "passphrase only" (avoids an optional wire param). Returns
 * `Success(false)` when no recovery phrase is in memory (caller must initialize
 * first via createRealm / recoveryClaim / pairing).
 */
suspend fun SyncService.setUpUnlock(
    passphrase: String,
    biometricWrapKey: ByteArray?,
): TResult<TBoolean> =
    findOperation<SetUpUnlock>(SetUpUnlock.ID)
        .setUpUnlock(passphrase, biometricWrapKey ?: ByteArray(0))
        .asTaskInteractor<TBoolean>()
        .execute()

interface SetUpUnlock : Operation {

    /** [biometricWrapKey] empty ⇒ passphrase-only (see file KDoc on the wire shape). */
    fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/setUpUnlock"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.PASSPHRASE)
                byteArray(Params.BIOMETRIC_WRAP_KEY)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val PASSPHRASE = "$ID/passphrase"
        const val BIOMETRIC_WRAP_KEY = "$ID/biometricWrapKey"
    }
}
