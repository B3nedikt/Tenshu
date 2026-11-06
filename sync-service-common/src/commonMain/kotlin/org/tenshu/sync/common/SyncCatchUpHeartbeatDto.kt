package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.SyncCatchUpHeartbeat

internal class SyncCatchUpHeartbeatDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), SyncCatchUpHeartbeat {

    override val schema: CompositeSchema
        get() = SyncCatchUpHeartbeat.Schema
}
