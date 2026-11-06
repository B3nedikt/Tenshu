package org.tenshu.core.common.app.impl

import org.tenshu.core.Data
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema

internal class AppRegistrationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), AppRegistration {

    override val schema: CompositeSchema
        get() = AppRegistration.Schema
}
