package org.tenshu.core.common.util

import org.tenshu.core.collection.list.toList
import org.tenshu.core.reference.Reference
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.GenericData
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.params

/**
 * A field declared via `genericData(...)` — its runtime value type isn't known
 * statically, so the DB layer stores it as a self-describing
 * `ObjectStore.DbValue.GenericValue`. Checked *before* [isCompositeField] in the
 * mapper (GenericData is itself a composite schema). See [GenericData].
 */
val Field.isGenericDataField: Boolean
    get() = type.baseSchema?.id?.get() == GenericData.ID

val Field.isCompositeField: Boolean
    get() = type.baseSchema is CompositeSchema

val Field.isValueField: Boolean
    get() = type.baseSchema is ValueSchema

val Field.isConstantField: Boolean
    get() = type.baseSchema is ConstantSchema

val Field.isParameterized: Boolean
    get() = type.params.toList().isNotEmpty() && type.baseSchema != Reference.Schema

val Field.length: Int
    get() {
        if (isOptional.get()) return -1
        if (isParameterized) return -1

        return when {
            isConstantField -> 16
            isValueField -> (type.baseSchema as ValueSchema).length
            isCompositeField -> -1
            else -> -1
        }
    }
