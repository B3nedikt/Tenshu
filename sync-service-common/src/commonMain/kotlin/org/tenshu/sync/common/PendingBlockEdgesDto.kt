package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto

internal class PendingBlockEdgesDto(
    fieldValues: List<Data?>
) : PendingBlockEdges, CompositeDto(fieldValues)
