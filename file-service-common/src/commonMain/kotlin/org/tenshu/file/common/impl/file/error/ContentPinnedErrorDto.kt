package org.tenshu.file.common.impl.file.error

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.system.ContentPinnedError

data class ContentPinnedErrorDto(
    private val fieldValues: List<Data?>
) : ContentPinnedError, CompositeDto(fieldValues)
