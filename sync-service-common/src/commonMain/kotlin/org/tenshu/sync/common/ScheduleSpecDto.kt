package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.ScheduleSpec

internal class ScheduleSpecDto(
    fieldValues: List<Data?>
) : ScheduleSpec, CompositeDto(fieldValues)
