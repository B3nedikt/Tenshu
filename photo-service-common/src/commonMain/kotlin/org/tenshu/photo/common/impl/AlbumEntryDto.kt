package org.tenshu.photo.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.photo.AlbumEntry

internal class AlbumEntryDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), AlbumEntry {

    override val schema: CompositeSchema
        get() = AlbumEntry.Schema
}
