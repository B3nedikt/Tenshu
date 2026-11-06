package org.tenshu.contact

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.dynamicProperty
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * Thin person aggregate — the contact-list row
 * (`docs/proposals/contact-service-identity-model.md` §4.1, the
 * `ContactsContract.Contacts` analogue).
 *
 * **Deliberately carries no vCard fields.** [displayName] is the **petname**
 * slot: user-controlled (defaulted from the handshake's self-asserted name,
 * but owned by the user), the spoof-resistant label. Self-asserted names stay
 * quarantined on the linked [TenshuIdentity] rows; the detail surface renders
 * the read-through union of linked sources/identities. [ContactSource]
 * rows hold imported data losslessly.
 *
 * Per-person collections (identities, sources, the avatar) are derived from the
 * `personId` back-links on those rows — link/unlink is a field write there,
 * never array surgery here.
 *
 * Personal-realm `Syncable(Eager)`: the contact graph is the user's private
 * data, ciphertext to the relay like any entity (House Rule #1; threat-model
 * A24).
 */
interface Person : Composite {

    override val schema: CompositeSchema get() = Schema

    val personId: TString
        get() = getField(Field.PERSON_ID)

    val displayName: TString
        get() = getField(Field.DISPLAY_NAME)

    companion object {
        const val ID = "tenshu/contact/person"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                PersonDataIdProvider.Schema,
                Syncable(SyncStrategy.Eager),
            ),
            fields = {
                string(Field.PERSON_ID)
                string(Field.DISPLAY_NAME)
            }
        )

        fun create(
            context: TContext,
            personId: String,
            displayName: String,
        ): Person = context.create(
            type = ID,
            fields = listOf(personId, displayName),
        )
    }

    object Field {
        const val PERSON_ID = "$ID/personId"
        const val DISPLAY_NAME = "$ID/displayName"
    }
}

/**
 * The contact's photo, resolved on read from their [PersonAvatar] row — empty bytes when they
 * have none (`contacts-app.md` Q4). Empty, not `null`: on a deferred property `null` already
 * means *still pending*, so a caller could not tell "no photo" from "not resolved yet".
 *
 * **`deferred = true`**, following `File.Preview`. The row is the source of truth and this is
 * ergonomics: it lets a chain project the photo beside the person instead of every caller
 * running a second query. Deferral is what makes that affordable — streaming terminals emit
 * their rows immediately and the resolution queue patches the photo in per row, so a list
 * never waits on N blob reads before showing anything.
 *
 * ⚠ Three things follow from that:
 *
 *  1. **One-shot terminals read a deferred property as `null`.** `getAll()` will not resolve
 *     it. A caller that genuinely needs the bytes in one shot wraps with `eager(...)`, or
 *     calls [getPersonAvatar] directly.
 *  2. **The surface rule** (`core-deferred-properties.md` §6): lists use a per-row loader,
 *     single-entity reactive reads use the property.
 *  3. ⚠⚠ **A `dynamicProperty` dispatches through the *client* module's `roFactory`
 *     registration**, not merely `operation(Schema)` in common. Miss it and every select
 *     projecting this dies as an opaque `error/internal`.
 */
val Person.Companion.Avatar: Property.Dynamic<TByteArray> get() = personAvatar

private val personAvatar: Property.Dynamic<TByteArray> = dynamicProperty<Person, TByteArray>(
    id = "${Person.ID}/avatar",
    receiver = Person.Schema,
    params = emptyList(),
    // No `Syncable` trait: a dynamic property is computed on read and never written into the
    // entity, so `SyncObserver` would never see a value to emit. What syncs is the
    // `PersonAvatar` row itself, which carries the trait.
    deferred = true,
) { getAvatar() }

val Entity<out Person>.personId: TString
    get() = getFieldValue(Person.Field.PERSON_ID)

val Entity<out Person>.displayName: TString
    get() = getFieldValue(Person.Field.DISPLAY_NAME)

interface PersonDataIdProvider : CompositeDataIdProvider<Person> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/contact/person/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Person.Field.PERSON_ID,
        )
    }
}
