package org.tenshu.storage.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.storage.InsufficientStorageError

internal class InsufficientStorageErrorDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), InsufficientStorageError {

    override val schema: CompositeSchema
        get() = InsufficientStorageError.Schema
}
