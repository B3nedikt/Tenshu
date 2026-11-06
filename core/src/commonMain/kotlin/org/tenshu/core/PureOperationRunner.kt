package org.tenshu.core

import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext

interface PureOperationRunner : TComponent {

    fun <T : Data?> execute(operation: Operation, caller: Data?): T

    companion object {
        const val ID = "tenshu/core/pure-operation-executor"
    }
}

fun TContext.pureOperationRunner(): PureOperationRunner =
    components[PureOperationRunner.ID] as? PureOperationRunner
        ?: error("PureOperationRunner not found in TContext components. Available: ${components.keys}")
