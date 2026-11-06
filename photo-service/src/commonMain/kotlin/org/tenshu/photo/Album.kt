package org.tenshu.photo

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A manual album — a name, and an ordered set of photos ([AlbumEntry]) — `photos-app.md` S4/S5.
 *
 * ⚠⚠ **A row in `:photo-service`, never a field on `File`.** `File` is the platform's hottest
 * composite and `:file-service` its most central service; teaching either what an album is would
 * be the layering inversion `PhotosNear`'s KDoc refuses for places. `MediaLabel` (to
 * `:inference-service`) and `PlaceRole` (to `:place-service`) both took this route, and a
 * separately-keyed row has the second property that matters here: it cannot be erased by an
 * unrelated write to the thing it describes.
 *
 * ⚠ Smart albums are **not** rows. Places, dates, videos, favourites and — later — labels are
 * queries, and storing their results would mean maintaining them. Only a manual album, which is
 * exactly the case a query cannot express, is persisted.
 */
interface Album : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable opaque id; the row key, and what [AlbumEntry.albumId] points at. */
    val albumId: TString get() = getField(Field.ALBUM_ID)

    val name: TString get() = getField(Field.NAME)

    val createdAt: UnixInstant get() = getField(Field.CREATED_AT)

    /**
     * The `File.ContentId` of the cover photo, or empty to mean *use the first entry*.
     *
     * ⚠ A content id rather than a path, for the reason [AlbumEntry] exists to state: a path is
     * not a stable name for a file. Empty is a real value, not a null stand-in — "the user has not
     * chosen" and "the user chose the first photo" behave identically today but diverge the moment
     * reordering lands, and an album whose cover silently followed a reorder would be a bug.
     */
    val coverContentId: TString get() = getField(Field.COVER_CONTENT_ID)

    companion object {
        const val ID = "tenshu/photo/album"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(AlbumDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.ALBUM_ID)
                string(Field.NAME)
                timestamp(Field.CREATED_AT)
                string(Field.COVER_CONTENT_ID)
            }
        )

        fun create(
            context: TContext,
            albumId: String,
            name: String,
            createdAtMs: Long,
            coverContentId: String = "",
        ): Album = context.create(
            type = ID,
            fields = listOf(albumId, name, createdAtMs, coverContentId),
        )
    }

    object Field {
        const val ALBUM_ID = "$ID/albumId"
        const val NAME = "$ID/name"
        const val CREATED_AT = "$ID/createdAt"
        const val COVER_CONTENT_ID = "$ID/coverContentId"
    }
}

/**
 * Field accessors for a projected [Album] row.
 *
 * ⚠ A `select { fields(...) }` chain yields `Entity<Album>`, not `Album`, so a reader needs these
 * rather than the interface's own getters — the `Calendar` / `Person` precedent.
 */
val Entity<out Album>.albumId: TString get() = getFieldValue(Album.Field.ALBUM_ID)

val Entity<out Album>.name: TString get() = getFieldValue(Album.Field.NAME)

val Entity<out Album>.createdAt: UnixInstant get() = getFieldValue(Album.Field.CREATED_AT)

val Entity<out Album>.coverContentId: TString get() = getFieldValue(Album.Field.COVER_CONTENT_ID)

interface AlbumDataIdProvider : CompositeDataIdProvider<Album> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/photo/album/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Album.Field.ALBUM_ID,
        )
    }
}
