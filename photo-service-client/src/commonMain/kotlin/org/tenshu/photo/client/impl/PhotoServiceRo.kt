package org.tenshu.photo.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.photo.Album
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.PhotoEdit
import org.tenshu.photo.PhotoFlags
import org.tenshu.photo.PhotoService
import org.tenshu.photo.TrashedPhoto

internal class PhotoServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : PhotoService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val albums: TList<Album>
        get() = serviceRelation(PhotoService.Relations.ALBUMS, Album.Schema)

    override val albumEntries: TList<AlbumEntry>
        get() = serviceRelation(PhotoService.Relations.ALBUM_ENTRIES, AlbumEntry.Schema)

    override val photoFlags: TList<PhotoFlags>
        get() = serviceRelation(PhotoService.Relations.PHOTO_FLAGS, PhotoFlags.Schema)

    override val trashedPhotos: TList<TrashedPhoto>
        get() = serviceRelation(PhotoService.Relations.TRASHED_PHOTOS, TrashedPhoto.Schema)

    override val photoEdits: TList<PhotoEdit>
        get() = serviceRelation(PhotoService.Relations.PHOTO_EDITS, PhotoEdit.Schema)
}
