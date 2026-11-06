package org.tenshu.core.common

import org.tenshu.core.Service
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.core.TContext
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.toType

open class TenshuEnvironment(
    val taskExecutor: AsyncTaskExecutor,
    val schemaRegistry: SchemaRegistry,
    val tContext: TContext,
    val requestChainFactory: (TContext) -> RequestChain
) {
    /**
     * ⚠⚠ Not a plain map. Every `client.fileService` / `photoService` / … getter lands here, from
     * whichever thread is making the call — and with the proxy in the caller's own process (iOS
     * route 1) that is several at once: tile previews, a view model's reads, the main thread's pages.
     * A first touch of one service inserted while another thread looked up a second; in a `HashMap`
     * that is a read inside a resize — the miss `docs/tech-debt.md` records for the cursor rows.
     */
    private val services = ConcurrentMemo<String, Service>()

    fun <T : Service> getService(id: String): T {
        @Suppress("UNCHECKED_CAST")
        return services.getOrPut(id) {
            val schema = tContext.schemaProvider().getSchema(id)
                ?: error("No schema registered for service id: $id")
            schemaRegistry.createRequestObject(
                type = schema.toType(),
                requestChain = requestChainFactory(tContext),
                context = tContext
            )
        } as T
    }
}
