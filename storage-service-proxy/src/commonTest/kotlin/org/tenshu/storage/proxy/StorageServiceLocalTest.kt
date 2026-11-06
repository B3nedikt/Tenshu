package org.tenshu.storage.proxy

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.storage.StorageService
import org.tenshu.storage.client.status
import org.tenshu.storage.test.FakeStorageProvider
import org.tenshu.storage.test.createStorageProxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Local (single-proxy) round-trip proving the Phase 1 primitive end-to-end
 * without sync: the [org.tenshu.storage.proxy.StorageProvider] figures are
 * derived into the single-row `statuses` relation by [initStorageService], and
 * the client chain surfaces them — `hasStorage=false` until a provider binds,
 * then reacting to each change.
 */
class StorageServiceLocalTest {

    @Test
    fun status_isUnbound_thenReflectsAndReactsToProvider() = runTest {
        val provider = FakeStorageProvider() // initial: hasStorage = false
        val proxy = createStorageProxy(provider, backgroundScope)
        val service = environmentFor(proxy).getService<StorageService>(StorageService.ID)

        // Unbound: the derived row exists but reports no storage.
        val unbound = service.status().filterNotNull().first()
        assertEquals(false, unbound.hasStorage.get(), "no provider figures yet ⇒ no storage")

        // Simulate an entitlement arriving — the derived status reacts.
        provider.set(
            org.tenshu.storage.proxy.StorageFigures(
                hasStorage = true,
                usedBytes = 10L,
                limitBytes = 1_000L,
            ),
        )
        val bound = service.status().filterNotNull().first { it.hasStorage.get() }
        assertEquals(10L, bound.usedBytes.get())
        assertEquals(1_000L, bound.limitBytes.get())

        // And reacts again on a usage change.
        provider.set(
            org.tenshu.storage.proxy.StorageFigures(
                hasStorage = true,
                usedBytes = 999L,
                limitBytes = 1_000L,
            ),
        )
        val updated = service.status().filterNotNull().first { it.usedBytes.get() == 999L }
        assertEquals(999L, updated.usedBytes.get())
    }

    private fun environmentFor(proxy: TestProxy): TenshuEnvironment {
        val executor = LocalTaskExecutor(proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = proxy.tContext,
        )
    }
}
