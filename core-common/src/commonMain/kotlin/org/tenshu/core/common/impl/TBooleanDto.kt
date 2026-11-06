package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.common.util.toLong

class TBooleanDto(
    private val rawValue: Any,
) : TBoolean, TValueDto() {

    override val value: Data
        get() = this

    override fun get(): Boolean {
        if (rawValue is ByteArray) return rawValue.toLong() == 1L
        return rawValue as? Boolean ?: error("Value $rawValue is not a long")
    }

    override fun equals(other: Any?): Boolean {
        if (other !is TBoolean) return false

        return this.get() == other.get()
    }

    override fun hashCode(): Int {
        return get().hashCode()
    }
}