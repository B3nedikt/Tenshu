package org.tenshu.file.common.impl.file

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.File

internal open class FileDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), File {

    constructor(
        path: String,
    ) : this(
        fieldValues = listOf(
            FilePathDto(path),
        )
    )
}