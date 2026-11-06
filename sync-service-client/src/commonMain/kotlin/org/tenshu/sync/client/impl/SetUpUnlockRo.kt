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
import org.tenshu.sync.SetUpUnlock

internal class SetUpUnlockRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SetUpUnlock {

    override val schema: OperationSchema
        get() = SetUpUnlock.Schema

    override fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    SetUpUnlock.Params.PASSPHRASE to passphrase.toTString(context),
                    SetUpUnlock.Params.BIOMETRIC_WRAP_KEY to biometricWrapKey.toTByteArray(context),
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
