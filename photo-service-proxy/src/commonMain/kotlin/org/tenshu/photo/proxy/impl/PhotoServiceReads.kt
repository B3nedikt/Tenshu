package org.tenshu.photo.proxy.impl

import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.photo.Album
import org.tenshu.photo.AlbumEntry
import org.tenshu.photo.PhotoService

/**
 * Proxy-side reads of this service's own relations — the `allEvents()` shape from
 * `:calendar-service-proxy`.
 *
 * ⚠ Full scans, and correct at this scale: an album's entries are counted in hundreds and the
 * relation holds every album's, so the filter is the same one a chain predicate would apply
 * anyway. ⚠ If a library ever makes this matter it wants an index, not a cleverer traversal — and
 * the *feed*, which is the read that actually runs over every photo, deliberately does not come
 * through here.
 */
internal fun ExecutionContext.allAlbumEntries(): List<AlbumEntry> =
    resolveList(
        resolveServiceData(
            service = ServiceTypeReferenceDto(PhotoService.ID),
            ref = EntityListReferenceDto(PhotoService.Relations.ALBUM_ENTRIES),
        )
    )?.filterIsInstance<AlbumEntry>()?.toList().orEmpty()

internal fun ExecutionContext.allAlbums(): List<Album> =
    resolveList(
        resolveServiceData(
            service = ServiceTypeReferenceDto(PhotoService.ID),
            ref = EntityListReferenceDto(PhotoService.Relations.ALBUMS),
        )
    )?.filterIsInstance<Album>()?.toList().orEmpty()
