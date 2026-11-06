package org.tenshu.file.common.impl.file.error

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.system.MoveNeedsLocalBytesError

data class MoveNeedsLocalBytesErrorDto(
    private val fieldValues: List<Data?>
) : MoveNeedsLocalBytesError, CompositeDto(fieldValues)
