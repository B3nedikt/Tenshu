package org.tenshu.systemtest

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.schema.RelationSchema
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.elementSchema
import org.tenshu.core.schema.toType
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.fail

/**
 * **Every chain-readable service relation must be able to build a request object for its
 * element type** — the registration whose absence takes a screen down the first time
 * somebody writes a lambda over that relation's elements.
 *
 * ## The bug class this closes
 *
 * The element-lambda ops — `sortBy`/`sortByDescending`/`thenBy`, `filter`, `search`/`searchAll`,
 * `find` — build their chain by invoking the caller's lambda against a **synthetic element
 * request object**. Creating one needs a `type(…, roFactory = …)` registration in the service's
 * *client* module, and there are two distinct ways to not have it:
 *
 * | missing | symptom at the call site |
 * |---|---|
 * | no `type(…)` for the element type | `No request object factory registered for type: <id>` |
 * | `roFactory` returns a **bare** `CompositeRo`/`EntityRo` | `ClassCastException: CompositeRo cannot be cast to X` |
 *
 * Both are checked below, because the second is the harder one to read from a stack trace and
 * a factory that merely *exists* does not rule it out.
 *
 * ⚠ `select { … }` is **not** in that set despite taking a lambda: its receiver is a
 * `ListBuilder<Selector<R>>`, never the element. A relation read only through
 * `select { fields(…) }` works without the Ro, and dies the moment a *sort key* lambda is added
 * (`ErrorEventsClientChainTest`).
 *
 * ⚠ A `composite(…)` in a `*CommonModule` proves nothing either way: its `roFactory` parameter
 * is **optional**, so some services register both there and some register only the DTO. Only the
 * factory this test actually invokes settles it.
 *
 * ## Why generative rather than one test per type
 *
 * Repaired one type at a time, the registration is fixed only *after* a user hits the crash.
 * Pinning known-bare registrations one by one would still leave the *next* relation
 * unprotected. Walking the graph makes the gap fail here instead of on a user's screen, and makes
 * a new relation carry its Ro from the day it is declared.
 *
 * ## What is walked, and why `chainReadable` is the filter
 *
 * Every `ServiceDataRelationSchema` that is a `TList` **and** declares
 * [RelationSchema.ServiceDataRelationSchema.chainReadable] — that flag means exactly
 * "`service.relation` is readable through the chain DSL at all", so it is precisely the set in
 * which a caller *can* write an element lambda. ⚠ Not `maintained`: that is a
 * membership-maintenance opt-in, and the relations which opt out of it (`core-relation-system.md`
 * R2b) are still perfectly chain-readable.
 *
 * ## Coverage: all 20 schema services
 *
 * `serviceGraphConfigs()` wires every service in the repo that has a schema — all 20.
 * (`:commerce-service` is the 21st `*-service` module but is a Ktor server with no `-common` /
 * `-client` twins and no `tModule`, so there is nothing to register.) The `required` floor below
 * keeps them there, because a test whose input silently shrinks passes by covering less.
 *
 * ⚠ Note `fit` is not and cannot be on that list: there is no `fit-service`. Fit's rows are
 * `:health-service`.
 */
class ElementRequestObjectRegistrationTest {

