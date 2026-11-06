package org.tenshu.core

import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.operation
import org.tenshu.core.trait.PureOperation
import org.tenshu.core.trait.ShortcutOperation
import org.tenshu.core.trait.TerminalOperation

interface Operation : Composite {

    override val schema: OperationSchema
        get() = Schema

    val params: Map<String, Data>
        get() = fields

    companion object {
        const val ID = "tenshu/core/operation"

        val Schema: OperationSchema = operation(id = ID)
    }
}

fun Operation.isTerminal(): Boolean =
    schema.hasTrait(TerminalOperation.ID)

fun Operation.isPure(): Boolean =
    schema.hasTrait(PureOperation.ID)

fun Operation.isShortcut(): Boolean =
    schema.hasTrait(ShortcutOperation.ID)
