package org.tenshu.preference.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.preference.proxy.initPreferenceService
import org.tenshu.preference.proxy.preferenceServiceProxyImplementationFactory

/**
 * Single-client test fixture. Wires the preference service's proxy
 * implementation factory and registers the relation-maintainer observer —
 * mirrors `createNotificationProxy` in `:notification-service-test-utils`.
 */
fun createPreferenceProxy(coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)) =
    TestProxy(
        schemaRegistry = createPreferenceTestRegistry(),
        coroutineScope = coroutineScope,
        implementationFactoryOverride = { id, _ ->
            preferenceServiceProxyImplementationFactory(id)
        }
    ).also {
        it.executionContext.initPreferenceService()
    }
