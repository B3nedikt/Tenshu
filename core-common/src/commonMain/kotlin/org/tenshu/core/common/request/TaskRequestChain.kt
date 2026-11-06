package org.tenshu.core.common.request

import org.tenshu.core.Data
import org.tenshu.core.TArray
import org.tenshu.core.TResult
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.request.RequestChain

class TaskRequestChain(
    private val taskExecutor: TaskExecutor,
    val context: TContext,
    private val builder: RequestChainBuilder = RequestChainBuilder(context),
    override val streamId: Long = 0
) : RequestChain, ExecutableRequestChain {

    override fun addReference(id: String): RequestChain {
        return copy(builder = builder.addReference(id) as RequestChainBuilder)
    }

    override fun addArray(array: TArray): RequestChain {
        return copy(builder = builder.addArray(array) as RequestChainBuilder)
    }

    override fun addNestedArray(block: (RequestChain) -> RequestChain): RequestChain {
        return copy(builder = builder.addNestedArray(block) as RequestChainBuilder)
    }

    override fun getCallstack(): List<Data> = builder.getCallstack()

    override fun createStreamId(): RequestChain {
        return copy(streamId = getCallstack().hashCode().toLong())
    }

    override suspend fun getResult(): TResult<Data> {
        val dataFactory = context.dataFactory()
        val task = dataFactory.createList<Data>(getCallstack())
        return if (taskExecutor is AsyncTaskExecutor) {
            taskExecutor.executeAsync(task)
        } else {
            taskExecutor.execute(task)
        }
    }

    private fun copy(
        builder: RequestChainBuilder = this.builder,
        streamId: Long = this.streamId
    ): TaskRequestChain {
        return TaskRequestChain(taskExecutor, context, builder, streamId)
    }
}
