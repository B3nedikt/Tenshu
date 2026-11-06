package org.tenshu.identity.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.identity.IdentityService

val TenshuClient.identityService: IdentityService
    get() = getService(IdentityService.ID)
