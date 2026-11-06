package org.tenshu.file.client.impl

import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.toType

internal class FileSystemRo(
    requestChain: RequestChain,
    context: TContext
) : ServiceRo(requestChain, context), FileSystem {
    override val schema: ServiceSchema get() = FileSystem.Schema

    override val root: Folder
        get() = context.roProvider().createRequestObject(
            type = Folder.Schema.toType(),
            requestChain = requestChain.addReference(FileSystem.Relations.ROOT),
            context = context
        )
}
