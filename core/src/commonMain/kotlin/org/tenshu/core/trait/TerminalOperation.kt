package org.tenshu.core.trait

import org.tenshu.core.schema.trait

interface TerminalOperation : Trait {

    companion object {
        const val ID = "tenshu/core/trait/terminalOperation"

        val Schema: TraitSchema = trait(ID)
    }
}
