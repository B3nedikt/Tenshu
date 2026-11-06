package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.SleepSession
import org.tenshu.core.schema.CompositeSchema

internal class SleepSessionDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), SleepSession {

    override val schema: CompositeSchema get() = SleepSession.Schema
}
