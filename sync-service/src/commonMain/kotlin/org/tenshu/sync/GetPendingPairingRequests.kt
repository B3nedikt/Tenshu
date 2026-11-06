package org.tenshu.sync

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Extension for triggering the GetPendingPairingRequests operation.
 */
suspend fun SyncService.getPendingPairingRequests(): TResult<TList<PairingRequest>> =
    findOperation<GetPendingPairingRequests>(GetPendingPairingRequests.ID)
        .getPendingPairingRequests()
        .asTaskInteractor<TList<PairingRequest>>()
        .execute()

interface GetPendingPairingRequests : Operation {

    override val schema get() = Schema

    fun getPendingPairingRequests(): RequestObject

    companion object {
        const val ID = "tenshu/sync/getPendingPairingRequests"

        /**
         * Message prefix on the failure raised when the relay **refuses this device's identity**
         * (`401`) rather than being unreachable. Matched by `HostPairingMonitor` so the host pairing
         * screen can say which of the two it is.
         *
         * ⚠⚠ The distinction is not cosmetic. A failed poll used to render *"Can't reach the relay —
         * retrying…"* for both, which is wrong twice over when the cause is a `401`: the relay
         * answered (so the network is fine, and the user is sent to look at the wrong thing), and
         * nothing will come of the retrying — a device the relay does not recognise is stuck until it
         * is re-admitted. Observed on 2026-08-17 on a desktop whose registration a relay wipe had
         * dropped: the banner promised a recovery that could not happen, because that device is a
         * pairing **joiner** and holds no master identity to re-register with.
         *
         * ⚠ It says nothing about *why* the relay refused — it deliberately cannot; see
         * `docs/sync/design.md` §"Recovering from relay amnesia". An A1-holding device usually
         * repairs itself before this is ever shown, so in practice this is the joiner's message.
         */
        const val ERROR_DEVICE_UNRECOGNIZED = "PAIRING_POLL_DEVICE_UNRECOGNIZED"
        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {},
            returnType = listType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
