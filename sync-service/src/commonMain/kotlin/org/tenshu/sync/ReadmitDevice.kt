package org.tenshu.sync

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Restore a **peer** device's relay registration, from this device's roster alone.
 *
 * **The gap this closes.** A device the relay no longer recognises answers every request with
 * `401`, and the only automatic repair — `SyncScheduler.recoverRegistration` →
 * [ReregisterDevice] — is gated on holding the master identity (A1). A pairing **joiner** holds
 * none, so for it the `401` is terminal: it cannot re-assert genesis and cannot sign a fresh
 * self-admission. Until now its only exit was a full re-pairing, which needs the user to drive a
 * QR/SAS handshake on two devices. Diagnosed on real hardware 2026-08-27 —
 * `docs/tech-debt.md` → *"A joiner that loses its relay registration is wedged permanently"*.
 *
 * **Why it needs nothing from the stranded device.** `Device.publicKey` is a field of the
 * **synced** `Device` row, so an A1-holder already has everything `POST /sync/admit-device`
 * verifies: the target's id and public key from its own roster, and the master key to sign the
 * delegation. The stranded device keeps its A4 keypair and derived realm keys throughout and
 * simply starts being accepted again on its next tick.
 *
 * **Trust surface is unchanged.** This is the same master-signed delegation over
 * `devicePublicKey || deviceId || epoch(8 BE)` that admission has always used, minted by the same
 * A1-holder that could admit the device by pairing anyway. No new authority, and nothing the relay
 * did not already verify.
 *
 * ⚠⚠ **A revoked device cannot be readmitted through this op**, and that is deliberate rather
 * than incidental: revocation deletes the local `Device` row, so the public key this operation
 * must sign over is gone and the call fails as not-found. Re-admitting a genuinely removed device
 * stays a re-pairing decision, made deliberately, not a one-tap repair sitting in a list.
 *
 * ⚠ Not for this device — see [ReregisterDevice], which owns the self-repair and its A1 gate.
 */
suspend fun SyncService.readmitDevice(deviceId: String): TResult<Data?> =
    findOperation<ReadmitDevice>(ReadmitDevice.ID)
        .readmitDevice(deviceId)
        .asTaskInteractor<Data?>()
        .execute()

interface ReadmitDevice : Operation {

    fun readmitDevice(deviceId: String): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/readmitDevice"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            params = {
                string(Params.DEVICE_ID)
            },
            returnType = Data.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val DEVICE_ID = "$ID/deviceId"
    }
}
