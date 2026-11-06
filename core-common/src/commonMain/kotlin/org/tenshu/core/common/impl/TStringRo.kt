package org.tenshu.core.common.impl

import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain

open class TStringRo(
    requestChain: RequestChain,
    context: TContext
) : TValueRo(requestChain, context), TString {
    override fun get(): String = error("Not a data object")
}
