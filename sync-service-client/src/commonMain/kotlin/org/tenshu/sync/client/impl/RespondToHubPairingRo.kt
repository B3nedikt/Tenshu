package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.sync.RespondToHubPairing

internal class RespondToHubPairingRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RespondToHubPairing {

    override val schema: OperationSchema
        get() = RespondToHubPairing.Schema

    override fun respondToHubPairing(scannedUri: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RespondToHubPairing.Params.SCANNED_URI to scannedUri.toTString(context),
                ),
                schema = schema
            )
        )
        // Composite return type (PairingOfferApprovalPrompt) — bind to Data.Schema; the
        // concrete prompt is materialised by the factory in syncServiceCommonModule.
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
