package org.tenshu.core.common.proxy

import org.tenshu.core.Data

interface OperationContext {
    var executionStack: List<Data>
}
