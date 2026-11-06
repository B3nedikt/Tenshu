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

suspend fun SyncService.relaxToHeadless(): TResult<TBoolean> =
    findOperation<RelaxToHeadless>(RelaxToHeadless.ID)
        .relaxToHeadless()
        .asTaskInteractor<TBoolean>()
        .execute()

/**
 * Turns the **"Encrypt on unlock only"** hard mode back off
 * (`docs/proposals/control-keep-device-unlocked.md` §4.2, toggle 2 — the inverse
 * of [SetUpUnlock]). Re-persists the plaintext A1 payload from the currently
 * *unlocked* session (the unlock that opened this session already re-seeded it,
 * per `SyncManager.setUpUnlock` / `applyDecodedPayload`) and deletes the
 * passphrase + biometric wrappers, so the next cold start autoloads the realm
 * **headless** again and background sync resumes without an app-level unlock.
 *
 * Refuses (`Success(false)`) when the realm is **locked** — with no in-memory
 * plaintext to fall back on, dropping the wrappers would strand the realm with no
 * way back in. The caller must be inside an unlocked session (Settings is, by
 * construction, behind the unlock). The biometric Keystore key is cleared
 * separately by the UI's `BiometricUnlockDelegate`.
 */
interface RelaxToHeadless : Operation {

    fun relaxToHeadless(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/relaxToHeadless"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = { },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
