package org.tenshu.core.trait

import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext

interface TraitResolver : TComponent {

    fun <T : Trait> getTrait(typeId: String, traitId: String): T?

    companion object {
        const val ID = "tenshu/core/trait-resolver"
    }
}

fun TContext.traitResolver(): TraitResolver = components[TraitResolver.ID] as TraitResolver
