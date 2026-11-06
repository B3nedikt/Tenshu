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

suspend fun SyncService.setUpLocalUnlock(passphrase: String): TResult<TBoolean> =
    findOperation<SetUpLocalUnlock>(SetUpLocalUnlock.ID)
        .setUpLocalUnlock(passphrase)
        .asTaskInteractor<TBoolean>()
        .execute()

/**
 * Wraps the device's recovery phrase under a passphrase-derived key
 * and persists the resulting blob, replacing the legacy plaintext
 * `RECOVERY_PHRASE` slot. Per
 * `docs/proposals/control-onboarding-ux.md` §4b.5 / §4a.6 — the
 * device's first transition from "auto-loaded plaintext" to "wrapped
 * + must unlock" happens here.
 *
 * Returns `Success(true)` on success, `Success(false)` if no recovery
 * phrase is in memory (caller must initialize first via createRealm /
 * recoveryClaim / pairing).
 */
interface SetUpLocalUnlock : Operation {

    fun setUpLocalUnlock(passphrase: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/setUpLocalUnlock"

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
