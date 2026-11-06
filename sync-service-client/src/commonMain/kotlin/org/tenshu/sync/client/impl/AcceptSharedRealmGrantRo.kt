package org.tenshu.sync.client.impl

import org.tenshu.core.TString
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.sync.AcceptSharedRealmGrant

internal class AcceptSharedRealmGrantRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), AcceptSharedRealmGrant {

    override val schema: OperationSchema
        get() = AcceptSharedRealmGrant.Schema

    override fun acceptSharedRealmGrant(grantorFingerprint: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    AcceptSharedRealmGrant.Params.GRANTOR_FINGERPRINT to grantorFingerprint.toTString(context),
                ),
                schema = schema,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<TString>(
            type = TString.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
