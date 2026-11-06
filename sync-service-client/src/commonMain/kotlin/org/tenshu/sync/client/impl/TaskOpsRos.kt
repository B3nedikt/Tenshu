package org.tenshu.sync.client.impl

import org.tenshu.core.TaskSpec
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.sync.BackgroundTask
import org.tenshu.sync.RegisterTask
import org.tenshu.sync.RenewTaskLease
import org.tenshu.sync.TransitionTask

internal class RegisterTaskRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RegisterTask {

    override val schema: OperationSchema
        get() = RegisterTask.Schema

    override fun registerTask(taskId: String, spec: TaskSpec<*>): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RegisterTask.Params.TASK_ID to taskId.toTString(context),
                    RegisterTask.Params.SPEC to spec,
                ),
                schema = schema
            )
        )
        return context.roProvider().createRequestObject<org.tenshu.core.Data>(
            type = org.tenshu.core.Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}

internal class RenewTaskLeaseRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RenewTaskLease {

    override val schema: OperationSchema
        get() = RenewTaskLease.Schema

    override fun renewTaskLease(taskId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RenewTaskLease.Params.TASK_ID to taskId.toTString(context),
                ),
                schema = schema
            )
        )
        return context.roProvider().createRequestObject<org.tenshu.core.Data>(
            type = org.tenshu.core.Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}

internal class TransitionTaskRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), TransitionTask {

    override val schema: OperationSchema
        get() = TransitionTask.Schema

    override fun transitionTask(taskId: String, transition: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    TransitionTask.Params.TASK_ID to taskId.toTString(context),
                    TransitionTask.Params.TRANSITION to transition.toTString(context),
                ),
                schema = schema
            )
        )
        return context.roProvider().createRequestObject<org.tenshu.core.Data>(
            type = org.tenshu.core.Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
