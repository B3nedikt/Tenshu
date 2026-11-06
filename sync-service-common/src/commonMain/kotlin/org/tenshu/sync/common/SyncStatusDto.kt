package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.SyncStatus

internal class SyncStatusDto(
    fieldValues: List<Data?>
) : SyncStatus, CompositeDto(fieldValues)
