package org.tenshu.sync.client.impl

import org.tenshu.core.Data
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
import org.tenshu.sync.GrantSharedRealmMembership

internal class GrantSharedRealmMembershipRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GrantSharedRealmMembership {

    override val schema: OperationSchema
        get() = GrantSharedRealmMembership.Schema

    override fun grantSharedRealmMembership(
        realmId: String,
        personId: String,
        label: String,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(GrantSharedRealmMembership.Params.REALM_ID, realmId.toTString(context))
            put(GrantSharedRealmMembership.Params.PERSON_ID, personId.toTString(context))
            if (label.isNotEmpty()) {
                put(GrantSharedRealmMembership.Params.LABEL, label.toTString(context))
            }
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<TString>(
            type = TString.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
