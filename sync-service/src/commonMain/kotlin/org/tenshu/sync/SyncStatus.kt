package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

interface SyncStatus : Composite {

    override val schema: CompositeSchema get() = Schema

    val deviceId: TString
        get() = getField(Fields.DEVICE_ID)

    val ownerId: TString?
        get() = getOptionalField(Fields.OWNER_ID)

    val isEnabled: TBoolean
        get() = getField(Fields.IS_ENABLED)

    val pairingString: TString?
        get() = getOptionalField(Fields.PAIRING_STRING)

    companion object {
        const val ID = "tenshu/sync/status"

        val Schema = composite(
            id = ID,
            fields = {
                string(Fields.DEVICE_ID)
                string(Fields.OWNER_ID, isOptional = true)
                boolean(Fields.IS_ENABLED)
                string(Fields.PAIRING_STRING, isOptional = true)
            }
        )

        fun create(
            context: TContext,
            deviceId: String,
            ownerId: String?,
            isEnabled: Boolean,
            pairingString: String?
        ): SyncStatus = context.create(
            type = ID,
            fields = listOf(
                deviceId.toTString(context),
                ownerId?.toTString(context),
                isEnabled.toTBoolean(context),
                pairingString?.toTString(context)
            )
        )
    }

    object Fields {
        const val DEVICE_ID = "$ID/deviceId"
        const val OWNER_ID = "$ID/ownerId"
        const val IS_ENABLED = "$ID/isEnabled"
        const val PAIRING_STRING = "$ID/pairingString"
    }
}
