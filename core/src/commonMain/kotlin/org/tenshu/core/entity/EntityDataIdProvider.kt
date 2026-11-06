package org.tenshu.core.entity

import org.tenshu.core.Data
import org.tenshu.core.schema.trait

interface EntityDataIdProvider : CompositeDataIdProvider<Entity<Data>> {

    companion object {
        const val ID = "tenshu/core/entity/dataIdProvider"

        val Schema: CompositeDataIdProviderSchema = trait(
            id = ID,
            parent = CompositeDataIdProvider.Schema
        )
    }
}
