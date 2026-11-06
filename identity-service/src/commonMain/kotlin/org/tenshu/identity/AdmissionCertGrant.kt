package org.tenshu.identity

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * An A1-holder's answer to an [AdmissionKeyAnnouncement]
 * (`delegated-device-admission-authority.md` §8 P4): the **admission
 * capability-cert chain** minted for [deviceId]'s announced A33 public key —
 * always **depth-1** (signed directly by the realm master), which makes this
 * row double as the Q2 **re-root**: a device holding a deeper chain that
 * announces gets a flat replacement whenever an A1-holder is online.
 *
 * Everything on this row is public, verifiable material — the master public
 * key (so the target device can check the chain roots at *its* realm:
 * `ownerIdForMasterPublicKey(masterPublicKey) == currentOwnerId`) and the
 * encoded cert chain. The A33 *private* key never syncs; it stays in the
 * announcing device's local custody. Keyed by [deviceId] — the newest grant
 * per device wins by LWW, which is exactly the re-root semantic.
 */
interface AdmissionCertGrant : Composite {

    override val schema: CompositeSchema get() = Schema

    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** The realm master public key (A1) the chain roots at — verify, then trust. */
    val masterPublicKey: TByteArray
        get() = getField(Fields.MASTER_PUBLIC_KEY)

    /** `encodeAdmissionCertChain` bytes (`:sync-service-common`) — depth-1 by construction. */
    val certChain: TByteArray
        get() = getField(Fields.CERT_CHAIN)

    companion object {
        const val ID = "tenshu/identity/admissionCertGrant"

        /** Keys by [deviceId] — one live grant per device (LWW re-root). */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DEVICE_ID)
                byteArray(Fields.MASTER_PUBLIC_KEY)
                byteArray(Fields.CERT_CHAIN)
            },
        )

        fun create(
            context: TContext,
            deviceId: String,
            masterPublicKey: ByteArray,
            certChain: ByteArray,
        ): AdmissionCertGrant = context.create(
            type = ID,
            fields = listOf(
                deviceId,
                masterPublicKey,
                certChain,
            ),
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val MASTER_PUBLIC_KEY = "$ID/masterPublicKey"
        const val CERT_CHAIN = "$ID/certChain"
    }
}
