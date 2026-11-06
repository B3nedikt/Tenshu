package org.tenshu.contact.client.impl

import org.tenshu.contact.ContactInvite
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.Person
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.TenshuIdentity
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain

internal class ContactServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : ContactService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val persons: TList<Person>
        get() = serviceRelation(ContactService.Relations.PERSONS, Person.Schema)

    override val tenshuIdentities: TList<TenshuIdentity>
        get() = serviceRelation(ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.Schema)

    override val contactInvites: TList<ContactInvite>
        get() = serviceRelation(ContactService.Relations.CONTACT_INVITES, ContactInvite.Schema)

    override val sources: TList<ContactSource>
        get() = serviceRelation(ContactService.Relations.SOURCES, ContactSource.Schema)

    override val avatars: TList<PersonAvatar>
        get() = serviceRelation(ContactService.Relations.AVATARS, PersonAvatar.Schema)
}
