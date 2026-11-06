package org.tenshu.preference.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.PreferenceService

val TenshuClient.preferenceService: PreferenceService
    get() = getService(PreferenceService.ID)
