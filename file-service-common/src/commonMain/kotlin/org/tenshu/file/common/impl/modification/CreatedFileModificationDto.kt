package org.tenshu.file.common.impl.modification

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.CreatedFileModification

internal class CreatedFileModificationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), CreatedFileModification
