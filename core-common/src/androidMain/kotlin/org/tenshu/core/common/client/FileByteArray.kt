package org.tenshu.core.common.client

import org.tenshu.core.TByteArray
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.value

interface FileByteArray : TByteArray {

    override val schema: ValueSchema
        get() = Schema

    val fileId: ByteArray

    companion object {
        const val ID = "tenshu/core/array/android/fileByteArray"

        val Schema: ValueSchema = value(
            id = ID,
            parent = TByteArray.Schema,
        )
    }
}
