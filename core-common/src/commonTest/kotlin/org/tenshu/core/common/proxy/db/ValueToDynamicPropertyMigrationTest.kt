package org.tenshu.core.common.proxy.db

import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.TIntegerDto
import org.tenshu.core.common.module.dynamicProperty
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.valueProperty
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.number.TInteger
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.dynamicProperty
import org.tenshu.core.relation.valueProperty
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the read side of a **value property becoming a dynamic property** — the shape of
 * `File.Thumbnail` / `File.ThumbnailLarge` moving to the content plane, which wedges sync on every
 * device that already has rows of the old shape unless the read tolerates them.
 *
 * ## What actually changes under the row
 *
 * A property's stored value is read back by looking its *id* up in the schema registry
 * ([DbEntryMapper]'s relation walk: `schemaProvider.getSchema(relationEntry.key)`) — the row itself
 * carries no type tag for it. So the two authoring forms put **different schema kinds under the
 * same id**:
 *
 * | authoring | registers | stored as | read arm |
 * |---|---|---|---|
 * | `valueProperty` | a `ValueSchema` | the value **inline** | `is ValueSchema` |
 * | `dynamicProperty` | a `DynamicPropertyRelationSchema` | a child-row **reference** | `is CompositeSchema` |
 *
 * `RelationSchema` extends `CompositeSchema`, so after the flip every pre-existing row's inline
 * value matches the composite arm and is cast to a `LongValue` child-row id — a bare
 * `ClassCastException: ByteArrayValue cannot be cast to LongValue`, thrown for as long as the row
 * exists.
 *
 * ⚠⚠ **The shape baseline cannot catch this.** `SchemaShapeBaselineEnforcementTest` pins
 * *positional* composite layout, and a property relation is stored in a **named map**, not
 * positionally — so the baseline stays honestly green ("0 changed") across the flip, and an empty
 * migration justified by "nothing reshaped positionally" is true and not sufficient: the kind of a
 * *relation's* stored value is a stored-shape change the positional argument does not cover.
 *
 * The expected behaviour is that the stale inline value reads back as **absent**, not as a throw: a
 * dynamic property is resolved on demand, so a leftover eager value is garbage that the resolver
 * supersedes. Absent is also the only answer available — the bytes cannot be turned into a child
 * row at read time.
 */
class ValueToDynamicPropertyMigrationTest {

    /** The same property id, authored both ways — the before/after of the flip. */
    private val propertyId = "test/synthetic/thumbnail"

    /** `TByteArray`, like `File.Thumbnail` — the type that makes the failure a loud cast. */
    private val asValueProperty = valueProperty<TByteArray>(propertyId, TByteArray.Schema)

    private val asDynamicProperty: Property.Dynamic<TByteArray> =
        dynamicProperty<SyntheticTestData, TByteArray>(
            id = propertyId,
            receiver = SyntheticTestData.Schema,
        ) { TByteArrayDto(ByteArray(0)) }

    /** A `TInteger` twin of the same transition — see [aLongValuedPropertyIsUndetectableAndStillMisaddresses]. */
    private val longPropertyId = "test/synthetic/size"

    private val longAsValueProperty = valueProperty<TInteger>(longPropertyId, TInteger.Schema)

    private val longAsDynamicProperty: Property.Dynamic<TInteger> =
        dynamicProperty<SyntheticTestData, TInteger>(
            id = longPropertyId,
            receiver = SyntheticTestData.Schema,
        ) { TIntegerDto(0L) }

    private lateinit var oldProxy: TestProxy
    private lateinit var oldMapper: DbEntryMapper
    private lateinit var newMapper: DbEntryMapper

    @BeforeTest
    fun setup() {
        // v1: the property is an eagerly-stored value.
        val oldRegistry = createSyntheticTestRegistry()
        oldRegistry.registerConfig(
            tModule {
                valueProperty(fromId = SyntheticTestData.ID, property = asValueProperty) {
                    TByteArrayDto(it as ByteArray)
                }
                valueProperty(fromId = SyntheticTestData.ID, property = longAsValueProperty) {
                    TIntegerDto(it as Long)
                }
            }
        )
        oldProxy = TestProxy(schemaRegistry = oldRegistry)
        oldMapper = DbEntryMapper(
            context = oldProxy.tContext,
            schemaProvider = oldRegistry,
            dataFactory = oldRegistry,
            traitResolver = oldProxy.executionContext.traitResolver,
        )

        // v2: the same id is an on-demand dynamic property.
        val newRegistry = createSyntheticTestRegistry()
        newRegistry.registerConfig(
            tModule {
                dynamicProperty(fromId = SyntheticTestData.ID, property = asDynamicProperty)
                dynamicProperty(fromId = SyntheticTestData.ID, property = longAsDynamicProperty)
            }
        )
        val newProxy = TestProxy(schemaRegistry = newRegistry)
        newMapper = DbEntryMapper(
            context = newProxy.tContext,
            schemaProvider = newRegistry,
            dataFactory = newRegistry,
            traitResolver = newProxy.executionContext.traitResolver,
        )
    }

    @Test
    fun aStoredValuePropertyReadsBackAsAbsentOnceItBecomesADynamicProperty() {
        // Written by the old build: the value rides inline in the row's property map.
        val entity = Entity.create<SyntheticTestData>(
            context = oldProxy.tContext,
            composite = SyntheticTestData.Factory.create(oldProxy.tContext, "doc", 0L),
        ) {
            set(asValueProperty, ByteArray(64) { it.toByte() })
        }

        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        oldMapper.serialize(entity) { entries[it.dataIdHash] = it }
        val rootHash = oldProxy.executionContext.getDataId(entity)!!.getDataIdHash()

        // Read by the new build. Taking the composite arm throws
        // `ClassCastException: ByteArrayValue cannot be cast to LongValue` (an integer value here,
        // but the cast is on the DbValue kind, not the payload type).
        val deserialized = newMapper.deSerialize(
            dbEntry = entries[rootHash]!!,
            asEntity = true,
        ) { hash -> entries[hash] ?: error("Entry not found for hash $hash") }

        val properties = (deserialized as Entity<*>).properties
        assertNull(
            properties.entries.firstOrNull { it.key.get() == propertyId }?.value,
            "the stale eagerly-stored value must read back as absent, so the on-demand " +
                "resolver supersedes it — not throw and wedge every reader of the row",
        )
    }

    /**
     * ⚠⚠ **Documents a residual gap the read arm cannot close**: the same transition on a
     * **`TInteger`** property is undetectable, because a stale `LongValue` is byte-for-byte what a
     * legitimate child-row reference looks like.
     *
     * The composite arm therefore dereferences the stored **value** as a row id: a property set to
     * `4096` sends the reader looking for row `4096`. This test asserts that mis-addressing
     * directly — it is the *current* behaviour, deliberately pinned rather than hidden, because the
     * failure it produces is worse than the `TByteArray` cast: here the row happens to be absent
     * and the lookup fails loudly, but nothing in the mechanism guarantees absence, and a colliding
     * hash would be returned as the property's value with no error anywhere.
     *
     * ⇒ "it throws a `ClassCastException`" is a property of `TByteArray`, not of the defect. The
     * read arm can only reject a kind *mismatch*; it cannot reject a kind match that means
     * something else. Closing this needs the stale slots to stop existing (a migration that strips
     * them) or an authoring-time guard that refuses a property id changing registry kind across a
     * version — not more tolerance at the reader.
     */
    @Test
    fun aLongValuedPropertyIsUndetectableAndStillMisaddresses() {
        val entity = Entity.create<SyntheticTestData>(
            context = oldProxy.tContext,
            composite = SyntheticTestData.Factory.create(oldProxy.tContext, "doc", 0L),
        ) {
            set(longAsValueProperty, 4096L)
        }

        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        oldMapper.serialize(entity) { entries[it.dataIdHash] = it }
        val rootHash = oldProxy.executionContext.getDataId(entity)!!.getDataIdHash()

        val requestedHashes = mutableListOf<Long>()
        runCatching {
            newMapper.deSerialize(
                dbEntry = entries[rootHash]!!,
                asEntity = true,
            ) { hash ->
                requestedHashes += hash
                entries[hash] ?: error("Entry not found for hash $hash")
            }
        }

        assertTrue(
            4096L in requestedHashes,
            "the stored value 4096 should have been dereferenced as a child-row id — if this " +
                "stops happening the gap is closed and this test should become an assertNull",
        )
    }

    /**
     * ⚠⚠ **The harm itself, demonstrated rather than argued.**
     * [aLongValuedPropertyIsUndetectableAndStillMisaddresses] proves the stored *value* is used as a
     * row id, but there the lookup fails loudly because nothing lives at that address — which makes
     * the defect look like a noisy one. It is not. When something *does* live there, the reader
     * returns **another row's data as this property's value**, and no layer anywhere raises a thing:
     * the mapper got a well-formed entry, the caller got a well-formed property.
     *
     * This is the case the read arm structurally cannot reject — a kind *match* that means something
     * else — and the reason a reset alone does not close the defect: a fresh realm removes today's
     * stale slots, not the next property that changes registry kind.
     *
     * ⚠ Read as a **canary, not a specification.** It asserts current behaviour so the guard that
     * fixes it fails here loudly; when an authoring-time check refuses the kind change (or a
     * migration strips the slots), this becomes unreachable and should be deleted with it.
     */
    @Test
    fun aCollidingRowIsReturnedAsThePropertysValue_withNoErrorAnywhere() {
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()

        // An unrelated, perfectly legitimate row. Anything at all can be at this address; what
        // matters is only that the address is occupied.
        val bystander = Entity.create<SyntheticTestData>(
            context = oldProxy.tContext,
            composite = SyntheticTestData.Factory.create(oldProxy.tContext, "bystander", 99L),
        )
        oldMapper.serialize(bystander) { entries[it.dataIdHash] = it }
        val bystanderHash = oldProxy.executionContext.getDataId(bystander)!!.getDataIdHash()

        // The victim: written by the old build with the property holding a plain number that
        // happens to equal that row's hash. ⚠ Contrived only in how the collision is *arranged* —
        // a real `File.Size`, an epoch millis, an id, any Long can land on an occupied hash, and
        // the store is keyed by a 64-bit hash precisely so collisions are possible but rare.
        val victim = Entity.create<SyntheticTestData>(
            context = oldProxy.tContext,
            composite = SyntheticTestData.Factory.create(oldProxy.tContext, "victim", 0L),
        ) {
            set(longAsValueProperty, bystanderHash)
        }
        oldMapper.serialize(victim) { entries[it.dataIdHash] = it }
        val victimHash = oldProxy.executionContext.getDataId(victim)!!.getDataIdHash()

        // Read by the new build, where the same id is a dynamic property. No throw, no log, no
        // null — the property resolves, to the wrong row.
        val deserialized = newMapper.deSerialize(
            dbEntry = entries[victimHash]!!,
            asEntity = true,
        ) { hash -> entries[hash] ?: error("Entry not found for hash $hash") }

        val resolved = (deserialized as Entity<*>).properties.entries
            .firstOrNull { it.key.get() == longPropertyId }?.value

        assertNotNull(
            resolved,
            "the collision makes the mis-addressed lookup SUCCEED — if this is null the gap has " +
                "been closed and this canary should be deleted along with it",
        )
        // ⚠ Named, not merely non-null: the value that comes back IS the bystander row. A test
        // that only asserted "something resolved" would pass against a reader that had returned
        // any old thing — the point is precisely *whose* data this is.
        assertEquals(
            "bystander",
            (resolved as SyntheticTestData).name.get(),
            "a TInteger property resolved to an unrelated ENTITY, and nothing anywhere objected",
        )
        assertEquals(99L, resolved.value.get())
    }
}
