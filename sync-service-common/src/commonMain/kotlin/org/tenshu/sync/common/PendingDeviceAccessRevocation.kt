package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.Data
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString

/**
 * One `POST /sync/device-access/revoke` this device owes the relay (`sync-entity-identity.md` §5b,
 * phase R2a): an **already signed** [DeviceAccessRevocation], durable until the relay gives a
 * terminal answer.
 *
 * ⚠⚠ **Signed at enqueue, sent at drain.** The signature is computed while the revoking key is in
 * hand — A1 may not be resident on a later tick, and a device revoking itself loses its own key
 * the moment the relay applies it. So the drain needs no key at all; it replays these bytes.
 *
 * ⚠⚠ [authoredAt] is the **same** HLC the `Device` tombstone for this removal carries. The relay
 * applies both under one staleness rule, and two stamps would let a re-admission landing between
 * them make the two planes disagree (§5b, "the intent carries the revocation's authored HLC").
 *
 * ⚠ One row per [deviceId]: a later removal of the same device replaces the earlier one, which a
 * removal at the later stamp subsumes. The drain deletes a row only if it still holds the stamp it
 * sent.
 *
 * ⚠ Device-local, never synced. A **new** composite, so no `SyncService` version moves (HR #9 bumps
 * on a reshape); its fields are positional and persisted, so any later change owes the bump.
 */
interface PendingDeviceAccessRevocation : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The device whose access is cut — this row's key. */
    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** The realm it is cut from (the personal realm the tombstone is stamped for). */
    val ownerId: TString
        get() = getField(Fields.OWNER_ID)

    /** [DeviceAccessRevocation.Authority.wire]. */
    val authority: TString
        get() = getField(Fields.AUTHORITY)

    /** The authored HLC — shared with the removal's `Device` tombstone. */
    val authoredAt: HybridLogicalClock
        get() = getField(Fields.AUTHORED_AT)

    /** Ed25519 over [DeviceAccessRevocation.message] for [authority]. */
    val signature: TByteArray
        get() = getField(Fields.SIGNATURE)

    /**
     * Where the relay left this request — a [State] — so a removal the relay has not applied is never shown
     * as plainly "removed" (maintainer decision 2026-11-12).
     */
    val relayState: TString
        get() = getField(Fields.RELAY_STATE)

    /** The relay's answer behind [relayState] (status and reason), for the user and the log. Empty while owed. */
    val relayDetail: TString
        get() = getField(Fields.RELAY_DETAIL)

    /**
     * The states. **Resent** (still owed): [OWED], [RELAY_UNKNOWN], [NEEDS_UPDATE]. **Kept for display only**,
     * never resent, because the same signed bytes would get the same answer: [REFUSED], [SUPERSEDED].
     */
    object State {
        /** Not yet answered, or a transient failure (network, `5xx`, `429`). */
        const val OWED = "owed"

        /** `404`: a relay that predates the route, or one that no longer holds the realm (a reset, data loss). */
        const val RELAY_UNKNOWN = "relay-unknown"

        /** `426`: protocol skew mid-rollout. Retried; an update on one side clears it. */
        const val NEEDS_UPDATE = "needs-update"

        /** Any other `4xx` (bad signature, malformed): a signing or encoding mismatch between versions. */
        const val REFUSED = "refused"

        /** `200` but stale: the relay holds a later admission of this device — it was re-admitted since. */
        const val SUPERSEDED = "superseded"

        val RESENT: Set<String> = setOf(OWED, RELAY_UNKNOWN, NEEDS_UPDATE)
    }

    companion object {
        const val ID = "tenshu/sync/pendingDeviceAccessRevocation"

        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema),
            fields = {
                string(Fields.DEVICE_ID)
                string(Fields.OWNER_ID)
                string(Fields.AUTHORITY)
                composite(Fields.AUTHORED_AT, type = HybridLogicalClock.Schema)
                byteArray(Fields.SIGNATURE)
                string(Fields.RELAY_STATE)
                string(Fields.RELAY_DETAIL)
            },
        )

        fun create(
            context: TContext,
            ownerId: String,
            deviceId: String,
            authority: DeviceAccessRevocation.Authority,
            authoredAt: HybridLogicalClock,
            signature: ByteArray,
            relayState: String = State.OWED,
            relayDetail: String = "",
        ): PendingDeviceAccessRevocation = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                ownerId.toTString(context),
                authority.wire.toTString(context),
                authoredAt,
                signature.toTByteArray(context),
                relayState.toTString(context),
                relayDetail.toTString(context),
            ),
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val OWNER_ID = "$ID/ownerId"
        const val AUTHORITY = "$ID/authority"
        const val AUTHORED_AT = "$ID/authoredAt"
        const val SIGNATURE = "$ID/signature"
        const val RELAY_STATE = "$ID/relayState"
        const val RELAY_DETAIL = "$ID/relayDetail"
    }
}

internal class PendingDeviceAccessRevocationDto(
    fieldValues: List<Data?>,
) : PendingDeviceAccessRevocation, CompositeDto(fieldValues)
