package org.tenshu.core.common.impl

import org.tenshu.core.TByteArray
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain

class TByteArrayRo(
    requestChain: RequestChain,
    context: TContext
) : TValueRo(requestChain, context), TByteArray {
    override val size: Long
        get() = error("Not a data object")

    override fun get(): ByteArray = error("Not a data object")
}
