package org.tenshu.contact.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.PersonAvatar

internal class PersonAvatarDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), PersonAvatar {

    override val schema: CompositeSchema
        get() = PersonAvatar.Schema
}
