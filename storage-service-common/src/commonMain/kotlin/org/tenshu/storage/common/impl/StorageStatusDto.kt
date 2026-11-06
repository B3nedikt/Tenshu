package org.tenshu.storage.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.storage.StorageStatus

internal class StorageStatusDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), StorageStatus {

    override val schema: CompositeSchema
        get() = StorageStatus.Schema
}
