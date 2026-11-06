package org.tenshu.contact

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.dynamicProperty
import org.tenshu.core.relation.valueProperty
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.ContentReferencing
import org.tenshu.core.trait.PrimaryContent
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * One record a contact's human-facing detail came from, kept **losslessly**
 * (`contact-service-identity-model.md` §4.3).
 *
 * A [Person] is deliberately thin — a petname and nothing else. Everything a user would
 * recognise as "the contact card" (numbers, emails, addresses, organisation, notes) lives
 * here, as the original vCard payload rather than as parsed fields.
 *
 * ⚠⚠ **[rawVcard] is the source of truth, and storing it verbatim is the whole point.**
 * Parsing into columns would silently discard every property the schema does not model —
 * `X-` extensions, `CATEGORIES`, unknown vendor lines — and there would be no way to notice.
 * Keeping the payload means an import round-trips, export is free, and the codec only has to
 * understand the handful of properties the UI actually edits.
 *
 * ⚠ **Never regenerate this field from parsed fields.** An edit path that rebuilds the record
 * from what the editor showed is deletion-by-omission (`contacts-app.md` Q6) — use
 * the codec's merge, which rewrites only the properties the user touched and passes
 * everything else through untouched.
 *
 * [sourceKey] names the origin and keys the row: `"manual:$uuid"` (typed by hand —
 * `contacts-app.md` D5), `"vcard:$uid"` (a `.vcf` import), `"android:$lookupKey"` / an iOS
 * `CNContact` id.
 * One person may have several, which is how a read-through union across sources works.
 *
 * Personal-realm `Syncable(Eager)`, ciphertext to the relay like any entity (House Rule #1;
 * threat-model A24).
 *
 * ## ⚠⚠ A big `PHOTO` is not in [rawVcard] (`contacts-app.md` §9.10)
 *
 * A `PHOTO` content line over `ContactPhotoContent.RELOCATE_ABOVE_BYTES` lives on the **content
 * plane** as [ContactSource.Photos], and [rawVcard] holds an `X-TENSHU-PHOTO-REF` placeholder line in
 * its place — so this row stays far below the iOS store's `ROW_MAX`. [rawVcard] is therefore the
 * card *as stored*, readable offline for everything but the big image; the card as imported is
 * `reassembleVCard(rawVcard, photos)`, byte-exact. ⚠ Export, share and re-encode must use the
 * reassembled card — exporting [rawVcard] alone would ship a placeholder instead of the photo.
 *
 * ⚠⚠ Every save of this type must **state** [ContactSource.ContentId] — the id when the card has
 * placeholders, blank when it has none. A bare-composite save states nothing: locally the store
 * keeps the previous slot (so photos a save dropped would stay declared for ever), and a payload
 * without it reads `none` (so the relay would free photos still referenced).
 * `saveContactSourceRow` in `:contact-service-proxy` is the one writer.
 */
interface ContactSource : Composite {

    override val schema: CompositeSchema get() = Schema

    /** `"<origin>:<id>"` — the key of this row. */
    val sourceKey: TString
        get() = getField(Field.SOURCE_KEY)

    /** Aggregate link to [Person.personId]; `""` = not yet linked. */
    val personId: TString
        get() = getField(Field.PERSON_ID)

    /** The record as it arrived — vCard 3.0 on write, 2.1 or 3.0 accepted on read. */
    val rawVcard: TString
        get() = getField(Field.RAW_VCARD)

    companion object {
        const val ID = "tenshu/contact/contactSource"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                ContactSourceDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
                // The relocated PHOTO payloads are content-plane content (`contacts-app.md`
                // §9.10). Code-side schema metadata, never serialised.
                ContentReferencing.Schema,
            ),
            fields = {
                string(Field.SOURCE_KEY)
                string(Field.PERSON_ID)
                string(Field.RAW_VCARD)
            }
        )

        fun create(
            context: TContext,
            sourceKey: String,
            personId: String,
            rawVcard: String,
        ): ContactSource = context.create(
            type = ID,
            fields = listOf(sourceKey, personId, rawVcard),
        )
    }

    object Field {
        const val SOURCE_KEY = "$ID/sourceKey"
        const val PERSON_ID = "$ID/personId"
        const val RAW_VCARD = "$ID/rawVcard"
    }

    /** Origin prefixes for [sourceKey]. */
    object Origin {
        const val MANUAL = "manual"
        const val VCARD = "vcard"
        const val ANDROID = "android"
        const val IOS = "ios"
    }
}

private val contactSourcePhotos: Property.Dynamic<TByteArray> = dynamicProperty<ContactSource, TByteArray>(
    id = "${ContactSource.ID}/photos",
    receiver = ContactSource.Schema,
    traits = listOf(Syncable(SyncStrategy.OnDemand), PrimaryContent()),
) { getPhotos() }

/**
 * The card's **relocated `PHOTO` lines** — a content-plane blob (`ContactPhotoContent`'s frame of
 * the raw spans) resolved on demand, the `Workout.Streams` / `File.Data` shape exactly: the bytes
 * live in the external `FileDatabase` under `contactPhotosPath(contentId)`, never on the row, and a
 * device that has not fetched them yet pulls the sealed chunks lazily through a Maybe/stream
 * terminal. `null` when the card moved nothing, or while the bytes are not local.
 *
 * ⚠ Read it only where the **full** card is needed (export, share, re-encode). Everything a
 * contact screen shows is in [ContactSource.rawVcard] already.
 */
val ContactSource.Companion.Photos: Property.Dynamic<TByteArray> get() = contactSourcePhotos

private val contactSourceContentId: Property.Value<TString> =
    valueProperty(id = "${ContactSource.ID}/contentId", schema = TString.Schema)

/**
 * The content identity [ContactSource.Photos] is addressed by — the contacts twin of
 * `Workout.ContentId` and `File.ContentId`, a named slot bound to `ContentReferencing.CONTENT_ID`.
 *
 * ⚠⚠ **Random per relocation, never derived from the source key.** A new set of moved photos
 * mints a new id, so the superseded content is left declared with no live edge — reclaimable
 * (`content-plane-refcount.md` §9.5) — instead of overwritten under an id still declared. Blank or
 * absent: the card moved nothing.
 */
val ContactSource.Companion.ContentId: Property.Value<TString> get() = contactSourceContentId

val Entity<out ContactSource>.sourceKey: TString
    get() = getFieldValue(ContactSource.Field.SOURCE_KEY)

val Entity<out ContactSource>.personId: TString
    get() = getFieldValue(ContactSource.Field.PERSON_ID)

val Entity<out ContactSource>.rawVcard: TString
    get() = getFieldValue(ContactSource.Field.RAW_VCARD)

interface ContactSourceDataIdProvider : CompositeDataIdProvider<ContactSource> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/contact/contactSource/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = ContactSource.Field.SOURCE_KEY,
        )
    }
}
