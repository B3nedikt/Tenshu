package org.tenshu.core.common.client

import org.tenshu.core.Data
import org.tenshu.core.common.impl.TByteArrayDto
import java.io.File

class FileByteArrayDto(
    private val file: File
) : FileByteArray {

    override val fileId: ByteArray
        get() = file.absolutePath.toByteArray()

    override val size: Long
        get() = file.length()

    override fun get(): ByteArray {
        return file.readBytes()
    }

    override val value: Data
        get() = TByteArrayDto(get())

    override fun equals(other: Any?): Boolean {
        if (other !is FileByteArray) return false

        return other.fileId.contentEquals(fileId)
    }

    override fun hashCode(): Int {
        return fileId.hashCode()
    }
}
