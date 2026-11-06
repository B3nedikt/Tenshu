package org.tenshu.calendar.client.impl

import org.tenshu.calendar.QueryOccurrences
import org.tenshu.calendar.ResolvedEventOccurrence
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType
import org.tenshu.core.toTString

/**
 * ⚠ Names `listType(ResolvedEventOccurrence.Schema)` — a **list** type, which is the shape
 * `PhotosNear` established. That is safe over IPC precisely because a list type has a registered
 * factory; naming a bare *concrete* return type here is what made `openTarget` uncallable
 * (`control-cross-app-open.md` §5d.3).
 */
internal class QueryOccurrencesRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), QueryOccurrences {

    override fun queryOccurrences(fromDate: String, toDate: String, limit: Int): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    QueryOccurrences.Params.FROM_DATE to fromDate.toTString(context),
                    QueryOccurrences.Params.TO_DATE to toDate.toTString(context),
                    QueryOccurrences.Params.LIMIT to limit.toLong().toTInteger(context),
                ),
                schema = schema,
            )
        )

        return context.roProvider().createRequestObject<TList<ResolvedEventOccurrence>>(
            type = listType(ResolvedEventOccurrence.Schema),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
