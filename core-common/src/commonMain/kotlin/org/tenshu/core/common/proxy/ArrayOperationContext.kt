package org.tenshu.core.common.proxy

import org.tenshu.core.Data

data class ArrayOperationContext(
    val arrayDatabaseReference: ArrayDatabaseReference,
    override var executionStack: List<Data>
) : OperationContext
