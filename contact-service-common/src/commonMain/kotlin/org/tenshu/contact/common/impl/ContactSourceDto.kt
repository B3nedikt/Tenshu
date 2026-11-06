package org.tenshu.contact.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.ContactSource

internal class ContactSourceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ContactSource {

    override val schema: CompositeSchema
        get() = ContactSource.Schema
}
