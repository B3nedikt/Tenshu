package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto

internal class RemovedContentsDto(
    fieldValues: List<Data?>
) : RemovedContents, CompositeDto(fieldValues)
