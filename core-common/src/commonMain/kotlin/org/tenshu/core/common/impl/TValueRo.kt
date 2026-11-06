package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.TValue
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject

abstract class TValueRo(
    override val requestChain: RequestChain,
    override val context: TContext
) : TValue, RequestObject {

    override val value: Data
        get() = this
}
