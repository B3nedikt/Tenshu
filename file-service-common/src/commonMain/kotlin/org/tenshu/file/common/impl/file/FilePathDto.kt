package org.tenshu.file.common.impl.file

import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.file.FilePath

internal class FilePathDto(
    value: Any
) : FilePath, TStringDto(value) {

    override val segments: List<String>
        get() = get().split("/")
}