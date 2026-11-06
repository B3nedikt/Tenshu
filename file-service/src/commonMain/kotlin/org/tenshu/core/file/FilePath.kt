package org.tenshu.core.file

import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.value

interface FilePath : TString {

    override val schema: ValueSchema
        get() = Schema

    val segments: List<String>

    companion object {
        const val ID = "tenshu/file/filePath"

        val Schema: ValueSchema = value(
            id = ID,
            parent = TString.Schema,
        )

        fun create(
            context: TContext,
            path: String
        ): FilePath {
            return context.dataFactory().createValue(ID, path) as FilePath
        }
    }
}
