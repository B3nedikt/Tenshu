package org.tenshu.contact.client

import org.tenshu.contact.ContactService
import org.tenshu.core.common.TenshuClient

val TenshuClient.contactService: ContactService
    get() = getService(ContactService.ID)
