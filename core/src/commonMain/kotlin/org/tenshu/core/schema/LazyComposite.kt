package org.tenshu.core.schema

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.trait.Trait
import org.tenshu.core.trait.TraitSchema

/**
 * A [Composite] that represents a definition that can materialize itself using a [TContext].
 * This is used to define schemas and objects globally (as singletons) but have them 
 * interact with platform-specific components at runtime.
 *
 * IMPORTANT: Instances of [LazyComposite] (like schema objects) are stateless. 
 * They do NOT cache their materialized result internally. Any stateful materialization
 * must be handled by the caller (e.g., [SchemaRegistry]).
 */
abstract class LazyComposite<T : Composite>(
    private val materializeFunc: (context: TContext) -> T,
    private val lazyFields: () -> Map<String, Any?> = { emptyMap() }
) : Composite, LazyDataCreator<T> {

    private val cachedLazyFields by lazy { lazyFields() }

    override val schema: CompositeSchema
        get() {
            val schemaId = getOptionalField<TString>(DataSchema.Fields.SCHEMA_ID)?.get()
            return if (schemaId == DataSchema.ID ||
                schemaId == CompositeSchema.ID ||
                schemaId == TraitSchema.ID ||
                schemaId == Trait.ID ||
                schemaId == OperationSchema.ID ||
                schemaId == ServiceSchema.ID ||
                schemaId == ValueSchema.ID ||
                schemaId == ConstantSchema.ID ||
                schemaId == ReferenceSchema.ID ||
                schemaId == Reference.ID ||
                schemaId == UnionSchema.ID ||
                schemaId == org.tenshu.core.collection.list.Stream.ID ||
                schemaId == org.tenshu.core.StreamElement.Value.ID ||
                schemaId == org.tenshu.core.StreamElement.Removed.ID ||
                schemaId == org.tenshu.core.StreamElement.End.ID ||
                schemaId?.startsWith(RelationSchema.ID) == true ||
                schemaId?.endsWith("Schema") == true ||
                schemaId?.endsWith("Requirement") == true ||
                schemaId?.contains("dataId") == true ||
                schemaId?.contains("Provider") == true
            ) {
                this as CompositeSchema
            } else {
                CompositeSchema.Schema
            }
        }

    override fun materialize(context: TContext): T = materializeFunc(context)

    override fun <R : Data> getField(reference: String): R {
        return getOptionalField(reference)
            ?: error(
                "Field '$reference' is not in the lazy field map of ${this::class.simpleName} (${
                    getOptionalField<TString>(
                        DataSchema.Fields.SCHEMA_ID
                    )?.get()
                }). " +
                        "Either add it to the lazyFields map of the DSL builder, or retrieve the fully materialized form " +
                        "from the context's schema provider."
            )
    }

    override fun <R : Data> getOptionalField(reference: String): R? {
        if (cachedLazyFields.containsKey(reference)) {
            @Suppress("UNCHECKED_CAST")
            return cachedLazyFields[reference] as? R
        }
        return null
    }
}
