package org.tenshu.contact.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.contact.Person

internal class PersonDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Person {

    override val schema: CompositeSchema
        get() = Person.Schema
}
