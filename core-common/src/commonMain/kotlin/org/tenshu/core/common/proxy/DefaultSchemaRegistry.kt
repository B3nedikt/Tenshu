package org.tenshu.core.common.proxy

import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.core.core.TContext

fun createDefaultSchemaRegistry(
    tContextProvider: () -> TContext,
    extraConfigs: List<ModuleConfiguration> = emptyList()
): SchemaRegistry {
    val registry = SchemaRegistry(tContextProvider)
    registry.registerConfig(coreCommonModule)
    extraConfigs.forEach { registry.registerConfig(it) }
    return registry
}
