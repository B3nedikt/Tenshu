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
 * Return type of [GetOfferStatus] (`sync-pairing-inverted-direction.md` §3.3 step 4).
 * [state] is one of [PENDING] (no response yet — keep polling), [SCANNED] (the responder
 * has scanned and announced its key, but has not yet approved), [APPROVED] (the responder
 * approved; the proxy has installed the keys and this device is paired), or [DENIED] (the
 * responder declined). [sas] carries the 6-digit Short Authentication String on [SCANNED]
 * **and** [APPROVED] — pre-approval on the former, which is what lets both users compare the
 * code before anyone approves (A2/A3); empty otherwise.
 */
interface OfferStatus : Composite {

    override val schema: CompositeSchema get() = Schema

    val state: TString get() = getField(Fields.STATE)
    val sas: TString get() = getField(Fields.SAS)

    companion object {
        const val ID = "tenshu/sync/offerStatus"

        const val PENDING = "pending"

        /**
         * The responder scanned the offer and announced its ephemeral key, but has **not decided
         * yet** (A2). [sas] is populated, so the offerer can show the code while the responder's
         * approval prompt is still on screen — the pre-approval comparison the join flow always
         * had and the offer flow structurally could not.
         *
         * ⚠ Additive on purpose: an older offerer treats any non-`approved`/`denied` state as
         * "keep polling", so it ignores this and behaves exactly as before. No cliff.
         */
        const val SCANNED = "scanned"
        const val APPROVED = "approved"
        const val DENIED = "denied"

        val Schema = composite(
            id = ID,
            fields = {
                string(Fields.STATE)
                string(Fields.SAS)
            }
        )

        fun create(
            context: TContext,
            state: String,
            sas: String = "",
        ): OfferStatus = context.create(
            type = ID,
            fields = listOf(
                state.toTString(context),
                sas.toTString(context),
            )
        )
    }

    object Fields {
        const val STATE = "$ID/state"
        const val SAS = "$ID/sas"
    }
}
