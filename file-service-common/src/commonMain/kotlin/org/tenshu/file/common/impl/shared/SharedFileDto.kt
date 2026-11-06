package org.tenshu.file.common.impl.shared

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.shared.SharedFile

internal class SharedFileDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), SharedFile
