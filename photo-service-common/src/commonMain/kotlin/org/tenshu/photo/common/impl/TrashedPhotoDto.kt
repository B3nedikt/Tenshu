package org.tenshu.photo.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.photo.TrashedPhoto

internal class TrashedPhotoDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), TrashedPhoto {

    override val schema: CompositeSchema
        get() = TrashedPhoto.Schema
}
