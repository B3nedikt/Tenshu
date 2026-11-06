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
 * Return type of [OfferPairing] (`sync-pairing-inverted-direction.md` §3.2): the
 * `tenshu://offer?…` QR/URI the new device displays, plus the `pairingSecretHash`
 * the offerer polls its rendezvous by (and the UI threads into [GetOfferStatus]).
 * The offerer's ephemeral private key stays proxy-side (never on this boundary).
 */
interface OfferPairingSession : Composite {

    override val schema: CompositeSchema get() = Schema

    val offerUri: TString get() = getField(Fields.OFFER_URI)
    val pairingSecretHash: TString get() = getField(Fields.PAIRING_SECRET_HASH)

    companion object {
        const val ID = "tenshu/sync/offerPairingSession"

        val Schema = composite(
            id = ID,
            fields = {
                string(Fields.OFFER_URI)
                string(Fields.PAIRING_SECRET_HASH)
            }
        )

        fun create(
            context: TContext,
            offerUri: String,
            pairingSecretHash: String,
        ): OfferPairingSession = context.create(
            type = ID,
            fields = listOf(
                offerUri.toTString(context),
                pairingSecretHash.toTString(context),
            )
        )
    }

    object Fields {
        const val OFFER_URI = "$ID/offerUri"
        const val PAIRING_SECRET_HASH = "$ID/pairingSecretHash"
    }
}
