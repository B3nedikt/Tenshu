package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * Return type of [RespondToPairingOffer] (`sync-pairing-inverted-direction.md` §3.3
 * step 3): the scanned offer's [deviceName] + the derived 6-digit [sas] the responder
 * shows in its approval prompt, plus the [pairingSecretHash] the UI threads back into
 * [ApprovePairingOffer]/[DenyPairingOffer]. The responder's ephemeral private key +
 * scanned offerer identity stay proxy-side (keyed by the hash).
 */
interface PairingOfferApprovalPrompt : Composite {

    override val schema: CompositeSchema get() = Schema

    val pairingSecretHash: TString get() = getField(Fields.PAIRING_SECRET_HASH)
    val deviceName: TString get() = getField(Fields.DEVICE_NAME)
    val sas: TString get() = getField(Fields.SAS)

    companion object {
        const val ID = "tenshu/sync/pairingOfferApprovalPrompt"

        val Schema = composite(
            id = ID,
            fields = {
                string(Fields.PAIRING_SECRET_HASH)
                string(Fields.DEVICE_NAME)
                string(Fields.SAS)
            }
        )

        fun create(
            context: TContext,
            pairingSecretHash: String,
            deviceName: String,
            sas: String,
        ): PairingOfferApprovalPrompt = context.create(
            type = ID,
            fields = listOf(
                pairingSecretHash.toTString(context),
                deviceName.toTString(context),
                sas.toTString(context),
            )
        )
    }

    object Fields {
        const val PAIRING_SECRET_HASH = "$ID/pairingSecretHash"
        const val DEVICE_NAME = "$ID/deviceName"
        const val SAS = "$ID/sas"
    }
}
