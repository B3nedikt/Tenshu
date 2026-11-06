package org.tenshu.core.common.app.impl

import org.tenshu.core.Data
import org.tenshu.core.app.AppGrant
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema

internal class AppGrantDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), AppGrant {

    override val schema: CompositeSchema
        get() = AppGrant.Schema
}
