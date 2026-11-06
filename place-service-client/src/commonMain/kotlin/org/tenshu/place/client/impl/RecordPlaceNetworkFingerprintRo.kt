package org.tenshu.place.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.core.place.RecordPlaceNetworkFingerprint
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordPlaceNetworkFingerprintRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordPlaceNetworkFingerprint {

    override fun recordPlaceNetworkFingerprint(fingerprint: PlaceNetworkFingerprint): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordPlaceNetworkFingerprint.Params.FINGERPRINT to fingerprint
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
