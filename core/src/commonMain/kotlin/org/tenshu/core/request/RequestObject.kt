package org.tenshu.core.request

import org.tenshu.core.Data
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext

/**
 * Interface for objects that act as client-side proxies for data on the proxy side.
 * Request objects contain a [RequestChain] that describes how to reach them.
 * 
 * They implement [TContext] by delegating to their internal [context], allowing
 * them to be used directly in DSLs.
 */
interface RequestObject : Data, TContext {
    /**
     * The underlying platform context.
     */
    val context: TContext

    /**
     * The chain of operations leading to this object.
     */
    val requestChain: RequestChain

    override val components: Map<String, TComponent> get() = context.components
}
