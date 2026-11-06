package org.tenshu.storage.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.StorageProvider
import org.tenshu.storage.proxy.initStorageService
import org.tenshu.storage.proxy.storageServiceProxyImplementationFactory

/**
 * Single-client test fixture. Registers the storage service schema + client
 * config, wires the proxy-side [initStorageService] derivation onto [provider],
 * and the storage op factory ([CheckCapacity][org.tenshu.storage.CheckCapacity])
 * against the same [StorageCapacityHolder] the provider feeds.
 */
fun createStorageProxy(
    provider: StorageProvider,
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    /**
     * The holder to wire the provider and the op factory onto. Pass one to drive the fields the
     * provider does not feed — `relayRejected` and `queuedContentBytes`, both written by the sync
     * scheduler in production — from a test.
     */
    capacityHolder: StorageCapacityHolder = StorageCapacityHolder(),
): TestProxy {
    return TestProxy(
        schemaRegistry = createStorageTestRegistry(),
        coroutineScope = coroutineScope,
        implementationFactoryOverride = { id, _ ->
            storageServiceProxyImplementationFactory(id, capacityHolder)
        },
    ).also {
        it.executionContext.initStorageService(coroutineScope, provider, capacityHolder)
    }
}
