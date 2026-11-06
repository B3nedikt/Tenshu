package org.tenshu.core.common.impl.schema

import org.tenshu.core.ChainTemplate
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.RelationSchema
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.TypeParameterDecl
import org.tenshu.core.schema.UnionSchema
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.trait.TraitRequirementSchema
import org.tenshu.core.trait.TraitSchema
import org.tenshu.core.trait.ValueTraitRequirementSchema

abstract class SchemaDto(
    protected val fieldValues: List<Data?>,
) : CompositeDto(fieldValues), DataSchema {

    override val id: TString
        get() = fieldValues[0] as TString

    override val parent: DataSchema?
        get() = fieldValues[1] as? DataSchema

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? {
        return when (reference) {
            DataSchema.Fields.SCHEMA_ID -> id as T
            DataSchema.Fields.PARENT -> parent as T?
            else -> super.getOptionalField(reference)
        }
    }
}

class CompositeSchemaDto(
    fieldValues: List<Data?>,
) : SchemaDto(fieldValues), CompositeSchema {

    override val schema: CompositeSchema
        get() = CompositeSchema.Schema

    @Suppress("UNCHECKED_CAST")
    override val values: TList<Field>
        get() = fieldValues[2] as TList<Field>

    @Suppress("UNCHECKED_CAST")
    override val traits: TList<TraitSchema>
        get() = fieldValues[3] as TList<TraitSchema>

    @Suppress("UNCHECKED_CAST")
    override val typeParams: TList<TypeParameterDecl>
        get() = (fieldValues.getOrNull(4) as? TList<TypeParameterDecl>)
            ?: org.tenshu.core.collection.list.SimpleTList(emptyList())

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? {
        return when (reference) {
            CompositeSchema.Fields.VALUES -> values as T
            CompositeSchema.Fields.TRAITS -> traits as T
            CompositeSchema.Fields.TYPE_PARAMS -> typeParams as T
            else -> super.getOptionalField(reference)
        }
    }
}

open class OperationSchemaDto(
    fieldValues: List<Data?>,
) : SchemaDto(fieldValues), OperationSchema {

    override val schema: CompositeSchema
        get() = OperationSchema.Schema

    @Suppress("UNCHECKED_CAST")
    override val params: TList<Field>
        get() = fieldValues[4] as TList<Field>

    @Suppress("UNCHECKED_CAST")
    override val values: TList<Field>
        get() = fieldValues[2] as TList<Field>

    @Suppress("UNCHECKED_CAST")
    override val traits: TList<TraitSchema>
        get() = fieldValues[3] as TList<TraitSchema>

    @Suppress("UNCHECKED_CAST")
    override val returnType: Type
        get() = fieldValues[5] as Type

    override val receiver: Type?
        get() = fieldValues.getOrNull(6) as? Type

    @Suppress("UNCHECKED_CAST")
    override val typeParams: TList<TypeParameterDecl>
        get() = (fieldValues.getOrNull(7) as? TList<TypeParameterDecl>)
            ?: org.tenshu.core.collection.list.SimpleTList(emptyList())

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? {
        return when (reference) {
            OperationSchema.Fields.PARAMS -> params as T
            CompositeSchema.Fields.VALUES -> values as T
            CompositeSchema.Fields.TRAITS -> traits as T
            OperationSchema.Fields.RETURN_TYPE -> returnType as T
            OperationSchema.Fields.RECEIVER -> receiver as T?
            OperationSchema.Fields.TYPE_PARAMS -> typeParams as T
            else -> super.getOptionalField(reference)
        }
    }
}

class TraitRequirementSchemaDto(
    fieldValues: List<Data?>,
) : OperationSchemaDto(fieldValues), TraitRequirementSchema {

    override val schema: CompositeSchema
        get() = TraitRequirementSchema.Schema
}

class ValueTraitRequirementSchemaDto(
    fieldValues: List<Data?>,
) : OperationSchemaDto(fieldValues), ValueTraitRequirementSchema {

    override val schema: CompositeSchema
        get() = ValueTraitRequirementSchema.Schema
}

