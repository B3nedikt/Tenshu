package org.tenshu.contact.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.ContactInvite

internal class ContactInviteDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ContactInvite {

    override val schema: CompositeSchema
        get() = ContactInvite.Schema
}
