package org.tenshu.core.request

import org.tenshu.core.Data
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.schema.Type

/**
 * Constructs request objects for parameterised types — the runtime mechanism behind
 * the request DSL (`file.getData()`, `service.find { ... }` chains). A fresh
 * `RequestObject` is returned per call; sharing is unsafe because each RO holds a
 * mutable [RequestChain] that accumulates operations as method chains are built.
 *
 * Provider, not registry: this surface is read-only. Registration of per-type
 * factories happens via `ModuleConfiguration.registerComposite(roFactory = ...)`
 * and equivalents — that's where the registry concern lives.
 *
 * Was `RoRegistry` in `:core-common/.../request/`; lifted to `:core` because its
 * peer types (`RequestChain`, `RequestObject`) live here and downstream call sites
 * in `:core` (chain materialisation, future schema-graph work) need access without
 * importing `:core-common`.
 */
interface RoProvider : TComponent {

    /**
     * Constructs a request object for the given parameterised [type]. Phase 5.0 of
     * `docs/adr/0009-operation-schema-typing.md` widened this from `type: String` so that
     * factories for parameterised types (`TList<E>`, `Maybe<E>`) can read the
     * element type from `type.params` at construction time.
     */
    fun <T : Data> createRequestObject(
        type: Type,
        requestChain: RequestChain,
        context: TContext,
    ): T

    companion object {
        const val COMPONENT_ID = "tenshu/core/request/roProvider"
    }
}

fun TContext.roProvider(): RoProvider = components[RoProvider.COMPONENT_ID] as RoProvider
