package org.tenshu.core.common.proxy

import org.tenshu.core.common.createCoreTestRegistry
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod
import kotlin.test.Test
import kotlin.test.fail

/**
 * Pins the contract documented in `docs/tech-debt.md`
 * §"Lazy-Fields Map Completeness as a Contract".
 *
 * Each schema-builder DSL function (`composite`, `operation`, `trait`, …)
 * hand-populates a `lazyFields` map. The `*Schema` interfaces declare typed
 * properties whose getters delegate to `getField(Fields.X)` — and `getField`,
 * since `LazyComposite` became stateless, throws an actionable
 * `IllegalStateException("…is not in the lazy field map of …")` when the
 * requested key isn't in the map.
 *
 * The footgun: a developer adds `val newField: Foo get() = getField(Fields.NEW)`
 * to a `*Schema` interface (and `Fields.NEW` to the companion) but forgets to
 * add `Fields.NEW to ...` in the DSL builder's `lazyFields` block. The call
 * site crashes at runtime — never seen at compile time.
 *
 * This test exercises every property getter on every `*Schema` supertype of
 * every registered schema. Any getter that throws the "not in the lazy field
 * map" sentinel fails the test, naming the schema, the property, and the
 * missing field id so the fix is one DSL-block addition away.
 *
 * **JVM-only.** Property enumeration requires `kotlin-reflect`; common code
 * has no equivalent. The covered schemas are the same across all platforms,
 * so a single JVM run protects every target.
 */
class SchemaLazyFieldsCompletenessTest {

    @Test
    fun every_declared_property_on_a_schema_supertype_resolves_via_lazy_fields() {
        val schemaRegistry = createCoreTestRegistry()
        val failures = mutableListOf<String>()

        for (schema in schemaRegistry.allSchemas()) {
            val schemaClass = schema::class
            // `memberProperties` returns the resolved set of properties with
            // overrides applied — mirrors what user code calls. Avoids the
            // false-positive where a parent interface's default getter is
            // invoked via reflection even though a derived interface overrides
            // it (e.g. `OperationSchema.typeParams` overrides
            // `CompositeSchema.typeParams` with a different backing key
            // during the `docs/adr/0005-composite-schema-typing.md` migration window).
            val properties = try {
                schemaClass.memberProperties
            } catch (_: Throwable) {
                // Anonymous schema impls can sometimes confuse kotlin-reflect's
                // top-level enumeration — skip rather than crash the whole run.
                continue
            }

            for (property in properties) {
                // Skip properties that aren't declared on a Tenshu schema
                // interface (Any.toString, Composite.fields, etc.).
                val declaringName = property.javaField?.declaringClass?.name
                    ?: property.getter.javaMethod?.declaringClass?.name
                    ?: continue
                if (!declaringName.startsWith("org.tenshu.")) continue

                try {
                    @Suppress("UNCHECKED_CAST")
                    (property as KProperty1<Any, *>).get(schema)
                } catch (e: Throwable) {
                    // Walk the cause chain looking for the LazyComposite
                    // "not in the lazy field map" sentinel. Other failures
                    // (ClassCastException from heuristic-driven `schema`
                    // getters, schema-id mismatches, anything else that
                    // happens to throw under reflection) are outside this
                    // test's contract — swallow them.
                    var cur: Throwable? = e
                    while (cur != null) {
                        if (cur.message?.contains("is not in the lazy field map") == true) {
                            failures += "${declaringName}.${property.name} on schema " +
                                    "${schemaClass.simpleName} — ${cur.message}"
                            break
                        }
                        cur = cur.cause
                    }
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "Schema(s) declare properties whose getter calls `getField(...)` for a key " +
                        "missing from the corresponding DSL builder's `lazyFields` map. Add the " +
                        "missing entries to the matching `composite(...) { lazyFields = { mapOf(...) } }` " +
                        "block in `:core/.../schema/Dsl.kt` (or the equivalent feature-service DSL):\n\n" +
                        failures.joinToString("\n  - ", prefix = "  - ")
            )
        }
    }
}
