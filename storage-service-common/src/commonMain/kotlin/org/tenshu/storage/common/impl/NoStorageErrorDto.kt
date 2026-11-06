package org.tenshu.storage.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.storage.NoStorageError

internal class NoStorageErrorDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), NoStorageError {

    override val schema: CompositeSchema
        get() = NoStorageError.Schema
}
