package org.tenshu.ingest.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.schema.CompositeSchema

internal class IngestSourceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), IngestSource {

    override val schema: CompositeSchema
        get() = IngestSource.Schema
}
