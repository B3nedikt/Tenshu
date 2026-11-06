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
 * Return type of [InitiatePairing] — carries this device's freshly-
 * generated ephemeral [publicKey] (per `pairing-flow.md` step 2) and
 * the 6-digit Short Authentication String per
 * `docs/proposals/sync-pairing-flow-improvements.md` §3.2.
 *
 * The SAS is derived locally on the proxy from the shared secret it
 * computes via X25519 ECDH; both sides of the handshake derive the
 * same value from the same inputs, so a UI showing the SAS lets the
 * user verify the two screens match before approving the request on
 * the host.
 */
interface PairingInitiation : Composite {

    override val schema: CompositeSchema get() = Schema

    val publicKey: TByteArray
        get() = getField(Fields.PUBLIC_KEY)

    val sas: TString
        get() = getField(Fields.SAS)

    companion object {
        const val ID = "tenshu/sync/pairingInitiation"

        val Schema = composite(
            id = ID,
            fields = {
                byteArray(Fields.PUBLIC_KEY)
                string(Fields.SAS)
            }
        )

        fun create(
            context: TContext,
            publicKey: ByteArray,
            sas: String,
        ): PairingInitiation = context.create(
            type = ID,
            fields = listOf(
                publicKey.toTByteArray(context),
                sas.toTString(context),
            )
        )
    }

    object Fields {
        const val PUBLIC_KEY = "$ID/publicKey"
        const val SAS = "$ID/sas"
    }
}
