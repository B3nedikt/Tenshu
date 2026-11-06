package org.tenshu.identity.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.identity.client.identityServiceClientModule
import org.tenshu.identity.common.identityServiceCommonModule

fun createIdentityProxy(): TestProxy {
    val registry = createCoreTestRegistry().apply {
        registerConfig(identityServiceCommonModule)
        registerConfig(identityServiceClientModule)
    }

    return TestProxy(
        schemaRegistry = registry,
        implementationFactoryOverride = { id, _ -> identityProxyImplementationFactory(id) }
    )
}
