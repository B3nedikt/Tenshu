package org.tenshu.core.file.system

import org.tenshu.core.Service
import org.tenshu.core.file.Folder
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.toType

interface FileSystem : Service {

    override val schema: ServiceSchema
        get() = Schema

    val root: Folder

    companion object {
        const val ID = "tenshu/file/system"

        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.ROOT,
                    type = Folder.Schema.toType()
                )
            )
        )
    }

    object Relations {
        const val ROOT = "$ID/root"
    }
}
