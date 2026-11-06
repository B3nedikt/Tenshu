package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.sync.ContentStreamHandle

internal class ContentStreamHandleDto(
    fieldValues: List<Data?>
) : ContentStreamHandle, CompositeDto(fieldValues)
