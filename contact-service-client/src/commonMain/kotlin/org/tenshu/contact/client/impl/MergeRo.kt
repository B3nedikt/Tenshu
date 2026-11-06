package org.tenshu.contact.client.impl

import org.tenshu.contact.LinkTenshuIdentity
import org.tenshu.contact.MergePersons
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

/**
 * Client ROs for the merge ops (`docs/proposals/contacts-app.md` D7).
 *
 * ⚠⚠ Both MUST also be registered in `contactServiceClientModule` with their
 * `roFactory`. `operation(Schema)` in the common module is not enough — dispatch goes
 * through the client module's registration, and a miss surfaces as an opaque
 * `error/internal`.
 */
internal class LinkTenshuIdentityRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), LinkTenshuIdentity {

    override fun linkTenshuIdentity(fingerprint: String, personId: String): RequestObject {
        val params = buildMap<String, Data> {
            put(LinkTenshuIdentity.Params.FINGERPRINT, fingerprint.toTString(context))
            put(LinkTenshuIdentity.Params.PERSON_ID, personId.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}

internal class MergePersonsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), MergePersons {

    override fun mergePersons(sourcePersonId: String, targetPersonId: String): RequestObject {
        val params = buildMap<String, Data> {
            put(MergePersons.Params.SOURCE_PERSON_ID, sourcePersonId.toTString(context))
            put(MergePersons.Params.TARGET_PERSON_ID, targetPersonId.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
