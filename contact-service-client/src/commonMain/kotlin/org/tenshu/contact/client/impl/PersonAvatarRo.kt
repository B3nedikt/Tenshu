package org.tenshu.contact.client.impl

import org.tenshu.contact.ClearPersonAvatar
import org.tenshu.contact.GetPersonAvatar
import org.tenshu.contact.Person
import org.tenshu.contact.SetPersonAvatar
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString

/**
 * Client ROs for the avatar ops.
 *
 * ⚠⚠ [GetPersonAvatarRo] is the one that matters most: it resolves the **deferred
 * `Person.Avatar` dynamic property**, and a `dynamicProperty` dispatches through *this*
 * module's `roFactory` registration rather than the common module's `operation(Schema)`.
 * Forget its registration in `contactServiceClientModule` and every select projecting the
 * avatar dies as an opaque `error/internal`.
 */
internal class SetPersonAvatarRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SetPersonAvatar {

    override fun setPersonAvatar(personId: String, imageData: ByteArray): RequestObject {
        val params = buildMap<String, Data> {
            put(SetPersonAvatar.Params.PERSON_ID, personId.toTString(context))
            put(SetPersonAvatar.Params.IMAGE_DATA, imageData.toTByteArray(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}

internal class ClearPersonAvatarRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ClearPersonAvatar {

    override fun clearPersonAvatar(personId: String): RequestObject {
        val params = buildMap<String, Data> {
            put(ClearPersonAvatar.Params.PERSON_ID, personId.toTString(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}

internal class GetPersonAvatarRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GetPersonAvatar {

    override fun getAvatar(person: Person): TByteArray {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(GetPersonAvatar.Params.PERSON to person),
                schema = schema,
            )
        )
        return context.roProvider()
            .createRequestObject(TByteArray.Schema.toType(), nextChain, context)
    }
}
