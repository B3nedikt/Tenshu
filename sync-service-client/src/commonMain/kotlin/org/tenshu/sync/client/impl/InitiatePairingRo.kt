package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.sync.InitiatePairing

internal class InitiatePairingRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), InitiatePairing {

    override val schema: OperationSchema
        get() = InitiatePairing.Schema

    override fun initiatePairing(
        ownerId: String,
        pairingSecret: String,
        deviceName: String,
        otherDevicePublicKey: ByteArray,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    InitiatePairing.Params.OWNER_ID to ownerId.toTString(context),
                    InitiatePairing.Params.PAIRING_SECRET to pairingSecret.toTString(context),
                    InitiatePairing.Params.DEVICE_NAME to deviceName.toTString(context),
                    InitiatePairing.Params.OTHER_DEVICE_PUBLIC_KEY to
                            otherDevicePublicKey.toTByteArray(context),
                ),
                schema = schema
            )
        )
        // Use the generic Data.Schema type per the project convention
        // for Composite return types (see GetSyncStatusRo, RecoveryClaimRo,
        // CreateRealmRo, etc.). Composite types don't have request-object
        // factories registered for them — only primitives like
        // TString/TBoolean do — so passing PairingInitiation.Schema here
        // throws "No request object factory registered for type:
        // tenshu/sync/pairingInitiation" at runtime. The actual
        // PairingInitiation deserialization comes from the Composite
        // factory registered in syncServiceCommonModule.
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
