package org.tenshu.contact.client.impl

import org.tenshu.contact.GetContactSas
import org.tenshu.contact.VerifyTenshuIdentity
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/**
 * Client ROs for the re-verification ops.
 *
 * ⚠⚠ Both MUST also be registered in `contactServiceClientModule` with their `roFactory` —
 * dispatch resolves through the client module, and a miss surfaces as an opaque
 * `error/internal` rather than a missing-registration error.
 */
internal class GetContactSasRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GetContactSas {

    override fun getContactSas(fingerprint: String): RequestObject {
        val params = buildMap<String, Data> {
            put(GetContactSas.Params.FINGERPRINT, fingerprint.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TString.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}

internal class VerifyTenshuIdentityRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), VerifyTenshuIdentity {

    override fun verifyTenshuIdentity(fingerprint: String, verified: Boolean): RequestObject {
        val params = buildMap<String, Data> {
            put(VerifyTenshuIdentity.Params.FINGERPRINT, fingerprint.toTString(context))
            put(VerifyTenshuIdentity.Params.VERIFIED, verified.toTBoolean(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
