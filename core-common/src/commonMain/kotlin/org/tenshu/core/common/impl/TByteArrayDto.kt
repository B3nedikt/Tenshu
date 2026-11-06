package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.TByteArray

open class TByteArrayDto(
    private val rawValue: Any
) : TByteArray, TValueDto() {

    override val value: Data
        get() = this

    override val size: Long
        get() = get().size.toLong()

    override fun get(): ByteArray {
        return rawValue as ByteArray
    }

    override fun equals(other: Any?): Boolean {
        if (other !is TByteArray) return false

        return this.get().contentEquals(other.get())
    }

    override fun hashCode(): Int {
        return get().hashCode()
    }
}