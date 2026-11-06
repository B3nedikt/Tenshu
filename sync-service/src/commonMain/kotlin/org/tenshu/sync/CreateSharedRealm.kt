package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Create a new **shared realm** (a shared folder is a realm of its own —
 * `docs/proposals/sync-multi-realm-runtime.md` §3 / `tenshu-rei.md` §2.3) and
 * self-join the calling device to it. Wraps `SharedRealmBootstrapper`:
 *
 *   1. Generate the realm's Ed25519 identity + a random 32-byte master key (A12, `Kr`);
 *      derive the realm `ownerId`; `POST /sync/realm`.
 *   2. Wrap `Kr` to this device's own A13 and save a `SharedRealmMembership(OWNER)`
 *      (`Syncable(Eager)` on the personal realm — propagates to the user's devices).
 *   3. Register the shared realm into the device's `RealmRegistry`.
 *
 * Returns the new shared realm's `ownerId` (as a `TString`) on success. **Requires
 * the recovery phrase to be available** (the realm must be unlocked) — fails
 * otherwise, since step 2 derives A13 from the phrase. Cross-device convergence
 * (other devices auto-joining and syncing the shared stream) additionally needs the
 * SR-3b.4 prerequisites — A13-to-joiners delivery + relay device admission — see the
 * proposal's "Cross-device prerequisites" section.
 */
suspend fun SyncService.createSharedRealm(): TResult<Data?> =
    findOperation<CreateSharedRealm>(CreateSharedRealm.ID)
        .createSharedRealm()
        .asTaskInteractor<Data?>()
        .execute()

interface CreateSharedRealm : Operation {

    fun createSharedRealm(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/createSharedRealm"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = { },
            returnType = Data.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
