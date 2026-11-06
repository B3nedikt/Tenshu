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

suspend fun SyncService.setUpBiometricUnlock(wrapKey: ByteArray): TResult<TBoolean> =
    findOperation<SetUpBiometricUnlock>(SetUpBiometricUnlock.ID)
        .setUpBiometricUnlock(wrapKey)
        .asTaskInteractor<TBoolean>()
        .execute()

/**
 * Biometric counterpart to [SetUpLocalUnlock] per the tech-debt entry
 * "Android Biometric Unlock — Missing, Undermines the Onboarding-Low-
 * Friction UX" + proposal §4.1. Wraps the device's recovery phrase
 * under [wrapKey] — a 32-byte AES key the UI biometric-protects
 * separately via the platform `BiometricUnlockDelegate` (Android:
 * AndroidKeyStore + `BiometricPrompt`). Coexists with the passphrase
 * wrapper; see `SyncManager.setUpBiometricUnlock` KDoc for the
 * coexistence + plaintext-deletion contract.
 *
 * Returns `Success(true)` on success, `Success(false)` if no recovery
 * phrase is in memory (caller must initialize first via createRealm /
 * recoveryClaim / pairing).
 */
interface SetUpBiometricUnlock : Operation {

    fun setUpBiometricUnlock(wrapKey: ByteArray): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/setUpBiometricUnlock"

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
