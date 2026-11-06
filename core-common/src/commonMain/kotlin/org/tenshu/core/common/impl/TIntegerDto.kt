package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.util.toLong
import org.tenshu.core.number.TInteger

open class TIntegerDto(
    private val rawValue: Any,
) : TInteger, TValueDto() {

    override val value: Data
        get() = this

    override fun get(): Long {
        if (rawValue is ByteArray) return rawValue.toLong()
        return rawValue as? Long ?: error("Value $rawValue is not a long")
    }

    override fun equals(other: Any?): Boolean {
        if (other !is TInteger) return true

        return this.get() == other.get()
    }

    override fun hashCode(): Int {
        return get().hashCode()
    }
}