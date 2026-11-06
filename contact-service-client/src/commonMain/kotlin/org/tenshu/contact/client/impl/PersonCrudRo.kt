package org.tenshu.contact.client.impl

import org.tenshu.contact.CreatePerson
import org.tenshu.contact.DeletePerson
import org.tenshu.contact.RenamePerson
import org.tenshu.contact.UnlinkTenshuIdentity
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

/**
 * Client ROs for the contact-CRUD ops (`docs/proposals/contacts-app.md` §6).
 *
 * ⚠⚠ Each of these MUST also be registered in `contactServiceClientModule` with its
 * `roFactory`. `operation(Schema)` in the common module is not enough: the dispatch goes
 * through the **client** module's registration, and a miss surfaces as an opaque
 * `error/internal` rather than a missing-registration error.
 */
internal class CreatePersonRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), CreatePerson {

    override fun createPerson(displayName: String): RequestObject {
        val params = buildMap<String, Data> {
            put(CreatePerson.Params.DISPLAY_NAME, displayName.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TString.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}

internal class RenamePersonRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RenamePerson {

    override fun renamePerson(personId: String, displayName: String): RequestObject {
        val params = buildMap<String, Data> {
            put(RenamePerson.Params.PERSON_ID, personId.toTString(context))
            put(RenamePerson.Params.DISPLAY_NAME, displayName.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}

internal class DeletePersonRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), DeletePerson {

    override fun deletePerson(personId: String): RequestObject {
        val params = buildMap<String, Data> {
            put(DeletePerson.Params.PERSON_ID, personId.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}

internal class UnlinkTenshuIdentityRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), UnlinkTenshuIdentity {

    override fun unlinkTenshuIdentity(fingerprint: String): RequestObject {
        val params = buildMap<String, Data> {
            put(UnlinkTenshuIdentity.Params.FINGERPRINT, fingerprint.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
