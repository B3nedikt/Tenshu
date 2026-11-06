package org.tenshu.core.common.request

import org.tenshu.core.TResult
import org.tenshu.core.request.RequestChain

interface ExecutableRequestChain {

    val streamId: Long

    fun createStreamId(): RequestChain
}

val RequestChain.streamId get() = (this as ExecutableRequestChain).streamId

fun RequestChain.createStreamId() = (this as ExecutableRequestChain).createStreamId()
