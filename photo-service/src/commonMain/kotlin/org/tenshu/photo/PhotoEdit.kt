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
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * How one photo is edited, non-destructively (`photos-editing.md` §12, ED-3): the recipe the editor
 * re-opens, and where the baked rendition every device shows lives. One row per *edited* photo — no
 * row is an unedited photo.
 *
 * ⚠ Keyed on the **original's** `File.ContentId` for [PhotoFlags]' reason, and it is the point of the
 * row: the photograph's bytes and id never move, so its albums, favourite, hidden flag and trash
 * record are untouched by an edit (`photos-editing.md` §3.1).
 *
 * ⚠⚠ **Shown by its rendition, or not at all** (§12.4). No device re-renders [recipe] for display
 * (ED-D2), and a device that holds this row but not the rendition shows a placeholder, never the
 * original — the original of a redacted edit is exactly what the edit hides (§11.3).
 *
 * ⚠ [recipe] is opaque here: versioned JSON only the Photos editor reads (ED-D9). Nothing in this
 * service, the proxy or another app may look inside it.
 */
interface PhotoEdit : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The original photo's `File.ContentId` — the row key. */
    val contentId: TString get() = getField(Field.CONTENT_ID)

    /** The edit, as `EditRecipeCodec` JSON (`photos-editing.md` §12.2). */
    val recipe: TString get() = getField(Field.RECIPE)

    /** The baked rendition's realm path, under `EDIT_RENDITIONS_ROOT` (§12.3). */
    val renditionPath: TString get() = getField(Field.RENDITION_PATH)

    /** The recipe holds a redaction — said here so a reader need not decode [recipe] to know. */
    val redacted: TBoolean get() = getField(Field.REDACTED)

    val editedAt: UnixInstant get() = getField(Field.EDITED_AT)

    companion object {
        const val ID = "tenshu/photo/edit"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(PhotoEditDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.CONTENT_ID)
                string(Field.RECIPE)
                string(Field.RENDITION_PATH)
                boolean(Field.REDACTED)
                timestamp(Field.EDITED_AT)
            }
        )

        fun create(
            context: TContext,
            contentId: String,
            recipe: String,
            renditionPath: String,
            redacted: Boolean,
            editedAtMs: Long,
        ): PhotoEdit = context.create(
            type = ID,
            fields = listOf(contentId, recipe, renditionPath, redacted, editedAtMs),
        )
    }

    object Field {
        const val CONTENT_ID = "$ID/contentId"
        const val RECIPE = "$ID/recipe"
        const val RENDITION_PATH = "$ID/renditionPath"
        const val REDACTED = "$ID/redacted"
        const val EDITED_AT = "$ID/editedAt"
    }
}

val Entity<out PhotoEdit>.contentId: TString get() = getFieldValue(PhotoEdit.Field.CONTENT_ID)

val Entity<out PhotoEdit>.recipe: TString get() = getFieldValue(PhotoEdit.Field.RECIPE)

val Entity<out PhotoEdit>.renditionPath: TString get() = getFieldValue(PhotoEdit.Field.RENDITION_PATH)

val Entity<out PhotoEdit>.redacted: TBoolean get() = getFieldValue(PhotoEdit.Field.REDACTED)

val Entity<out PhotoEdit>.editedAt: UnixInstant get() = getFieldValue(PhotoEdit.Field.EDITED_AT)

interface PhotoEditDataIdProvider : CompositeDataIdProvider<PhotoEdit> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/photo/edit/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = PhotoEdit.Field.CONTENT_ID,
        )
    }
}
