package org.tenshu.core.common.impl.reference

import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.schema.reference

abstract class ReferenceDto(
    private val typeString: String
) : Reference {

    override fun get(): String {
        return typeString
    }

    override val schema: ReferenceSchema
        get() = reference(typeString)

    override fun equals(other: Any?): Boolean {
        if (other !is Reference) return false
        return this.get().contentEquals(other.get())
    }

    override fun hashCode(): Int {
        return get().hashCode()
    }
}