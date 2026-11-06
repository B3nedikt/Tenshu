package org.tenshu.core.relation

import org.tenshu.core.Data
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.reference.DynamicPropertyTypeReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list
import org.tenshu.core.schema.reference

sealed interface PropertyRelation : Relation {

    companion object {
        const val ID = "tenshu/core/relation/property/value"
        val Schema: CompositeSchema = composite(id = ID, parent = Relation.Schema)
    }
}

interface StaticPropertyRelation : PropertyRelation {

    val reference: PropertyTypeReference
        get() = getField(Fields.REFERENCE)

    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "${PropertyRelation.ID}/static"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = PropertyRelation.Schema,
            fields = {
                reference(Fields.REFERENCE, type = PropertyTypeReference.Schema)
            }
        )

        fun create(
            context: TContext? = null,
            reference: PropertyTypeReference,
        ): StaticPropertyRelation = LazyStaticPropertyRelation(context, reference)
    }

    object Fields {
        const val REFERENCE = "$ID/reference"
    }
}

interface DynamicPropertyRelation : PropertyRelation {

    val reference: DynamicPropertyTypeReference
        get() = getField(Fields.REFERENCE)

    val params: TList<Data>?
        get() = getOptionalField(Fields.PARAMS)

    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "${PropertyRelation.ID}/dynamic"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = PropertyRelation.Schema,
            fields = {
                reference(Fields.REFERENCE, type = DynamicPropertyTypeReference.Schema)
                list(Fields.PARAMS, of = Data.Schema, isOptional = true)
            }
        )

        fun create(
            context: TContext? = null,
            reference: DynamicPropertyTypeReference,
            params: TList<Data>? = null,
        ): DynamicPropertyRelation = LazyDynamicPropertyRelation(context, reference, params)
    }

    object Fields {
        const val REFERENCE = "$ID/reference"
        const val PARAMS = "$ID/params"
    }
}

private class LazyStaticPropertyRelation(
    private val context: TContext?,
    override val reference: PropertyTypeReference,
) : StaticPropertyRelation {

    private val delegate: StaticPropertyRelation by lazy {
        if (context == null) error("LazyStaticPropertyRelation materialization requires a context.")
        context.dataFactory().createComposite(
            type = StaticPropertyRelation.ID,
            fields = listOf(reference),
        )
    }

    override fun <T : Data> getField(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            StaticPropertyRelation.Fields.REFERENCE -> this.reference as T
            else -> delegate.getField(reference)
        }
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            StaticPropertyRelation.Fields.REFERENCE -> this.reference as T
            else -> delegate.getOptionalField(reference)
        }
    }
}

private class LazyDynamicPropertyRelation(
    private val context: TContext?,
    override val reference: DynamicPropertyTypeReference,
    private val initialParams: TList<Data>?,
) : DynamicPropertyRelation {

    override val params: TList<Data>?
        get() = initialParams

    private val delegate: DynamicPropertyRelation by lazy {
        if (context == null) error("LazyDynamicPropertyRelation materialization requires a context.")
        context.dataFactory().createComposite(
            type = DynamicPropertyRelation.ID,
            fields = listOfNotNull(reference, initialParams),
        )
    }

    override fun <T : Data> getField(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            DynamicPropertyRelation.Fields.REFERENCE -> this.reference as T
            else -> delegate.getField(reference)
        }
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return when (reference) {
            DynamicPropertyRelation.Fields.REFERENCE -> this.reference as T
            DynamicPropertyRelation.Fields.PARAMS -> initialParams as T?
            else -> delegate.getOptionalField(reference)
        }
    }
}

