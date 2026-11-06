package org.tenshu.preference.test

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.preference.client.preferenceServiceClientModule

fun createPreferenceTestRegistry(): SchemaRegistry =
    createPreferenceServiceCommonTestRegistry().apply {
        registerConfig(preferenceServiceClientModule)
    }
