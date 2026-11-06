package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.DeleteBlock

internal class DeleteBlockDto(
    fieldValues: List<Data?>
) : DeleteBlock, CompositeDto(fieldValues)
