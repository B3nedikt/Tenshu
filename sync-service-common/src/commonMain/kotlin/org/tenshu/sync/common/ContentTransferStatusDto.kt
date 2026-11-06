package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.ContentTransferStatus

internal class ContentTransferStatusDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ContentTransferStatus {

    override val schema: CompositeSchema
        get() = ContentTransferStatus.Schema
}
