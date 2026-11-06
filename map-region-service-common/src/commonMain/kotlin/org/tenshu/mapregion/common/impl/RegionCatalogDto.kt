package org.tenshu.mapregion.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.mapregion.RegionCatalog
import org.tenshu.core.schema.CompositeSchema

internal class RegionCatalogDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), RegionCatalog {

    override val schema: CompositeSchema get() = RegionCatalog.Schema
}
