package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.DataBlockPayload

internal class DataBlockPayloadDto(
    fieldValues: List<Data?>
) : DataBlockPayload, CompositeDto(fieldValues)
