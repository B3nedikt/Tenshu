package org.tenshu.core.common.util

import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.ValueSchema

val ValueSchema.length: Int
    get() {
        return when {
            isString -> -1
            isInteger -> 8
            isByteArray -> -1
            isBoolean -> 8
            else -> error("Unsupported type: $id")
        }
    }

val ValueSchema.isString: Boolean
    get() {
        return isParentA(TString.Schema)
    }

val ValueSchema.isInteger: Boolean
    get() {
        return isParentA(TInteger.Schema)
    }

val ValueSchema.isByteArray: Boolean
    get() {
        return isParentA(TByteArray.Schema)
    }

val ValueSchema.isBoolean: Boolean
    get() {
        return isParentA(TBoolean.Schema)
    }

fun ValueSchema.isParentA(schema: ValueSchema): Boolean {
    var current: DataSchema? = this
    val targetId = schema.id.get()
    while (current != null) {
        if (current.id.get() == targetId) return true
        current = current.parent
    }
    return false
}