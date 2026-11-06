package org.tenshu.core.common.app.impl

import org.tenshu.core.Data
import org.tenshu.core.common.app.AnnounceSession
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class AnnounceSessionRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), AnnounceSession {

    override fun announceSession(
        appId: String,
        current: VersionVector,
        requires: VersionVector,
    ): RequestObject {
        val params = mapOf<String, Data>(
            AnnounceSession.Params.APP_ID to appId.toTString(context),
            AnnounceSession.Params.CURRENT to current,
            AnnounceSession.Params.REQUIRES to requires,
        )
        val nextChain = requestChain.addArray(
            OperationDto(
                params = params,
                schema = schema
            )
        )
        // Generic Data type, the WriteSetting idiom: a typed request object
        // would need a registered SessionAdmission Ro factory (missing, it
        // surfaces as "No request object factory registered" on the very
        // first announce). The RESULT still parses typed via the registered
        // SessionAdmissionDto.
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
