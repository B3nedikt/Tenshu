package org.tenshu.inference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.inference.InstalledModel

internal class InstalledModelDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), InstalledModel {

    override val schema: CompositeSchema get() = InstalledModel.Schema
}
