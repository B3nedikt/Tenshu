package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.SyncDelta

internal class SyncDeltaDto(
    fieldValues: List<Data?>
) : SyncDelta, CompositeDto(fieldValues)
