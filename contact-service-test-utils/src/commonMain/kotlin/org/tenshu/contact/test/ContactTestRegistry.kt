package org.tenshu.contact.test

import org.tenshu.contact.client.contactServiceClientModule
import org.tenshu.core.common.proxy.SchemaRegistry

fun createContactTestRegistry(): SchemaRegistry =
    createContactServiceCommonTestRegistry().apply {
        registerConfig(contactServiceClientModule)
    }