class ValueSchemaDto(
    fieldValues: List<Data?>,
) : SchemaDto(fieldValues), ValueSchema {

    override val schema: CompositeSchema
        get() = ValueSchema.Schema
}

class ConstantSchemaDto(
    fieldValues: List<Data?>,
) : SchemaDto(fieldValues), ConstantSchema {

    override val schema: CompositeSchema
        get() = ConstantSchema.Schema
}

class ReferenceSchemaDto(
    fieldValues: List<Data?>,
) : SchemaDto(fieldValues), ReferenceSchema {

    override val schema: CompositeSchema
        get() = ConstantSchema.Schema
}

class ServiceSchemaDto(
    fieldValues: List<Data?>,
) : SchemaDto(fieldValues), ServiceSchema {

    @Suppress("UNCHECKED_CAST")
    override val relationSchemas: TList<RelationSchema>
        get() = fieldValues[2] as TList<RelationSchema>

    override val schema: CompositeSchema
        get() = DataSchema.Schema // Use generic DataSchema.Schema for now

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? {
        return when (reference) {
            ServiceSchema.Fields.RELATIONS -> relationSchemas as T
            else -> super.getOptionalField(reference)
        }
    }
}

class PropertyRelationSchemaDto(
    fieldValues: List<Data?>,
) : SchemaDto(fieldValues), RelationSchema.PropertyRelationSchema {

    @Suppress("UNCHECKED_CAST")
    override val values: TList<Field>
        get() = fieldValues[2] as TList<Field>

    override val schema: CompositeSchema
        get() = RelationSchema.PropertyRelationSchema.Schema

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? {
        return when (reference) {
            CompositeSchema.Fields.VALUES -> values as T
            else -> super.getOptionalField(reference)
        }
    }
}

class DynamicPropertyRelationSchemaDto(
    fieldValues: List<Data?>,
    override val callstack: ChainTemplate<*, *>
) : SchemaDto(fieldValues), RelationSchema.DynamicPropertyRelationSchema {

    @Suppress("UNCHECKED_CAST")
    override val params: TList<DataSchema>?
        get() = fieldValues.getOrNull(5) as? TList<DataSchema>

    @Suppress("UNCHECKED_CAST")
    override val chain: TList<Data>?
        get() = fieldValues.getOrNull(6) as? TList<Data>

    override val schema: CompositeSchema
        get() = RelationSchema.DynamicPropertyRelationSchema.Schema

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? {
        return when (reference) {
            RelationSchema.DynamicPropertyRelationSchema.Fields.PARAMS -> params as T?
            RelationSchema.DynamicPropertyRelationSchema.Fields.CHAIN -> chain as T?
            else -> super.getOptionalField(reference)
        }
    }
}

class ServiceRelationSchemaDto(
    fieldValues: List<Data?>,
) : SchemaDto(fieldValues), RelationSchema.ServiceRelationSchema {

    override val schema: CompositeSchema
        get() = RelationSchema.ServiceRelationSchema.Schema
}

class ServiceDataRelationSchemaDto(
    fieldValues: List<Data?>,
) : SchemaDto(fieldValues), RelationSchema.ServiceDataRelationSchema {

    @Suppress("UNCHECKED_CAST")
    override val type: Type
        get() = fieldValues[2] as Type

    override val schema: CompositeSchema
        get() = RelationSchema.ServiceDataRelationSchema.Schema

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? {
        return when (reference) {
            RelationSchema.ServiceDataRelationSchema.Fields.TYPE -> type as T
            else -> super.getOptionalField(reference)
        }
    }
}

class UnionSchemaDto(
    fieldValues: List<Data?>,
) : SchemaDto(fieldValues), UnionSchema {

    @Suppress("UNCHECKED_CAST")
    override val members: TList<DataSchema>
        get() = fieldValues[4] as TList<DataSchema>

    override val schema: CompositeSchema
        get() = UnionSchema.Schema

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? {
        return when (reference) {
            UnionSchema.Fields.MEMBERS -> members as T
            else -> super.getOptionalField(reference)
        }
    }
}
