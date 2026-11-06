package org.tenshu.photo

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * One photo's membership of one [Album], with its position.
 *
 * ## ⚠⚠ Why the key is a content id and not a path
 *
 * `photos-app.md` PH-D1 said membership would address files by `DataId` and ride the
 * `referenceProperty` + `DataId`-change→reference-migration primitive, "so a move or a rename does
 * not empty an album". **That was wrong, and PH-2 is where it was caught.**
 *
 * A `File`'s `DataId` is a `StringDataId` derived from its **path**, which is why `MoveFile` has to
 * call `DataIdMigrator` at all. And that primitive does exactly one thing —
 * *"This class owns that **old-side** work"*: it retires the old key, tells peers, and sweeps the
 * stale bookkeeping. **Nothing rewrites a foreign row's stored key field.** An `AlbumEntry` holding
 * a path would therefore dangle the moment the user moved or renamed the photo in Drive, and the
 * album would quietly lose it — the exact failure the doc claimed was handled.
 *
 * `File.ContentId` is the identity that actually survives: `MoveFile` preserves it verbatim across
 * a move (*"the SAME contentId (preserved by `include(sourceProps)`)"*), because the ciphertext and
 * manifest are bound to it rather than to the path. So the entry is keyed on
 * `"$albumId|$contentId"` and a move is invisible to it, by construction rather than by repair.
 *
 * [filePath] rides along as a **hint, not an identity**: resolving a content id back to a file has
 * no index and would cost a realm walk, while a path resolves through the same cheap
 * `navigateTo` + `search` every other read uses. A stale hint is self-healing — the reader falls
 * back to a content-id lookup and upserts the entry with the path it found — and it is never
 * consulted for identity, membership or ordering.
 *
 * ⚠ A file uploaded before content addressing (Ph0–2) may carry an empty `ContentId`. Such a file
 * cannot be added to an album, and `AddToAlbum` rejects it rather than minting an entry keyed on
 * the empty string — which would collide with every other such file in the same album.
 */
interface AlbumEntry : Composite {

    override val schema: CompositeSchema get() = Schema

    /** `"$albumId|$contentId"` — the row key. Built by [keyOf], never assembled by callers. */
    val key: TString get() = getField(Field.KEY)

    val albumId: TString get() = getField(Field.ALBUM_ID)

    /** The photo's `File.ContentId` — the durable half of the key. */
    val contentId: TString get() = getField(Field.CONTENT_ID)

    /** Where the file was when it was added. A hint for a cheap read; see the class doc. */
    val filePath: TString get() = getField(Field.FILE_PATH)

    /**
     * Sort order within the album, ascending.
     *
     * ⚠ Sparse by convention — [POSITION_STEP] apart — so inserting between two neighbours is one
     * write rather than a renumber of everything after it. A full renumber is still correct and
     * still what a long drag eventually needs; the gaps just make the common case cheap.
     */
    val position: TInteger get() = getField(Field.POSITION)

    companion object {
        const val ID = "tenshu/photo/albumEntry"

        /** The gap left between adjacent positions. See [AlbumEntry.position]. */
        const val POSITION_STEP = 1_000L

        /**
         * ⚠ `|` is not a legal character in a content id (they are hex) nor in an album id (a UUID),
         * so the two halves can never be ambiguous — the property a composite key needs and the
         * reason this is not, say, a concatenation of two user-supplied strings.
         */
        fun keyOf(albumId: String, contentId: String): String = "$albumId|$contentId"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(AlbumEntryDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.KEY)
                string(Field.ALBUM_ID)
                string(Field.CONTENT_ID)
                string(Field.FILE_PATH)
                int(Field.POSITION)
            }
        )

        fun create(
            context: TContext,
            albumId: String,
            contentId: String,
            filePath: String,
            position: Long,
        ): AlbumEntry = context.create(
            type = ID,
            fields = listOf(keyOf(albumId, contentId), albumId, contentId, filePath, position),
        )
    }

    object Field {
        const val KEY = "$ID/key"
        const val ALBUM_ID = "$ID/albumId"
        const val CONTENT_ID = "$ID/contentId"
        const val FILE_PATH = "$ID/filePath"
        const val POSITION = "$ID/position"
    }
}

val Entity<out AlbumEntry>.key: TString get() = getFieldValue(AlbumEntry.Field.KEY)

val Entity<out AlbumEntry>.albumId: TString get() = getFieldValue(AlbumEntry.Field.ALBUM_ID)

val Entity<out AlbumEntry>.contentId: TString get() = getFieldValue(AlbumEntry.Field.CONTENT_ID)

val Entity<out AlbumEntry>.filePath: TString get() = getFieldValue(AlbumEntry.Field.FILE_PATH)

val Entity<out AlbumEntry>.position: TInteger get() = getFieldValue(AlbumEntry.Field.POSITION)

interface AlbumEntryDataIdProvider : CompositeDataIdProvider<AlbumEntry> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/photo/albumEntry/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = AlbumEntry.Field.KEY,
        )
    }
}
