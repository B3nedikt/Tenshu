package org.tenshu.systemtest

import org.junit.Test
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.RequestChainBuilder
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.toType
import kotlin.test.fail

/**
 * Every composite that can be a chain-DSL **predicate receiver** must have a request-object
 * factory that actually produces its interface.
 *
 * ⚠⚠ **The bug this exists for is silent at registration and loud a long way away.** A type
 * registered with a `dtoFactory` but a *bare* `CompositeRo` request-object factory reads
 * perfectly — rows materialise, projections work — and then `filter<Moment> { … }` dies with
 * `class CompositeRo cannot be cast to class Moment`, thrown from inside `FilterRo`, naming
 * neither the module nor the registration that caused it. `dtoFactory` builds the row when it
 * is **read**; `roFactory` builds the **receiver** the predicate lambda runs against, and
 * nothing connects the two.
 *
 * MQ-2 hit it on three types at once (`Moment`, `Place`, plus their siblings) while
 * `Workout`, `HomeDevice` and `FileModification` were already correct — the difference being
 * only that somebody had written `object : CompositeRo(…), X {}` in those three cases and
 * `CompositeRo(…)` in the others. That is not a distinction anyone can be expected to hold
 * in their head, so it is checked here instead.
 *
 * ⚠ Coverage is the service graph this module wires, exactly as
 * `SchemaShapeBaselineEnforcementTest`'s is — widening it means adding `*-common` modules to
 * this module's dependencies.
 */
class TypedPredicateReceiverTest {

    @Test
    fun everyRegisteredCompositeProducesItsOwnInterfaceAsAPredicateReceiver() {
        val configs = serviceGraphConfigs()
        val registry = createCoreTestRegistry().apply { configs.forEach { registerConfig(it) } }
        val proxy = TestProxy(schemaRegistry = registry)
        val context = proxy.tContext

        val offenders = mutableListOf<String>()

        // ⚠ Walked in registration order and de-duplicated by type id, because a LATER
        // config wins at lookup — a client module registering a bare `CompositeRo` shadows
        // its own common module's typed one, which is precisely how MQ-2's three types
        // broke while looking correctly registered.
        val interfaces = configs
            .flatMap { config -> config.classToTypeMapping.entries.map { it.key to it.value } }
            .distinctBy { it.second }

        interfaces.forEach { (interfaceClass, typeId) ->
            val schema = registry.getSchema(typeId) as? CompositeSchema ?: return@forEach
            // Parameterised composites (lists, maybes) resolve their factory from the type
            // argument, not the base id, so they are not predicate receivers in this sense.
            if (schema.id.get() != typeId) return@forEach

            val receiver = runCatching {
                context.roProvider().createRequestObject<Data>(
                    type = schema.toType(),
                    requestChain = RequestChainBuilder(context),
                    context = context,
                )
            }.getOrNull() ?: return@forEach

            if (!interfaceClass.isInstance(receiver)) {
                offenders += "$typeId → ${receiver::class.simpleName} " +
                    "(expected ${interfaceClass.simpleName})"
            }
        }

        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("These types cannot be used in a chain-DSL predicate — their")
                    appendLine("roFactory returns a bare CompositeRo, so `filter<T> { … }` will")
                    appendLine("throw a ClassCastException from inside FilterRo:")
                    offenders.sorted().forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Fix: in the module that registers the type, replace")
                    appendLine("  roFactory = { chain, ctx -> CompositeRo(chain, ctx, X.Schema) }")
                    appendLine("with the typed form used by FileModification / Workout:")
                    appendLine("  roFactory = { chain, ctx ->")
                    appendLine("      object : CompositeRo(chain, ctx, X.Schema), X {")
                    appendLine("          override val schema: CompositeSchema get() = X.Schema")
                    appendLine("      }")
                    appendLine("  }")
                }
            )
        }
    }
}
