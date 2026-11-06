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
import org.tenshu.core.toTString
import org.tenshu.sync.CreateRealm

internal class CreateRealmRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), CreateRealm {

    override val schema: OperationSchema
        get() = CreateRealm.Schema

    override fun createRealm(recoveryPhrase: String, deviceName: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    CreateRealm.Params.RECOVERY_PHRASE to recoveryPhrase.toTString(context),
                    CreateRealm.Params.DEVICE_NAME to deviceName.toTString(context)
                ),
                schema = schema
            )
        )
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
