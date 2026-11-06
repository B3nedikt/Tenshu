package org.tenshu.sync

import org.tenshu.core.TError
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

/**
 * [RecoveryClaim] refused: this device already belongs to a **different** realm than the one the
 * recovery phrase names.
 *
 * ⚠⚠ Raised before any relay call, so nothing is claimed, seated or overwritten — the device keeps
 * the realm it had (threat model T-RC-09). Recovering into another realm would otherwise leave the
 * first realm's custody (unlock wrapper, joiner payload, cursors) beside the second realm's keys.
 * The remedy is the user's: forget / disconnect this device, then recover.
 *
 * ⚠ **Same realm is not this error.** A device that is resident in the phrase's own realm — the
 * locked device whose user forgot the passphrase and took "Recover" from the lock screen — is
 * allowed through; that is the one caller that legitimately recovers while set up.
 *
 * ⚠ **Fieldless, no service bump.** A new composite cannot reach an older reader here: the only
 * callers of [RecoveryClaim] are Control (which *is* the proxy on Android/desktop), the iOS
 * route-1 runtime (in-process) and the web SDK (which bundles the proxy) — the caller and the
 * thrower are always one build. Bumping `SyncService` for an IPC-only error type fenced whole
 * realms last time (`core-version-compatibility.md` §1.3), so this deliberately does not.
 */
interface DeviceAlreadySetUpError : TError {
    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "${SyncService.ID}/error/deviceAlreadySetUp"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {},
        )

        fun create(context: TContext): DeviceAlreadySetUpError = context.create(
            type = ID,
            fields = emptyList(),
        )
    }
}
