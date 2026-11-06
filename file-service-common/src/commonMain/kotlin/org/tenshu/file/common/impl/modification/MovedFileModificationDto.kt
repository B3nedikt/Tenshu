package org.tenshu.file.common.impl.modification

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.MovedFileModification

internal class MovedFileModificationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MovedFileModification
