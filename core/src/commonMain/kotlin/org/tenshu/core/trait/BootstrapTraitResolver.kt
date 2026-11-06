package org.tenshu.core.trait

object BootstrapTraitResolver : TraitResolver {
    override fun <T : Trait> getTrait(typeId: String, traitId: String): T? = null
}
