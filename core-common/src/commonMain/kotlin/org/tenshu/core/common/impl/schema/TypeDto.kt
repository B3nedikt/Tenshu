package org.tenshu.core.common.impl.schema

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.GenericType
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.TypeVariable
import org.tenshu.core.schema.UnionType

open class TypeDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Type {

    override val schema: CompositeSchema
        get() = Type.Schema
}

class GenericTypeDto(
    fieldValues: List<Data?>,
) : TypeDto(fieldValues), GenericType {

    override val schema: CompositeSchema
        get() = GenericType.Schema
}

class UnionTypeDto(
    fieldValues: List<Data?>,
) : TypeDto(fieldValues), UnionType {

    override val schema: CompositeSchema
        get() = UnionType.Schema
}

class TypeVariableDto(
    fieldValues: List<Data?>,
) : TypeDto(fieldValues), TypeVariable {

    override val schema: CompositeSchema
        get() = TypeVariable.Schema
}
