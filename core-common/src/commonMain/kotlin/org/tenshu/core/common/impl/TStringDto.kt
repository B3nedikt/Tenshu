package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.TString

open class TStringDto(
    private val rawValue: Any
) : TString, TValueDto() {

    override val value: Data
        get() = this

    override fun get(): String {
        if (rawValue is ByteArray) return rawValue.decodeToString()
        return rawValue as? String ?: error("rawValue $rawValue is not a string")
    }

    override fun equals(other: Any?): Boolean {
        if (other !is TString) return false

        return this.get() == other.get()
    }

    override fun hashCode(): Int {
        return get().hashCode()
    }

    override fun toString(): String = get()
}
