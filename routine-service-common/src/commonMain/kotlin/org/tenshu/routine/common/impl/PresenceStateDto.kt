package org.tenshu.routine.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.routine.PresenceState

internal class PresenceStateDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), PresenceState {

    override val schema: CompositeSchema
        get() = PresenceState.Schema
}
