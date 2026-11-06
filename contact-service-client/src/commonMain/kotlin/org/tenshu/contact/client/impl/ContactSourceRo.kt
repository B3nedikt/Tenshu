package org.tenshu.contact.client.impl

import org.tenshu.contact.ImportContact
import org.tenshu.contact.SaveContactSource
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
import org.tenshu.core.toTString

/** Client ROs for the source-record ops. ⚠ Both need a `roFactory` registration. */
internal class SaveContactSourceRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SaveContactSource {

    override fun saveContactSource(
        sourceKey: String,
        personId: String,
        rawVcard: String,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(SaveContactSource.Params.SOURCE_KEY, sourceKey.toTString(context))
            put(SaveContactSource.Params.PERSON_ID, personId.toTString(context))
            put(SaveContactSource.Params.RAW_VCARD, rawVcard.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}

internal class ImportContactRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ImportContact {

    override fun importContact(rawVcard: String, fallbackName: String): RequestObject {
        val params = buildMap<String, Data> {
            put(ImportContact.Params.RAW_VCARD, rawVcard.toTString(context))
            put(ImportContact.Params.FALLBACK_NAME, fallbackName.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TString.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
