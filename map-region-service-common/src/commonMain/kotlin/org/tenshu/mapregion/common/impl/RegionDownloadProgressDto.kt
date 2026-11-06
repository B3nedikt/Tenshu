package org.tenshu.mapregion.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.mapregion.RegionDownloadProgress
import org.tenshu.core.schema.CompositeSchema

internal class RegionDownloadProgressDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), RegionDownloadProgress {

    override val schema: CompositeSchema get() = RegionDownloadProgress.Schema
}
