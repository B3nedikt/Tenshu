package org.tenshu.file.common

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.file.Data
import org.tenshu.core.file.Device
import org.tenshu.core.file.File
import org.tenshu.core.file.FileHistory
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.relation.DynamicPropertyRelation
import org.tenshu.core.relation.StaticPropertyRelation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the empty-Recent-Files bug.
 *
 * `list.sortBy(File.LastModifiedAt)` ships a **raw** [StaticPropertyRelation] in
 * the `SortBy` SELECTOR param (`SortByRo.sortByProperty` — `property.selector()`),
 * unlike `select { relations(handle) }` which unwraps the handle to a
 * `PropertySelector`. That runtime relation composite therefore has to be
 * registered in the production `coreCommonModule`; otherwise the proxy's
 * [BinaryParser] can't resolve its type id
 * (`tenshu/core/relation/property/value/static` → `cd88c88f…`) and the whole
 * callstack call dies in `ProxyGateway.call`, so `recentFiles` silently falls
 * back to an empty list.
 *
 * The Phase-6 in-process integration test never caught this: the local
 * connection passes live objects and never invokes the binary parser. Only a
 * serialize → parse round-trip over the real registry reproduces it — which is
 * exactly the client → proxy hop. This serialises the same `selector()` the
 * chain ships and parses it back through the production schema config.
 */
class PropertyRelationWireRegistrationTest {

    private val registry =
        createCoreTestRegistry().apply { registerConfig(fileServiceCommonModule) }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer(registry)
    private val parser = BinaryParser(registry, registry)

    @Test
    fun staticPropertyRelationSelectorRoundTripsAcrossTheWire() {
        // Exactly what `sortBy(File.LastModifiedAt)` puts on the wire.
        val selector = File.LastModifiedAt.selector()

        val parsed = parser.parse<StaticPropertyRelation>(serializer.serialize(selector))

        assertEquals(File.LastModifiedAt.id, parsed.reference.get())
    }

    @Test
    fun dynamicPropertyRelationSelectorRoundTripsAcrossTheWire() {
        // The symmetric dynamic-property wire form (e.g. File.Data / File.Preview).
        val selector = File.Data.selector()

        val parsed = parser.parse<DynamicPropertyRelation>(serializer.serialize(selector))

        assertEquals(File.Data.id, parsed.reference.get())
    }

    /**
     * Regression for the empty-Activity bug: `File.FileHistory` is an
     * `entityRelation`, and `registerEntityRelation` did NOT add its property id to
     * `typeMapping` (unlike `registerValueProperty`, which gets it via
     * `registerSchema`). So the parser couldn't decode the reference's id hash off
     * the wire — `getTypeId(bytes)` threw "No type id registered for bytes" — and
     * the whole `.relation(File.FileHistory)` read failed into an empty list.
     * In-process tests never caught it (they skip the parser).
     */
    @Test
    fun entityRelationSelectorRoundTripsAcrossTheWire() {
        val selector = File.FileHistory.selector()

        val parsed = parser.parse<StaticPropertyRelation>(serializer.serialize(selector))

        assertEquals(File.FileHistory.id, parsed.reference.get())
    }

    /**
     * The same latent gap for `registerReferenceProperty`: `FileModification.Device`
     * (the "modified on <device>" attribution) round-trips only once its property id
     * is in `typeMapping`.
     */
    @Test
    fun referencePropertySelectorRoundTripsAcrossTheWire() {
        val selector = FileModification.Device.selector()

        val parsed = parser.parse<StaticPropertyRelation>(serializer.serialize(selector))

        assertEquals(FileModification.Device.id, parsed.reference.get())
    }
}
