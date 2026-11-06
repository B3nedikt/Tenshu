package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.FileChunkRef

internal class FileChunkRefDto(
    fieldValues: List<Data?>
) : FileChunkRef, CompositeDto(fieldValues)
