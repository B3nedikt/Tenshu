package org.tenshu.core.file

import org.tenshu.core.Operation
import org.tenshu.core.TByteArray
import org.tenshu.core.core.findOperation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.TerminalOperation

/**
 * Builder extension for building the GetData operation.
 */
fun File.getData(): TByteArray =
    findOperation<GetData>(GetData.ID).getData(this)

interface GetData : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getData(file: File): TByteArray

    companion object {
        const val ID = "tenshu/file/getData"

        val Schema = operation(
            id = ID,
            receiver = File.Schema.toType(),
            params = {
                composite(id = Params.FILE, type = File.Schema)
            },
            traits = listOf(TerminalOperation.Schema)
        )
    }

    object Params {
        const val FILE = "$ID/file"
    }
}
