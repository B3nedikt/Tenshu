package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.SyncCatchUpStatus

internal class SyncCatchUpStatusDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), SyncCatchUpStatus {

    override val schema: CompositeSchema
        get() = SyncCatchUpStatus.Schema
}
