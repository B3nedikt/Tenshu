package org.tenshu.core.common.proxy.db

import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.TIntegerDto
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.valueProperty
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.number.TInteger
import org.tenshu.core.relation.valueProperty
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Proves the [valueProperty] primitive (docs/adr/0013-property-authoring-unification.md):
 * a stored scalar value property set on an entity survives a serialize ->
 * deserialize round-trip via [DbEntryMapper], using the
 * dbEntryMapper's existing `is ValueSchema` property branch — no dbEntryMapper
 * change required.
 */
class ValuePropertyRoundTripTest {

    private val sizeProp = valueProperty<TInteger>("test/synthetic/sizeProp", TInteger.Schema)

    private lateinit var testProxy: TestProxy
    private lateinit var dbEntryMapper: DbEntryMapper

    @BeforeTest
    fun setup() {
        val registry = createSyntheticTestRegistry()
        registry.registerConfig(
            tModule {
                valueProperty(
                    fromId = SyntheticTestData.ID,
                    property = sizeProp,
                ) { TIntegerDto(it as Long) }
            }
        )
        testProxy = TestProxy(schemaRegistry = registry)
        dbEntryMapper = DbEntryMapper(
            context = testProxy.tContext,
            schemaProvider = registry,
            dataFactory = registry,
            traitResolver = testProxy.executionContext.traitResolver,
        )
    }

    @Test
    fun valuePropertyRoundTrips() {
        val entity = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "doc", 0L),
        ) {
            set(sizeProp, 4096L)
        }

        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        dbEntryMapper.serialize(entity) { entries[it.dataIdHash] = it }

        val rootHash = testProxy.executionContext.getDataId(entity)!!.getDataIdHash()
        val deserialized = dbEntryMapper.deSerialize(
            dbEntry = entries[rootHash]!!,
            asEntity = true,
        ) { hash -> entries[hash] ?: error("Entry not found for hash $hash") }

        assertIs<Entity<SyntheticTestData>>(deserialized)
        val size = deserialized.get(sizeProp)?.get()
        assertEquals(4096L, size, "value property must survive the round-trip")
    }
}
