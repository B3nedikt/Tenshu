package org.tenshu.file.common.impl.modification

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.ContentChangedFileModification

internal class ContentChangedFileModificationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ContentChangedFileModification
