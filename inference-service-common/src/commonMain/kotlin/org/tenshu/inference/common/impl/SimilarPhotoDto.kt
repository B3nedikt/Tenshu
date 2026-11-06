package org.tenshu.inference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.inference.SimilarPhoto

internal class SimilarPhotoDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), SimilarPhoto {

    override val schema: CompositeSchema get() = SimilarPhoto.Schema
}
