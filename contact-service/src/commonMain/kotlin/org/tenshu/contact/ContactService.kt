package org.tenshu.contact

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * Service surface for the contact graph
 * (`docs/proposals/contact-service-identity-model.md` §4; the handshake is
 * `contact-invitation-handshake.md` §7.3).
 *
 * Its relations are traversed with the standard chain DSL:
 *
 * ```
 * service.persons.search<Person> { … }.get()        // the Manage-access picker
 * service.tenshuIdentities
 *     .search<TenshuIdentity> { personId equalTo X }
 *     .get()                                        // a person's keyring
 * service.contactInvites
 *     .search<ContactInvite> { status equalTo PENDING }
 *     .flow()                                       // pending-invite watch
 * ```
 */
interface ContactService : Service {

    override val schema: ServiceSchema get() = Schema

    val persons: TList<Person>

    val tenshuIdentities: TList<TenshuIdentity>

    val contactInvites: TList<ContactInvite>

    /**
     * The lossless source records behind each contact's human-facing detail.
     * A person may have several — the detail screen renders their read-through union.
     */
    val sources: TList<ContactSource>

    /**
     * Contact photos, one row per person at most. A sibling relation rather than a
     * field on [Person] because `RenamePerson` is a replace-write — see [PersonAvatar].
     */
    val avatars: TList<PersonAvatar>

    companion object {
        const val ID = "tenshu/contact/service"

        // `order` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:contact-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            // v1 is the release baseline (`core-schema-evolution.md` Appendix A.6).
            version = 1,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.PERSONS,
                    type = TList.typeOf(Person.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.TENSHU_IDENTITIES,
                    type = TList.typeOf(TenshuIdentity.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.CONTACT_INVITES,
                    type = TList.typeOf(ContactInvite.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.SOURCES,
                    type = TList.typeOf(ContactSource.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.AVATARS,
                    type = TList.typeOf(PersonAvatar.Schema),
                    maintained = true,
                ),
            ),
        )
    }

    object Relations {
        const val PERSONS = "$ID/persons"
        const val TENSHU_IDENTITIES = "$ID/tenshuIdentities"
        const val CONTACT_INVITES = "$ID/contactInvites"
        const val SOURCES = "$ID/sources"
        const val AVATARS = "$ID/avatars"
    }
}
