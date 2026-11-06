package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.EdgedPush

internal class EdgedPushDto(
    fieldValues: List<Data?>
) : EdgedPush, CompositeDto(fieldValues)
