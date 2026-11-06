package org.tenshu.photo.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.photo.Album

internal class AlbumDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Album {

    override val schema: CompositeSchema
        get() = Album.Schema
}
