package org.tenshu.file.impl

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.path
import org.tenshu.core.file.Folder
import org.tenshu.core.file.GetData
import org.tenshu.core.id

internal class GetDataImplementation(
    private val fileDatabase: FileDatabase
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val file = caller as? Composite
            ?: ((params?.get(GetData.Params.FILE) as? Composite)
                ?: error("Parameter ${GetData.Params.FILE} is required"))

        val typeId = if (file is Entity<*>) file.type.get() else file.id

        if (typeId == Folder.ID) {
            return null as T
        }

        // ⚠⚠ An `Entity` caller must read its path through the entity accessor, NOT
        // `getOptionalField`. `File.Field.PATH` is **mandatory** on `tenshu/file`, and
        // `getOptionalField` refuses mandatory fields by contract — it answers null (or throws
        // "…is mandatory…. Use getField() instead") rather than returning the value that is
        // demonstrably there. The `?: error(...)` below then read that null as "no path" and
        // failed the op.
        //
        // ⚠ This made `GetData` throw for **every** `Entity` caller, which is the shape a
        // `File.Preview` projection produces — so `GetPreview`'s compute-from-local-bytes branch
        // was unreachable through a projection, and a device that held a file's bytes still could
        // not compute a sharp preview from them. `JvmGetPreviewImplementation` reads the same field
        // correctly two lines earlier (`Entity<out File>.path`); this is that read, not a new idea.
        val path: String = when {
            file is File -> file.path.get()
            file is Entity<*> -> @Suppress("UNCHECKED_CAST") (file as Entity<out File>).path.get()
            else -> {
                val pathValue: Data = file.getOptionalField(File.Field.PATH)
                    ?: file.getOptionalField("path")
                    ?: error("Field ${File.Field.PATH} not found in caller")
                (pathValue as TString).get()
            }
        }

        val bytes = fileDatabase.readBytes(path) ?: return null as T

        return bytes as T
    }
}
