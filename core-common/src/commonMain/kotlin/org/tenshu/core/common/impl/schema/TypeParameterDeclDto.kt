package org.tenshu.core.common.impl.schema

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.TypeParameterDecl

class TypeParameterDeclDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), TypeParameterDecl {

    override val schema: CompositeSchema
        get() = TypeParameterDecl.Schema
}
