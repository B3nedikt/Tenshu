package org.tenshu.file.common.impl.file.folder

import org.tenshu.core.Data
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.file.common.impl.file.FileDto
import org.tenshu.file.common.impl.file.FilePathDto

internal class FolderDto(
    fieldValues: List<Data?>,
) : FileDto(fieldValues), Folder {

    override val schema: CompositeSchema
        get() = Folder.Schema

    constructor(
        path: String,
        files: List<File>,
    ) : this(
        fieldValues = listOf(
            FilePathDto(path),
            TListDto(files)
        )
    )
}
