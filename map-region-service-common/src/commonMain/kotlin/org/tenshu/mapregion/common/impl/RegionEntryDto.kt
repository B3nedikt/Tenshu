package org.tenshu.mapregion.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.mapregion.RegionEntry
import org.tenshu.core.schema.CompositeSchema

internal class RegionEntryDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), RegionEntry {

    override val schema: CompositeSchema get() = RegionEntry.Schema
}
