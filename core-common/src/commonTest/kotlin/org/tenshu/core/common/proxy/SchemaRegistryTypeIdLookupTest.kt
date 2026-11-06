package org.tenshu.core.common.proxy

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.module.ModuleConfigurationImpl
import org.tenshu.core.common.util.idToUuid
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `SchemaRegistry.getTypeId(ByteArray)` — the by-bytes type-id lookup the binary parser hits once
 * per nested value it decodes.
 *
 * ⚠⚠ It used to be a **linear scan** over every registered mapping, because `ByteArray` cannot be
 * a hash key (its `equals` is reference-based) so the map had to be searched with `contentEquals`.
 * Measured on a real iPhone 2026-08-05: **1,307 entries scanned and ~390 µs per call, 80,000 calls
 * in 200 s ⇒ 31.7 s, about 16% of wall-clock** — and not only during sync, since every parse pays
 * it, so every IPC call and chain read did too.
 *
 * The tests here pin the two things a content-keyed index can get wrong, both of which would be
 * **silent**: resolving to the wrong id when a mapping is duplicated, and going stale when a config
 * registers after the index was built.
 */
class SchemaRegistryTypeIdLookupTest {

    @Test
    fun resolvesARegisteredTypeIdFromItsWireBytes() {
        val registry = createCoreTestRegistry()
        val id = "tenshu/core/string"

        assertEquals(id, registry.getTypeId(idToUuid(id)))
    }

    @Test
    fun aDistinctByteArrayInstanceWithEqualContentStillResolves() {
        // ⚠ The whole hazard of keying on bytes: `ByteArray` equality is reference-based, so an
        // index keyed on the array itself would miss every lookup — and the failure would look
        // like "no type id registered", far from the cause.
        val registry = createCoreTestRegistry()
        val id = "tenshu/core/string"
        val a = idToUuid(id)
        val b = idToUuid(id)
        check(a !== b) { "sanity: two distinct instances" }
        check(a.contentEquals(b)) { "sanity: with equal content" }

        assertEquals(registry.getTypeId(a), registry.getTypeId(b))
    }

    @Test
    fun theLastRegisteredConfigWinsADuplicatedMapping() {
        // ⚠⚠ PRECEDENCE, and it is inverted from the loop this replaced. The old code was
        // `configs.asReversed().firstNotNullOfOrNull { … }` — last-registered wins. An index built
        // in reversed order would resolve a duplicated type id to the WRONG type, silently, and
        // nothing about the resulting parse failure would point at this lookup.
        val registry = createCoreTestRegistry()
        val bytes = idToUuid("tenshu/test/duplicated")

        val first = ModuleConfigurationImpl().apply { typeMapping[bytes] = "first-wins-if-broken" }
        val second = ModuleConfigurationImpl().apply { typeMapping[bytes] = "last-must-win" }
        registry.registerConfig(first)
        registry.registerConfig(second)

        assertEquals("last-must-win", registry.getTypeId(bytes))
    }

    @Test
    fun aConfigRegisteredAfterTheFirstLookupIsStillVisible() {
        // ⚠ The index is built lazily and cached, so registration must drop it. Without that, a
        // module registered after the first parse would be invisible for the life of the process —
        // and only on paths that had already warmed the cache, which is the worst kind of bug.
        val registry = createCoreTestRegistry()
        // Warm the index.
        registry.getTypeId(idToUuid("tenshu/core/string"))

        val late = idToUuid("tenshu/test/registered-late")
        registry.registerConfig(ModuleConfigurationImpl().apply { typeMapping[late] = "late-type" })

        assertEquals("late-type", registry.getTypeId(late))
    }
}
