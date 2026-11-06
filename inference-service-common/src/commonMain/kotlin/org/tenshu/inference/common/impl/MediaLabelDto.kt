package org.tenshu.inference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.inference.MediaLabel

internal class MediaLabelDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MediaLabel {

    override val schema: CompositeSchema get() = MediaLabel.Schema
}
