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
 * A device's announcement of its **admission public key** (asset A33,
 * `delegated-device-admission-authority.md` §8 P4 / Q4) — the migration path
 * for devices that never received an A33 via the pairing blob (pre-P3
 * joiners), and the re-root signal (Q2) for devices holding a deep chain.
 *
 * The device mints its A33 keypair **locally**, keeps the private half in its
 * own at-rest custody (the J3 joiner payload), and syncs only the public key —
 * signed with its A4 device-signing key over the canonical
 * `admissionKeyAnnouncementMessage` (tag `tenshu-admit-announce-v2`, length-prefixed) so an A1-holder can verify the
 * announcement against the [Device] row's registered public key before
 * minting. **No private key ever rides this row** (contrast: naive migration
 * would sync freshly-minted keypairs realm-wide, letting a later-stolen device
 * impersonate every other device's admission identity).
 *
 * An A1-holder that observes a verified announcement answers with an
 * [AdmissionCertGrant]. Keyed by [deviceId] — one announcement per device.
 */
interface AdmissionKeyAnnouncement : Composite {

    override val schema: CompositeSchema get() = Schema

    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    /** The announced A33 public key. */
    val publicKey: TByteArray
        get() = getField(Fields.PUBLIC_KEY)

    /**
     * Ed25519 signature by the device's **A4** signing key over the canonical
     * announcement message (`admissionKeyAnnouncementMessage`,
     * `:sync-service-common`) — binds the A33 key to the device that the realm
     * (and relay) already know by that A4 key.
     */
    val signature: TByteArray
        get() = getField(Fields.SIGNATURE)

    companion object {
        const val ID = "tenshu/identity/admissionKeyAnnouncement"

        /** Keys by [deviceId] — one announcement per device. */
        val DataIdProviderSchema: StringDataIdProviderSchema = stringDataId(
            id = "$ID/dataIdProvider",
            fieldRef = Fields.DEVICE_ID,
        )

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProviderSchema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Fields.DEVICE_ID)
                byteArray(Fields.PUBLIC_KEY)
                byteArray(Fields.SIGNATURE)
            },
        )

        fun create(
            context: TContext,
            deviceId: String,
            publicKey: ByteArray,
            signature: ByteArray,
        ): AdmissionKeyAnnouncement = context.create(
            type = ID,
            fields = listOf(
                deviceId,
                publicKey,
                signature,
            ),
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val PUBLIC_KEY = "$ID/publicKey"
        const val SIGNATURE = "$ID/signature"
    }
}
