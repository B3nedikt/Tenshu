package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.lazyDataMaterializer
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.DataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitRequirement
import org.tenshu.core.trait.TraitRequirementSchema
import org.tenshu.core.trait.TraitResolver
import org.tenshu.core.trait.TraitSchema
import org.tenshu.core.trait.ValueTraitRequirement
import org.tenshu.core.trait.ValueTraitRequirementSchema

class TraitResolverImpl(
    private val tContextProvider: () -> TContext
) : TraitResolver {

    private val context by lazy { tContextProvider() }
    private val schemaProvider get() = context.schemaProvider()
    private val materializer get() = context.lazyDataMaterializer()

    override fun <T : Trait> getTrait(typeId: String, traitId: String): T? {
        val schema = schemaProvider.getSchema(typeId)
            ?: return null

        return findTraitInTypeHierarchy(schema, traitId)
    }

    private fun <T : Trait> findTraitInTypeHierarchy(schema: DataSchema, traitId: String): T? {
        if (schema is CompositeSchema) {
            val traits = schema.traits.toList()
            for (i in traits.indices.reversed()) {
                var traitSchema = traits[i]
                if (traitSchema.isSubtypeOf(traitId)) {

                    if (traitSchema is org.tenshu.core.schema.LazyDataCreator<*>) {
                        @Suppress("UNCHECKED_CAST")
                        traitSchema = materializer.getOrCreate(
                            traitSchema as org.tenshu.core.schema.LazyDataCreator<Composite>
                        ) as TraitSchema
                    }

                    @Suppress("UNCHECKED_CAST")
                    var trait = schemaProvider.getTrait(schema.id.get(), traitSchema) as? T

                    if (trait == null) {
                        // Try to find a factory for the same trait in the type hierarchy
                        var currentParent = schema.parent
                        while (currentParent != null && trait == null) {
                            trait =
                                schemaProvider.getTrait(currentParent.id.get(), traitSchema) as? T
                            currentParent = currentParent.parent
                        }
                    }

                    if (trait != null) return trait

                    // If no explicit trait factory registered, but the traitSchema itself
                    // is a composite that contains the trait fields (like Syncable DSL),
                    // we wrap it.
                    val wrapped = object : Trait, Composite by (traitSchema as Composite) {
                        override val schema: TraitSchema = traitSchema

                        override val traitRequirements: TList<TraitRequirement>
                            get() = traitSchema.traitRequirements.toList().map { reqSchema ->
                                val schemaObj =
                                    if (reqSchema is org.tenshu.core.schema.LazyDataCreator<*>) {
                                        @Suppress("UNCHECKED_CAST")
                                        materializer.getOrCreate(
                                            reqSchema as org.tenshu.core.schema.LazyDataCreator<Composite>
                                        )
                                    } else {
                                        reqSchema
                                    }

                                if (schemaObj is ValueTraitRequirementSchema) {
                                    object : ValueTraitRequirement<Data, Data>,
                                        Composite by (schemaObj as Composite) {
                                        override val schema: ValueTraitRequirementSchema = schemaObj
                                    }
                                } else {
                                    object : TraitRequirement,
                                        Composite by (schemaObj as Composite) {
                                        override val schema: TraitRequirementSchema =
                                            schemaObj as TraitRequirementSchema
                                    }
                                }
                            }.toTList(context)
                    }

                    if (traitId == Syncable.ID) {
                        val syncable = object : Syncable, Trait by wrapped {
                            override val strategy: SyncStrategy
                                get() = wrapped.getField<SyncStrategy>(
                                    Syncable.Fields.STRATEGY
                                )
                            override val conflictStrategy: ConflictStrategy
                                get() = wrapped.getField<ConflictStrategy>(
                                    Syncable.Fields.CONFLICT_STRATEGY
                                )
                        }
                        @Suppress("UNCHECKED_CAST")
                        return syncable as Any as T
                    }

                    if (traitId == DataIdProvider.ID) {
                        val provider = object : DataIdProvider<Data>, Trait by wrapped {
                            override val schema get() = wrapped.schema as DataIdProviderSchema
                        }
                        @Suppress("UNCHECKED_CAST")
                        return provider as Any as T
                    }

                    @Suppress("UNCHECKED_CAST")
                    return wrapped as Any as T
                }
            }
        }

        val parent = schema.parent
        if (parent != null) {
            return findTraitInTypeHierarchy(parent, traitId)
        }

        return null
    }
}
