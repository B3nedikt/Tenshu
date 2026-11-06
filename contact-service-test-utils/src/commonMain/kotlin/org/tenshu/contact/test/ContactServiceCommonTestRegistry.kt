package org.tenshu.contact.test

import org.tenshu.contact.common.contactServiceCommonModule
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry

fun createContactServiceCommonTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(contactServiceCommonModule)
}
