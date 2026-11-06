package org.tenshu.core.common.impl

import org.tenshu.core.TBoolean
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain

class TBooleanRo(
    requestChain: RequestChain,
    context: TContext,
) : TValueRo(requestChain, context), TBoolean {
    override fun get(): Boolean = error("Not a data object")
}
