package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Discard **this** device's realm identity — the platform-agnostic twin of iOS's
 * `ControlSurface.wipeDeviceState`, so desktop and Android stop needing `rm -rf` on a data
 * directory to leave a realm.
 *
 * ## Why this exists
 *
 * iOS has "Forget this device" on `LockedView`; desktop and Android have nothing
 * (`admission-revocation-names-the-capability.md` §5b.12, link 4). That gap is not cosmetic:
 *
 * * ⚠⚠ **It is the only way to get a new `deviceId`.** Under option F a removed identity never
 *   returns, so rejoining requires a *fresh* identity. On Android and desktop the user's only route
 *   today is clearing app data — which on desktop means deleting `~/.tenshu/<app>/` by hand.
 * * ⚠ **`discardSupersededRealm` is not this.** That fires only when joining a *different* realm
 *   (`priorOwnerId != ownerId`) and is a side effect of pairing, never a thing a user can ask for.
 *
 * ## What it does, in an order that is load-bearing
 *
 * 1. **Retire this device's row first** — `RevokeDevice` has no self-guard by design, so a device
 *    can retire itself while it still holds the keys to prove it. ⚠⚠ Then *wait* for the tombstone
 *    to actually leave: `TombstoneEmitter.emit` only appends to `PENDING_TOMBSTONES`, and the push
 *    is signed with the very A4 key step 3 deletes. A tombstone still queued when the key goes can
 *    never be pushed, and the wipe manufactures the orphaned `Device` row it was meant to prevent.
 * 2. **Purge the outbound queues** — anything staged to send is addressed to a realm this device is
 *    leaving. Between 1 and 3, because minting an `ExecutionContext` needs a session and the custody
 *    keys are about to go.
 * 3. **Purge the custody bucket** — the identity itself.
 *
 * ⚠ A failed retirement (offline, relay down) must **not** block the wipe: people forget a device
 * precisely when something is broken. The outcome string says so, and the leftover row becomes an
 * announced result with a named remedy rather than a silent one.
 *
 * ## ⚠⚠ What the CALLER owes
 *
 * This op destroys A1 material when the device holds it. Per §5b.12.2 the UI must route that case
 * through the **recovery-phrase reveal** — never a block, since a broken or sold device must still
 * be forgettable, but never silently either: this may be the realm's only copy, and nothing else
 * will tell the user before it is gone.
 *
 * Returns a human-readable outcome describing what actually happened, not a bare success.
 */
suspend fun SyncService.forgetThisDevice(): TResult<TString> =
    findOperation<ForgetThisDevice>(ForgetThisDevice.ID)
        .forgetThisDevice()
        .asTaskInteractor<TString>()
        .execute()

interface ForgetThisDevice : Operation {

    fun forgetThisDevice(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/forgetThisDevice"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {},
            returnType = Data.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
