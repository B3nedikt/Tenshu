package org.tenshu.file.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileService
import org.tenshu.core.file.share.Share
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class FileServiceRo(
    requestChain: RequestChain,
    context: TContext
) : ServiceRo(requestChain, context), FileService {
    override val schema: ServiceSchema get() = FileService.Schema

    override val defaultFileSystem: FileSystem
        get() = context.roProvider().createRequestObject(
            type = FileSystem.Schema.toType(),
            requestChain = requestChain.addReference(FileService.Relations.DEFAULT_FILE_SYSTEM),
            context = context
        )

    // A serviceDataRelation (realm-wide list) is addressed off the service
    // itself, so `serviceRelation` prepends the service reference before the
    // relation. (The plain `serviceRelation` field `defaultFileSystem` above
    // doesn't need it.)
    override val modifications: TList<FileModification>
        get() = serviceRelation(FileService.Relations.MODIFICATIONS, FileModification.Schema)

    // Realm-wide *My shares* projection (docs/file-sharing.md).
    override val shares: TList<Share>
        get() = serviceRelation(FileService.Relations.SHARES, Share.Schema)
}
