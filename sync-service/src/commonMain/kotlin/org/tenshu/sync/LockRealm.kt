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

/**
 * Re-seals the realm: clears the in-memory derived keys / master key / realm
 * encryption key / recovery phrase so [getUnlockStatus]'s `isLocked` becomes
 * true again, leaving the persisted unlock wrapper (and the device signing key)
 * untouched. The inverse of [UnlockLocal] — a user-initiated "Lock now". The
 * device stays a realm member; only this session's key material is purged from
 * memory, so the next read of the realm goes back through the LockedScreen.
 *
 * Always returns `Success(true)` — locking can't fail (it only clears memory).
 */
suspend fun SyncService.lockRealm(): TResult<TBoolean> =
    findOperation<LockRealm>(LockRealm.ID)
        .lockRealm()
        .asTaskInteractor<TBoolean>()
        .execute()

interface LockRealm : Operation {

    override val schema get() = Schema

    fun lockRealm(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/lockRealm"
        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
