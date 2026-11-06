package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TaskHandle
import org.tenshu.core.asCancellableTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.BackgroundOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Long-running requester-side pairing wait (Device B) — the §7.5 Phase L2
 * shape (`docs/proposals/core-background-processing.md`). Replaces the UI's
 * repeated `GetPairingStatus` poll loop + the separate `CancelPairing` lever
 * with a single cancellable Layer-2 task: it loops the shared
 * `PairingCompletion` in-proxy until the encrypted master key arrives (or the
 * ~5-min budget elapses), and cancelling the returned [TaskHandle] tears down
 * the in-proxy handshake. Carries [BackgroundOperation] (no `IoOperation`) so
 * it resolves to a `CancellableTaskInteractor`; uniform cross-process
 * (Drive↔Control) and in-process (JS SDK) now the in-process carve-out is
 * closed. `GetPairingStatus` stays for poll-once / back-compat callers.
 */
suspend fun SyncService.awaitPairing(
    pairingSecret: String,
    otherDevicePublicKey: ByteArray
): TaskHandle<TBoolean> =
    findOperation<AwaitPairing>(AwaitPairing.ID)
        .awaitPairing(pairingSecret, otherDevicePublicKey)
        .asCancellableTaskInteractor<TBoolean>()
        .execute()

interface AwaitPairing : Operation {

    override val schema get() = Schema

    fun awaitPairing(
        pairingSecret: String,
        otherDevicePublicKey: ByteArray
    ): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/awaitPairing"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.PAIRING_SECRET)
                byteArray(Params.OTHER_DEVICE_PUBLIC_KEY)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(TerminalOperation.Schema, BackgroundOperation.Schema)
        )
    }

    object Params {
        const val PAIRING_SECRET = "$ID/pairingSecret"
        const val OTHER_DEVICE_PUBLIC_KEY = "$ID/otherDevicePublicKey"
    }
}
