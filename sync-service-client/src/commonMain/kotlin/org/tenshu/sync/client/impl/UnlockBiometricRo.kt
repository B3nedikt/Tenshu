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
import org.tenshu.sync.UnlockBiometric

internal class UnlockBiometricRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), UnlockBiometric {

    override val schema: OperationSchema
        get() = UnlockBiometric.Schema

    override fun unlockBiometric(wrapKey: ByteArray): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    UnlockBiometric.Params.WRAP_KEY to wrapKey.toTByteArray(context),
                ),
                schema = schema,
            )
        )
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
