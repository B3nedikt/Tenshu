package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.TombstonesResponse

internal class TombstonesResponseDto(
    fieldValues: List<Data?>
) : TombstonesResponse, CompositeDto(fieldValues)
