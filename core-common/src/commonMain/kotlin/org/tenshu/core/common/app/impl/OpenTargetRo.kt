package org.tenshu.core.common.app.impl

import org.tenshu.core.Data
import org.tenshu.core.app.OpenTarget
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class OpenTargetRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), OpenTarget {

    override fun openTarget(target: DataId): RequestObject {
        val params = buildMap<String, Data> {
            // ⚠ The DataId travels as itself — a composite param, not a rendered string. Which
            // variant it is (`StringDataId`, `CompositeEntityDataId`, …) is preserved, and that is
            // the whole point of CO-D5.
            put(OpenTarget.Params.TARGET, target)
        }
        val nextChain = requestChain.addArray(
            OperationDto(
                params = params,
                schema = schema,
            )
        )
        // ⚠⚠ A GENERIC `Data` request object, not an `OpenOutcome`-typed one — and this is the
        // difference between an op that works over IPC and one that does not. Naming the concrete
        // return type demands a **composite RO factory** for it, which `OpenOutcome` does not have
        // and does not need: the reply is parsed back through the type id its own bytes carry, via
        // the `OpenOutcomeDto` registered in `CoreCommonModule`.
        //
        // ⚠⚠ TT-1a named the concrete type, and every IPC call therefore died with
        // `No request object factory registered for type: tenshu/app/openOutcome` — *after* the
        // proxy had resolved the handler, so the failure looked like a delivery problem. Nothing
        // caught it because `OpenTargetTest` drives an in-process `TestProxy`, which never builds
        // a request object at all. `AskMiaRo` carries the same note for the same reason.
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
