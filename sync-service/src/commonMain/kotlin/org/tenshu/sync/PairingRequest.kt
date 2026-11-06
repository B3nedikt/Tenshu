package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString

/**
 * Handshake: a request from a new device to be added to the user's realm
 * devices. Mostly faithful to the relay-side wire shape, with one
 * proxy-only enrichment: the optional [sas] field is populated by the
 * host's `GetPendingPairingRequestsImplementation` after deriving the
 * pairing SAS locally per
 * `docs/proposals/sync-pairing-flow-improvements.md` §3.2. The relay
 * never sees the SAS — the field is left null on requests deserialised
 * from the wire and only set by the proxy before returning to the UI.
 *
 * Carries two pubkeys with distinct roles:
 *  - [publicKey] — the joiner's ephemeral X25519 ECDH key (asset A8). Used
 *    for SAS derivation between joiner and host. Stays out of
 *    `KnownDevicesStore`; doesn't outlive the handshake.
 *  - [signingPublicKey] — the joiner's persistent Ed25519 device signing
 *    key (asset A4). The host signs the master delegation over **this**
 *    pubkey; the relay registers it in `KnownDevicesStore`; subsequent
 *    JWT verifications and block-signature checks resolve to it.
 *
 * Conflating the two (an earlier shape registered the X25519 key) caused
 * every JWT-authed call from the joiner to fail with 401 because the
 * relay tried to verify Ed25519 signatures against an X25519 pubkey.
 */
interface PairingRequest : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    val deviceName: TString
        get() = getField(Fields.DEVICE_NAME)

    val publicKey: TByteArray
        get() = getField(Fields.PUBLIC_KEY)

    /**
     * Joiner's persistent Ed25519 device signing pubkey (asset A4). The
     * relay registers this in `KnownDevicesStore` after verifying the
     * host's master delegation; all subsequent JWT verification and
     * block-signature checks for the joiner resolve to this key.
     */
    val signingPublicKey: TByteArray
        get() = getField(Fields.SIGNING_PUBLIC_KEY)

    val pairingSecretHash: TString
        get() = getField(Fields.PAIRING_SECRET_HASH)

    /**
     * 6-digit SAS the host UI shows on the approval prompt per §6.3 of
     * `docs/proposals/control-onboarding-ux.md`. Null on relay-fetched
     * requests; populated locally by the proxy after fetch.
     */
    val sas: TString?
        get() = getOptionalField(Fields.SAS)

    companion object {
        const val ID = "tenshu/sync/pairing/request"

        val Schema = composite(
            id = ID,
            fields = {
                string(Fields.DEVICE_ID)
                string(Fields.DEVICE_NAME)
                byteArray(Fields.PUBLIC_KEY)
                byteArray(Fields.SIGNING_PUBLIC_KEY)
                string(Fields.PAIRING_SECRET_HASH)
                string(Fields.SAS, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            deviceId: String,
            deviceName: String,
            publicKey: ByteArray,
            signingPublicKey: ByteArray,
            pairingSecretHash: String,
            sas: String? = null,
        ): PairingRequest = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                deviceName.toTString(context),
                publicKey.toTByteArray(context),
                signingPublicKey.toTByteArray(context),
                pairingSecretHash.toTString(context),
                sas?.toTString(context),
            )
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val DEVICE_NAME = "$ID/deviceName"
        const val PUBLIC_KEY = "$ID/publicKey"
        const val SIGNING_PUBLIC_KEY = "$ID/signingPublicKey"
        const val PAIRING_SECRET_HASH = "$ID/pairingSecretHash"
        const val SAS = "$ID/sas"
    }
}
