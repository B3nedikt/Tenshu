package org.tenshu.file.common.impl.file.error

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.system.AlreadyExistsError

data class AlreadyExistsErrorDto(
    private val fieldValues: List<Data?>
) : AlreadyExistsError, CompositeDto(fieldValues)
