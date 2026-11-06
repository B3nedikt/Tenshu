package org.tenshu.core.trait

import org.tenshu.core.schema.trait

/**
 * An operation that can shortcut the execution of a callstack by returning a failure result.
 */
interface ShortcutOperation : Trait {

    companion object {
        const val ID = "tenshu/core/trait/shortcut-operation"

        val Schema: TraitSchema = trait(ID)
    }
}
