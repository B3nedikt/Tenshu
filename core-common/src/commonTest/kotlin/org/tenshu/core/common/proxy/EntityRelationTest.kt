package org.tenshu.core.common.proxy

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.module.entityRelation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.entity.DataId
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.entityRelation
import org.tenshu.core.schema.propertyRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the **entity relation** primitive (`docs/proposals/core-nested-projection.md`
 * §3.3): a list-valued `Property` whose elements live in a per-entity array keyed by the
 * receiver's own `DataId` (an [DataId.EntityPropertyDataId]) — the one-to-many
 * counterpart of the reference value property ([ReferencePropertyTest]).
 *
 * Resolution reuses the existing array mechanism — `resolveProperty` returns an
 * [ArrayDatabaseReference] keyed by `EntityPropertyDataId(<receiver>, propertyId)`, which
 * `resolveList` materialises lazily — and the wire selector stays the uniform
 * `StaticPropertyRelation`. The array is populated by a maintained reverse-index in
 * production; here we seed it directly via `addToArray` and read it back.
 */
class EntityRelationTest {

    private val historyHandle: Property.Value<TList<SyntheticTestData>> =
        entityRelation(id = "test/synthetic/history", element = SyntheticTestData.Schema)

    private fun proxy() = TestProxy(schemaRegistry = createSyntheticTestRegistry()).apply {
        schemaRegistry.registerConfig(
            tModule {
                entityRelation(fromId = SyntheticTestData.ID, property = historyHandle)
            }
        )
    }

    private fun TestProxy.historyRef(parentName: String) = ArrayDatabaseReference(
        arrayId = DataId.EntityPropertyDataId.create(
            context = tContext,
            type = TList.ID,
            parentEntity = DataId.StringDataId.create(tContext, SyntheticTestData.ID, parentName),
            propertyId = historyHandle.id,
        ),
        isEntity = false,
        elementSchemaId = SyntheticTestData.ID,
    )

    @Test
    fun resolvesPerEntityArrayKeyedByReceiver() {
        val p = proxy()
        val parent = SyntheticTestData.Factory.create(p.tContext, name = "p1", value = 0)
        p.database.addToArray(p.historyRef("p1"), SyntheticTestData.Factory.create(p.tContext, "c1", 1))
        p.database.addToArray(p.historyRef("p1"), SyntheticTestData.Factory.create(p.tContext, "c2", 2))

        val resolved = p.executionContext.resolveProperty(parent, propertyRef(historyHandle.id))

        assertTrue(resolved is ArrayDatabaseReference, "entity relation resolves to a deferred array")
        val arrayId = resolved.arrayId
        assertTrue(arrayId is DataId.EntityPropertyDataId, "keyed by an EntityPropertyDataId")
        assertEquals(historyHandle.id, arrayId.propertyId.get(), "the property id is the array key's propertyId")
        assertEquals(
            "p1",
            (arrayId.parentEntity as DataId.StringDataId).keyValue.get(),
            "the array is parented to the receiver's own DataId",
        )

        val rows = p.executionContext.resolveList(resolved)!!.toList()
        assertEquals(
            listOf("c1", "c2"),
            rows.map { (it as SyntheticTestData).name.get() },
            "resolveList materialises the per-entity array elements",
        )
    }

    @Test
    fun isolatesByReceiver() {
        val p = proxy()
        p.database.addToArray(p.historyRef("p1"), SyntheticTestData.Factory.create(p.tContext, "c1", 1))
        val other = SyntheticTestData.Factory.create(p.tContext, name = "p2", value = 0)

        val resolved = p.executionContext.resolveProperty(other, propertyRef(historyHandle.id))
            as ArrayDatabaseReference
        val rows = p.executionContext.resolveList(resolved)?.toList() ?: emptyList()

        assertTrue(rows.isEmpty(), "a different receiver sees no rows — the array is per-entity")
    }
}
