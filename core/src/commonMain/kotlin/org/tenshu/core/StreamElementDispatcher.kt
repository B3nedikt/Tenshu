package org.tenshu.core

/**
 * Multiplexes incoming [StreamElement]s to their corresponding active [StreamInteractor]s.
 * Used by transport layers when a push element arrives from the wire.
 *
 * Not a [TComponent]: only consumed by transport-wiring code in DI modules
 * (`PlatformClientModule`, `ProxyModule`), which resolve it via Koin. No operation,
 * interactor, or DSL extension reaches into [TContext] for it. Keeping it out of the
 * components map avoids the runtime cast in a `TContext.streamElementDispatcher()`
 * accessor that no caller would use.
 */
interface StreamElementDispatcher {

    /**
     * Routes the element to the active interactor identified by the element's streamId.
     */
    suspend fun dispatchElement(element: StreamElement<*>)
}
