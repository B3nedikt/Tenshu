package org.tenshu.sdk.ios

import org.tenshu.core.PureOperationRunner
import org.tenshu.core.common.PureOperationRunnerImplementation
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.time.SystemClock
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.LazyDataMaterializer
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RoProvider
import org.tenshu.core.time.Clock
import org.tenshu.core.trait.TraitResolver

/**
 * Parses a cross-app-open URL **without the app's runtime** (`control-cross-app-open.md` §5i).
 *
 * ⚠⚠⚠ **The decode must not depend on whether the user has a realm, and the first version did.**
 * It read `BinaryParser` out of the route-1 Koin graph — which on iOS is installed only once an app
 * finds a Control-provisioned session. So an unprovisioned app could not even *recognise* an
 * incoming open, and, worse, the graph is installed **after** launch: the very moment a launch URL
 * arrives is the moment it is least likely to exist. The symptom was an app that accepted the URL
 * and did nothing, five simulator runs in a row.
 *
 * ⭐ Nothing about decoding needs app state. The payload is core types only — an
 * `OpenTargetRequest` wrapping a `DataId` — and every one of them is registered by
 * `coreCommonModule`. So this builds the smallest context that can parse those and keeps it for the
 * process. ⚠ It is deliberately **decode-only**: no store, no client, no crypto beyond what the
 * component map demands, and nothing here may grow into a second runtime.
 *
 * ⚠ The registry and the context are mutually referential — the registry materialises through the
 * context, the context exposes the registry — which is why the context is published into the
 * holder before the parser is handed out. `createTestContext` resolves the same knot the same way.
 */
internal object OpenTargetDecoder {

    /** ⚠ Context, registry and parser are one unit — the registry materialises through the
     *  context and the context exposes the registry, so they cannot be built independently. */
    private class DecodeOnly(val context: TContext, val parser: BinaryParser)

    private val decodeOnly: DecodeOnly by lazy { build() }

    val parser: BinaryParser get() = decodeOnly.parser

    /** The context a probe URL is built against — see `OpenTargetProbe`. */
    val context: TContext get() = decodeOnly.context

    private fun build(): DecodeOnly {
        val components = mutableMapOf<String, TComponent>()
        val context = object : TContext {
            override val components: Map<String, TComponent> get() = components
        }
        val registry = createDefaultSchemaRegistry({ context })

        components[DataFactory.COMPONENT_ID] = registry
        components[SchemaProvider.COMPONENT_ID] = registry
        components[RoProvider.COMPONENT_ID] = registry
        components[OperationResolver.COMPONENT_ID] = registry
        components[LazyDataMaterializer.ID] = registry
        components[TraitResolver.ID] = TraitResolverImpl { context }
        components[PureOperationRunner.ID] = PureOperationRunnerImplementation(
            tContextProvider = { context },
            implementationFactoryProvider = {
                OperationImplementationFactory { id -> implementationFactory(id) }
            },
        )
        components[Clock.COMPONENT_ID] = SystemClock()

        return DecodeOnly(context = context, parser = BinaryParser(registry, registry))
    }
}
