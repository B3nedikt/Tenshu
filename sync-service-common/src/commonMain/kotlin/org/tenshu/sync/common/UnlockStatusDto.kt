package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.UnlockStatus

internal class UnlockStatusDto(
    fieldValues: List<Data?>
) : UnlockStatus, CompositeDto(fieldValues)
