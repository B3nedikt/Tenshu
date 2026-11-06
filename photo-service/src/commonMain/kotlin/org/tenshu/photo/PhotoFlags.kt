package org.tenshu.photo

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * What the user has said about one photo: favourite, hidden. One row per photo, and only for
 * photos the user has actually marked — the absence of a row is the default for both.
 *
 * ⚠ Keyed on `File.ContentId` for [AlbumEntry]'s reason: a path is not a stable name for a file,
 * and a favourite that a move silently dropped would be worse than one that was never set.
 *
 * ⚠ **Both flags on one row, deliberately.** They are the same kind of statement about the same
 * subject and are written from the same gesture surface; two rows would double the sync traffic of
 * a double-tap and buy nothing. ⚠ The cost is that clearing one flag rewrites the other's value
 * too — safe here because both are last-writer-wins booleans owned by the same user, and worth
 * re-examining the moment a third flag arrives that is *not*.
 *
 * ⚠ `hidden` is a **listing** concern, not a security one: it keeps a photo out of the feed and
 * nothing more. It is not a lock, not an encryption boundary, and nothing outside Photos honours
 * it — a hidden photo is an ordinary file in Drive, which is where anyone looking would find it. A
 * real private album is a shared-realm-shaped question and is not this.
 */
interface PhotoFlags : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The photo's `File.ContentId` — the row key. */
    val contentId: TString get() = getField(Field.CONTENT_ID)

    val favourite: TBoolean get() = getField(Field.FAVOURITE)

    val hidden: TBoolean get() = getField(Field.HIDDEN)

    companion object {
        const val ID = "tenshu/photo/flags"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(PhotoFlagsDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.CONTENT_ID)
                boolean(Field.FAVOURITE)
                boolean(Field.HIDDEN)
            }
        )

        fun create(
            context: TContext,
            contentId: String,
            favourite: Boolean,
            hidden: Boolean,
        ): PhotoFlags = context.create(
            type = ID,
            fields = listOf(contentId, favourite, hidden),
        )
    }

    object Field {
        const val CONTENT_ID = "$ID/contentId"
        const val FAVOURITE = "$ID/favourite"
        const val HIDDEN = "$ID/hidden"
    }
}

val Entity<out PhotoFlags>.contentId: TString get() = getFieldValue(PhotoFlags.Field.CONTENT_ID)

val Entity<out PhotoFlags>.favourite: TBoolean get() = getFieldValue(PhotoFlags.Field.FAVOURITE)

val Entity<out PhotoFlags>.hidden: TBoolean get() = getFieldValue(PhotoFlags.Field.HIDDEN)

interface PhotoFlagsDataIdProvider : CompositeDataIdProvider<PhotoFlags> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/photo/flags/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = PhotoFlags.Field.CONTENT_ID,
        )
    }
}
