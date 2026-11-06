package org.tenshu.core.common.schema.migration

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ⚠⚠ These pin the E2j-4 deadlock fix (`core-schema-evolution.md` §8.9).
 *
 * In production a [TContext]'s `components` map is a **lazy view over the DI
 * graph**: every `get(key)` is a container lookup that may create the singleton.
 * A store-open migration runs *inside* the `single<ObjectStore>` factory, so the
 * graph is mid-construction on the calling thread while the migration runs on
 * another; any component the migration resolves that the calling thread is
 * already building deadlocks the two. That is exactly what shipped —
 * `OverlayTContext.components` was `base.components + overrides`, and `+` copies
 * the map, which enumerates it, which resolved **every** component (the
 * uploader, the on-demand resolver, the crypto manager) for every value a
 * migration created. Android hung at startup; iOS died with `SIGSEGV`.
 *
 * The invariant is therefore not "it produces the right values" but **"it
 * resolves one key and never enumerates"** — so the base here is a context that
 * fails loudly the moment anything walks it.
 */
class MigrationOverlayContextTest {

    /** A base whose bulk accessors are a test failure, mirroring the DI view's cost. */
    private class ExplodingBaseContext(
        private val backing: Map<String, TComponent>,
    ) : TContext {
        var lookups: Int = 0
            private set

        override val components: Map<String, TComponent> = object : Map<String, TComponent> {
            override val keys: Set<String> get() = backing.keys
            override val size: Int get() = backing.size
            override fun isEmpty(): Boolean = backing.isEmpty()
            override fun containsKey(key: String): Boolean = backing.containsKey(key)
            override fun containsValue(value: TComponent): Boolean = error("enumerated the base")

            override fun get(key: String): TComponent? {
                lookups++
                return backing[key]
            }

            override val values: Collection<TComponent> get() = error("enumerated the base")
            override val entries: Set<Map.Entry<String, TComponent>> get() = error("enumerated the base")
        }
    }

    private fun overlayOf(base: TContext): TContext {
        val live = createCoreTestRegistry()
        val migration = schemaMigration(THING_SERVICE_ID, fromVersion = 1) {
            migration(THING_ID, from = THING_AT_V1, to = THING_AT_V1)
        }
        return OverlayTContext(base, migration.toBoundOverlay(live))
    }

    @Test
    fun `reading an overridden component never enumerates the base graph`() {
        // ⚠ The base must be NON-empty: `Map.plus` returns early on an empty
        // source, so an empty base would let the bug through this very test.
        val base = ExplodingBaseContext(mapOf("tenshu/test/other" to object : TComponent {}))
        val overlay = overlayOf(base)

        // The two ids a migration actually uses. `error("enumerated the base")`
        // fires instead of this returning if the merge ever comes back.
        assertTrue(overlay.components[DataFactory.COMPONENT_ID] is SchemaOverlayProvider)
        assertTrue(overlay.components[SchemaProvider.COMPONENT_ID] is SchemaOverlayProvider)
        assertEquals(0, base.lookups, "an overridden id must not be looked up in the base at all")
    }

    @Test
    fun `a non-overridden component falls through to the base in one lookup without a walk`() {
        val other = object : TComponent {}
        val base = ExplodingBaseContext(mapOf("tenshu/test/other" to other))
        val overlay = overlayOf(base)

        assertSame(other, overlay.components["tenshu/test/other"])
        assertEquals(1, base.lookups, "exactly one keyed lookup, never an enumeration")
    }

    @Test
    fun `repeated reads stay proportional to the reads rather than the component count`() {
        val base = ExplodingBaseContext(mapOf("tenshu/test/other" to object : TComponent {}))
        val overlay = overlayOf(base)

        repeat(5) { overlay.components[DataFactory.COMPONENT_ID] }
        assertEquals(0, base.lookups)

        repeat(5) { overlay.components["tenshu/test/other"] }
        assertEquals(5, base.lookups, "one base lookup per read — no caching claim, no fan-out either")
    }
}
