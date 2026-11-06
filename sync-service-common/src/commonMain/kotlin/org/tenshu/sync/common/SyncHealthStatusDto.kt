package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.SyncHealthStatus

internal class SyncHealthStatusDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), SyncHealthStatus {

    override val schema: CompositeSchema
        get() = SyncHealthStatus.Schema
}
