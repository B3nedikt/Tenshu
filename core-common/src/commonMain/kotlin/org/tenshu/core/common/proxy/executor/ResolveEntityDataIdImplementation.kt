package org.tenshu.core.common.proxy.executor

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.executePure
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.trait.traitResolver

internal class ResolveEntityDataIdImplementation : PureOperationImplementation {

    override fun <T : Data?> execute(
        context: TContext,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {

        @Suppress("UNCHECKED_CAST")
        val entity = caller as Entity<Data>
        val type = entity.type.get()

        val provider = context.traitResolver().getTrait<CompositeDataIdProvider<Composite>>(
            typeId = type,
            traitId = DataIdProvider.ID
        ) ?: error("DataIdProvider not found for type $type")

        val requirement = provider.traitRequirements.toList().find {
            it.schema.isSubtypeOf(DataIdProvider.Requirements.DATA_ID)
        } ?: error("DATA_ID requirement not found in provider for $type")

        return requirement.executePure(
            context = context,
            caller = entity,
            factory = implementationFactory
        )
    }
}
