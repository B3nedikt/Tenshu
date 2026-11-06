package org.tenshu.preference.test

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.preference.common.preferenceServiceCommonModule

fun createPreferenceServiceCommonTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(preferenceServiceCommonModule)
}
