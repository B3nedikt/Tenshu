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
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString

/**
 * A device's signed declaration of the highest HLC it has observed across the realm.
 * Carried piggyback on every `POST /sync/changes` request and returned in peer-tip lists
 * to other devices, per `docs/proposals/sync-backend-relay-plan.md` §5.1 and
 * `docs/sync/threat-model.md` §11.3.
 *
 * Why signed: receiving devices verify [signature] against the publishing device's
 * known public key (KnownDevicesStore-equivalent on the client). A hostile relay
 * cannot forge a tip claiming a different device has observed a different HLC — the
 * signature would fail. This is what makes I6 (no silent censorship) enforceable.
 *
 * Canonical signed bytes: see `MasterDelegationVerifier.hlcTipMessage` server-side and
 * the matching client-side helper.
 */
interface SignedHlcTip : Composite {

    override val schema: CompositeSchema get() = Schema

    val ownerId: TString get() = getField(Fields.OWNER_ID)
    val deviceId: TString get() = getField(Fields.DEVICE_ID)
    val hlc: HybridLogicalClock get() = getField(Fields.HLC)
    val signature: TByteArray get() = getField(Fields.SIGNATURE)

    companion object {
        const val ID = "tenshu/sync/signedHlcTip"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.OWNER_ID)
                string(Fields.DEVICE_ID)
                composite(Fields.HLC, type = HybridLogicalClock.Schema)
                byteArray(Fields.SIGNATURE)
            }
        )

        fun create(
            context: TContext,
            ownerId: String,
            deviceId: String,
            hlc: HybridLogicalClock,
            signature: ByteArray
        ): SignedHlcTip = context.create(
            type = ID,
            fields = listOf(
                ownerId.toTString(context),
                deviceId.toTString(context),
                hlc,
                signature.toTByteArray(context)
            )
        )
    }

    object Fields {
        const val OWNER_ID = "$ID/ownerId"
        const val DEVICE_ID = "$ID/deviceId"
        const val HLC = "$ID/hlc"
        const val SIGNATURE = "$ID/signature"
    }
}
