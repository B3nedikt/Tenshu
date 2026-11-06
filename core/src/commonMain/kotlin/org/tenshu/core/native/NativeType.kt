package org.tenshu.core.native

import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.reference.TypeReference
import org.tenshu.core.schema.reference

interface NativeType : TypeReference {

    companion object {
        const val ID = "tenshu/native/type"

        val Schema: ReferenceSchema = reference(id = ID)
    }
}
