package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.ChangesResponse

internal class ChangesResponseDto(
    fieldValues: List<Data?>
) : ChangesResponse, CompositeDto(fieldValues)
