package org.tenshu.contact

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A contact's photo (`docs/proposals/contacts-app.md` D1 + Q4).
 *
 * ⚠⚠ **A sibling row keyed by [personId], never a field or property on [Person]** — and the
 * reason is structural, not cosmetic. `RenamePerson` is a **replace-write**: it rebuilds the
 * person row from the fields it knows about, so an avatar living on `Person` would be destroyed
 * by a rename, silently (deletion by omission). A separately-keyed row is immune to that by
 * construction. Do not "simplify" this into a property.
 *
 * Symmetry worth keeping: [Person.displayName] is the user-owned **petname** over whatever
 * names the sources assert; this is the user-owned **photo** over whatever `PHOTO` those
 * sources carry — which stay lossless in [ContactSource.rawVcard] either way, so nothing here
 * is the only copy of anything.
 *
 * ⚠ **The image is stored inline, not on the content plane.** A vCard `PHOTO` is already a
 * small embedded image, and the `File.Thumbnail` precedent is exactly this: a modest,
 * eagerly-synced blob living in the row that needs it. Pick-from-Drive (D1's other source) is
 * content-addressed and needs a reshape of this composite — a version bump plus a
 * `SchemaMigration` (House Rule #9). There is no `contentId` field ahead of that design.
 *
 * Personal-realm `Syncable(Eager)` — the photo follows the contact to every device, ciphertext
 * to the relay like any entity (House Rule #1; threat-model A24).
 */
interface PersonAvatar : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The [Person.personId] this photo belongs to; also the key of this row. */
    val personId: TString
        get() = getField(Field.PERSON_ID)

    /**
     * The image bytes, in whatever format they arrived as — JPEG or PNG in practice, since
     * that is what exporters embed. Not re-encoded: re-encoding would need a platform image
     * decoder in a service module, and would lose quality to no purpose.
     */
    val imageData: TByteArray
        get() = getField(Field.IMAGE_DATA)

    companion object {
        const val ID = "tenshu/contact/personAvatar"

        /**
         * The largest photo accepted from an import, in bytes.
         *
         * ⚠ A ceiling exists because this row syncs **eagerly to every device**: a 4 MB
         * portrait embedded by some desktop address book would cost every one of them, for a
         * 40-pixel list glyph. Cards over the limit keep their `PHOTO` losslessly in
         * `rawVcard` — nothing is destroyed, the photo simply is not promoted to the avatar.
         * Downscaling instead would put a platform image decoder inside `:contact-service`.
         */
        const val MAX_IMAGE_BYTES = 256 * 1024

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                PersonAvatarDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(Field.PERSON_ID)
                byteArray(Field.IMAGE_DATA)
            }
        )

        fun create(
            context: TContext,
            personId: String,
            imageData: ByteArray,
        ): PersonAvatar = context.create(
            type = ID,
            fields = listOf(personId, imageData),
        )
    }

    object Field {
        const val PERSON_ID = "$ID/personId"
        const val IMAGE_DATA = "$ID/imageData"
    }
}

val Entity<out PersonAvatar>.personId: TString
    get() = getFieldValue(PersonAvatar.Field.PERSON_ID)

val Entity<out PersonAvatar>.imageData: TByteArray
    get() = getFieldValue(PersonAvatar.Field.IMAGE_DATA)

interface PersonAvatarDataIdProvider : CompositeDataIdProvider<PersonAvatar> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/contact/personAvatar/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = PersonAvatar.Field.PERSON_ID,
        )
    }
}