    @Test
    fun everyChainReadableRelationCanBuildAnElementRequestObject() = runTest {
        val configs = serviceGraphConfigs()
        val registry: SchemaRegistry = createCoreTestRegistry().apply {
            configs.forEach { registerConfig(it) }
        }

        // typeId → the Kotlin interface the module registered it under. This is what makes the
        // ClassCastException half checkable: the element-lambda ops cast the synthetic object to
        // the element interface, so "a factory exists" is only half the contract.
        val interfaceForType: Map<String, KClass<out Data>> = buildMap {
            configs.forEach { config ->
                config.classToTypeMapping.forEach { (kClass, typeId) -> put(typeId, kClass) }
            }
        }

        // A real proxy, so the chain and context are the ones a client actually passes — a hand
        // -rolled fake would prove the factory runs against something, not against production.
        val proxy = TestProxy(schemaRegistry = registry, coroutineScope = backgroundScope)
        val chain = LocalRequestChain(proxy.localTaskExecutor, proxy.tContext)

        val failures = mutableListOf<String>()
        var checked = 0

        // ⚠⚠ `distinctBy` is load-bearing, not tidiness. `allSchemas()` is
        // `configs.flatMap { it.schemaMapping.values }`, and BOTH a service's `-common` and its
        // `-client` module call `service(…)` — so every relation of a correctly-paired service
        // appears twice. Without this, one defect in one relation reports as two, and the
        // denominator is inflated by however many services happen to be wired as pairs.
        val services = registry.allSchemas()
            .filterIsInstance<ServiceSchema>()
            .distinctBy { it.id.get() }
            .sortedBy { it.id.get() }

        // A test whose input can silently shrink is a test that goes green by covering less. If a
        // build-file edit drops one of these from `serviceGraphConfigs()`, the walk finds fewer
        // relations and still passes — so name the floor and fail loudly instead. Every service
        // here must stay.
        val required = listOf(
            "tenshu/ingest/service",
            "tenshu/logging/service",
            "tenshu/mapRegion/service",
            "tenshu/place/service",
            "tenshu/storage/service",
            "tenshu/timeline/service",
        )
        val walked = services.map { it.id.get() }.toSet()
        (required - walked).let { absent ->
            if (absent.isNotEmpty()) {
                fail(
                    "These services are not in the graph, so nothing below judged them: " +
                        "${absent.sorted()}.\nAdd the `-common` AND `-client` pair to " +
                        "`system-test-utils/build.gradle.kts` and `ServiceGraphConfigs.kt`. " +
                        "A common-only entry makes every element type of that service look " +
                        "unregistered, because element Ros are registered only in `-client`.",
                )
            }
        }

        services
            .forEach { service ->
                service.relationSchemas.toList()
                    .filterIsInstance<RelationSchema.ServiceDataRelationSchema>()
                    .distinctBy { it.id.get() }
                    .forEach forEachRelation@{ relation ->
                        if (relation.type.baseSchema?.id?.get() != TList.ID) return@forEachRelation
                        if (!relation.chainReadable) return@forEachRelation

                        val elementTypeId = relation.type.elementSchema().id.get()
                        val where = "${service.id.get()} → ${relation.id.get()} (element $elementTypeId)"
                        checked++

                        val requestObject = try {
                            registry.createRequestObject<Data>(
                                type = relation.type.elementSchema().toType(),
                                requestChain = chain,
                                context = proxy.tContext,
                            )
                        } catch (e: IllegalStateException) {
                            failures += "$where\n      no element Ro: ${e.message}"
                            return@forEachRelation
                        }

                        val expected = interfaceForType[elementTypeId]
                        if (expected != null && !expected.isInstance(requestObject)) {
                            // ⚠ `simpleName` is null for the anonymous `object : CompositeRo(…)`
                            // these factories return — which is precisely the shape that fails
                            // this check, so the null branch is the COMMON one, not the edge case.
                            // Without the fallback the failure reads "factory returned null".
                            val actual = requestObject::class.let { it.simpleName ?: it.toString() }
                            failures += "$where\n      bare Ro: factory returned " +
                                "$actual, which does not implement " +
                                "${expected.simpleName} — the lambda's cast would throw"
                        }
                    }
            }

        if (failures.isNotEmpty()) {
            fail(
                buildString {
                    appendLine(
                        "${failures.size} of $checked chain-readable service relations cannot build " +
                            "an element request object.",
                    )
                    appendLine(
                        "Each one crashes the first caller who writes sortBy/filter/search/find " +
                            "over it — at chain-build time, so it takes the whole screen down.",
                    )
                    appendLine()
                    failures.sorted().forEach { appendLine("  • $it") }
                    appendLine()
                    appendLine("Fix: register a TYPED Ro in the owning service's *ClientModule —")
                    appendLine("`FileServiceClientModule` and `LoggingServiceClientModule` are the templates:")
                    appendLine()
                    appendLine("    type(")
                    appendLine("        interfaceClass = X::class,")
                    appendLine("        dataSchema = X.Schema,")
                    appendLine("        roFactory = { chain, ctx ->")
                    appendLine("            object : CompositeRo(chain, ctx, X.Schema), X {")
                    appendLine("                override val schema: CompositeSchema get() = X.Schema")
                    appendLine("            }")
                    appendLine("        },")
                    appendLine("    )")
                    appendLine()
                    appendLine("⚠ `EntityRo<X>` instead of `CompositeRo` when the element is an Entity, and the")
                    appendLine("  interface MUST be in the supertype list — a bare CompositeRo moves the crash to")
                    appendLine("  a ClassCastException rather than fixing it.")
                },
            )
        }
    }
}
