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
 * Re-assert this device's registration with the relay.
 *
 * ⚠ This op recovers from an in-memory known-devices registry — the dev/test path, and any
 * relay running without `DATABASE_URL`. A production relay persists it (`SqliteDeviceRegistry`,
 * which is the durable fix for the bug below), so the 401 storm this op was written for should
 * no longer occur there. When such a relay restarts under a
 * long-lived, autostarted Control daemon, this device's previously-registered public
 * key is gone, so every JWT-authenticated call — sync push/pull *and* the host's
 * pairing-request poll — comes back 401, with nothing re-asserting the registration.
 * That is the failure that makes the pairing approval dialog never appear
 * ([GetPendingPairingRequests] returns empty on a silently-401'd poll).
 *
 * This op re-establishes the relay-side registration **without re-pairing**, using the
 * device's existing master identity (asset A1):
 *
 *   1. Re-bootstrap the realm via `POST /sync/realm` ([SyncRelay.createRealm]). After a
 *      wipe the realm record is gone, so this deterministically recreates it (same
 *      `ownerId`/`masterPublicKey`) and registers this device — a 201.
 *   2. If that fails (409: another of the user's devices recreated the realm first, or
 *      it survived), re-admit self via `POST /sync/admit-device`
 *      ([SyncRelay.admitDevice]) with a master-signed delegation over
 *      `(devicePublicKey || deviceId || epoch)`.
 *
 * Idempotent and safe to retry: a transient network failure just fails again
 * (the caller debounces + retries); a 401 is repaired. Only a master-identity holder
 * (the realm owner, or any recovered device) can run this — a pairing **joiner** holds
 * no master key, so the impl fails loudly there (its recourse is re-pairing or the
 * owner re-admitting it). See `ReregisterDeviceImplementation`.
 */
suspend fun SyncService.reregisterDevice(): TResult<Data?> =
    findOperation<ReregisterDevice>(ReregisterDevice.ID)
        .reregisterDevice()
        .asTaskInteractor<Data?>()
        .execute()

interface ReregisterDevice : Operation {

    override val schema get() = Schema

    fun reregisterDevice(): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/reregisterDevice"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            returnType = Data.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
