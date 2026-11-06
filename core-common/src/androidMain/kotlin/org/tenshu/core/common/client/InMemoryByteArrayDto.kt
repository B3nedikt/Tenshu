package org.tenshu.core.common.client

import org.tenshu.core.Data
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.util.md5Hash

class InMemoryByteArrayDto(
    private val byteArray: ByteArray
) : InMemoryByteArray {

    override val uniqueId: ByteArray by lazy {
        md5Hash(byteArray)
    }

    override val size: Long
        get() = byteArray.size.toLong()

    override fun get(): ByteArray {
        return byteArray
    }

    override val value: Data
        get() = TByteArrayDto(get())

    override fun equals(other: Any?): Boolean {
        if (other !is FileByteArray) return false

        return other.fileId.contentEquals(uniqueId)
    }

    override fun hashCode(): Int {
        return uniqueId.hashCode()
    }
}