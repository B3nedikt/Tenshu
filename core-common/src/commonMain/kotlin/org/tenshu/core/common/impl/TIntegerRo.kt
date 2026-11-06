package org.tenshu.core.common.impl

import org.tenshu.core.core.TContext
import org.tenshu.core.number.TInteger
import org.tenshu.core.request.RequestChain

class TIntegerRo(
    requestChain: RequestChain,
    context: TContext
) : TValueRo(requestChain, context), TInteger {
    override fun get(): Long = error("Not a data object")
}
