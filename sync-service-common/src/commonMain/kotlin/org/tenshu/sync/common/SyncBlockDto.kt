package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.SyncBlock

internal class SyncBlockDto(
    fieldValues: List<Data?>
) : SyncBlock, CompositeDto(fieldValues)
