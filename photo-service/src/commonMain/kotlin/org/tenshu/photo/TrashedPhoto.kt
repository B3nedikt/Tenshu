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
 * Where a trashed photo came from, so it can go back (`photos-app.md` PH-5 / S8).
 *
 * ⚠⚠ **The row exists because a move forgets.** Deleting is `MoveFile` to `/Trash`, which is what
 * makes it cheap (pure metadata, the bytes and the `contentId` stay put) — but the original path is
 * gone the moment the move lands, and "restore" means putting it back where it was. Nothing in
 * `:file-service` remembers that, and it should not: where a *photo* was is a Photos concern.
 *
 * ⚠ Keyed on `contentId` for [AlbumEntry]'s reason, and it matters more here: the row's whole job
 * is to survive the move that changes the file's path.
 *
 * ⚠ `Syncable(Eager)`: trashing on the phone must show as trashed on the tablet, and restoring
 * from either must put the file back in the same place. The file's own move already syncs; this is
 * the half that says *where from*.
 */
interface TrashedPhoto : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The photo's `File.ContentId` — the row key, and what survives the move. */
    val contentId: TString get() = getField(Field.CONTENT_ID)

    /** Where the file was before it was trashed — where [restore] puts it back. */
    val originalPath: TString get() = getField(Field.ORIGINAL_PATH)

    /** Where it is now, under `/Trash`. */
    val trashPath: TString get() = getField(Field.TRASH_PATH)

    val trashedAt: UnixInstant get() = getField(Field.TRASHED_AT)

    companion object {
        const val ID = "tenshu/photo/trashedPhoto"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(TrashedPhotoDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.CONTENT_ID)
                string(Field.ORIGINAL_PATH)
                string(Field.TRASH_PATH)
                timestamp(Field.TRASHED_AT)
            }
        )

        fun create(
            context: TContext,
            contentId: String,
            originalPath: String,
            trashPath: String,
            trashedAtMs: Long,
        ): TrashedPhoto = context.create(
            type = ID,
            fields = listOf(contentId, originalPath, trashPath, trashedAtMs),
        )
    }

    object Field {
        const val CONTENT_ID = "$ID/contentId"
        const val ORIGINAL_PATH = "$ID/originalPath"
        const val TRASH_PATH = "$ID/trashPath"
        const val TRASHED_AT = "$ID/trashedAt"
    }
}

val Entity<out TrashedPhoto>.contentId: TString get() = getFieldValue(TrashedPhoto.Field.CONTENT_ID)

val Entity<out TrashedPhoto>.originalPath: TString
    get() = getFieldValue(TrashedPhoto.Field.ORIGINAL_PATH)

val Entity<out TrashedPhoto>.trashPath: TString get() = getFieldValue(TrashedPhoto.Field.TRASH_PATH)

val Entity<out TrashedPhoto>.trashedAt: UnixInstant
    get() = getFieldValue(TrashedPhoto.Field.TRASHED_AT)

interface TrashedPhotoDataIdProvider : CompositeDataIdProvider<TrashedPhoto> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/photo/trashedPhoto/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = TrashedPhoto.Field.CONTENT_ID,
        )
    }
}
