package org.tenshu.core.common.proxy.db

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticConstant
import org.tenshu.core.common.SyntheticConstantHolder
import org.tenshu.core.common.SyntheticGenericHolder
import org.tenshu.core.common.SyntheticGrandparent
import org.tenshu.core.common.SyntheticParent
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.name
import org.tenshu.core.common.proxy.Projection
import org.tenshu.core.common.value
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.id
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DbEntryMapperTest {

    private lateinit var testProxy: TestProxy
    private lateinit var dbEntryMapper: DbEntryMapper

    @BeforeTest
    fun setup() {
        val registry = createSyntheticTestRegistry()
        testProxy = TestProxy(schemaRegistry = registry)
        dbEntryMapper = DbEntryMapper(
            context = testProxy.tContext,
            schemaProvider = registry,
            dataFactory = registry,
            traitResolver = testProxy.executionContext.traitResolver
        )
    }

    @Test
    fun testSerializeAndDeserializeEntity() {
        // GIVEN: A synthetic entity
        val entity = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "test-name", 42L),
            properties = emptyMap()
        )

        // WHEN: Serialized to DB entries
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        dbEntryMapper.serialize(entity) { entries[it.dataIdHash] = it }

        // THEN: We have entries
        val rootHash = testProxy.executionContext.getDataId(entity)!!.getDataIdHash()
        val rootEntry = entries[rootHash]!!
        assertEquals(SyntheticTestData.ID, rootEntry.type)

        // WHEN: Deserialized back
        val deserialized = dbEntryMapper.deSerialize(rootEntry, asEntity = true) { hash ->
            entries[hash] ?: error("Entry not found for hash $hash")
        }

        // THEN: It is an entity of the correct type and has the correct values
        assertIs<Entity<SyntheticTestData>>(deserialized)
        assertEquals(SyntheticTestData.ID, deserialized.type.get())

        assertEquals("test-name", deserialized.name.get())
        assertEquals(42L, deserialized.value.get())
    }

    @Test
    fun testSerializePlainCompositeWithMandatoryFields() {
        // GIVEN: A plain composite (snapshot) with mandatory fields (UUID and timestamp)
        // We use FolderAddedNotification as an example of a type with mandatory fields in its parent
        val notification = SyntheticTestData.Factory.create(testProxy.tContext, "test-name", 123L)

        // WHEN: Serialized
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        dbEntryMapper.serialize(notification) { entries[it.dataIdHash] = it }

        // THEN: Serialization should succeed (my fix ensures it uses getField instead of getOptionalField)
        val hash = testProxy.executionContext.getDataId(notification)!!.getDataIdHash()
        assertNotNull(entries[hash])
        assertEquals(SyntheticTestData.ID, entries[hash]!!.type)
    }

    /**
     * Plain composite (non-entity) round-trip. Confirms the [Entity]-less
     * path stores fields positionally and that the dbEntryMapper reconstructs
     * the typed composite without losing field identity.
     */
    @Test
    fun roundTrip_plainCompositeWithoutEntityProperties() {
        val original = SyntheticTestData.Factory.create(testProxy.tContext, "alpha", 7L)
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()

        dbEntryMapper.serialize(original) { entries[it.dataIdHash] = it }

        val hash = testProxy.executionContext.getDataId(original)!!.getDataIdHash()
        val deserialized = dbEntryMapper.deSerialize(
            dbEntry = entries[hash]!!,
            getEntry = { entries[it]!! }
        )

        assertIs<SyntheticTestData>(deserialized)
        assertEquals("alpha", deserialized.name.get())
        assertEquals(7L, deserialized.value.get())
        assertEquals(SyntheticTestData.ID, deserialized.id)
    }

    /**
     * Re-saving the same DataId with different field values must overwrite
     * the existing entry. Without this, plain field updates would either
     * accumulate or corrupt the entry. The companion only-grow tests for
     * parameterised list fields live in `:file-service-proxy/.../DatabaseTest`
     * because they need a list-bearing schema (Folder.files); the
     * `SyntheticTestData` schema used here carries no list field.
     */
    @Test
    fun roundTrip_secondSaveOverwritesFields() {
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        dbEntryMapper.serialize(
            SyntheticTestData.Factory.create(testProxy.tContext, "gamma", 1L)
        ) { entries[it.dataIdHash] = it }
        dbEntryMapper.serialize(
            SyntheticTestData.Factory.create(testProxy.tContext, "gamma", 999L)
        ) { entries[it.dataIdHash] = it }

        val dataId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = "gamma"
        )
        val deserialized = dbEntryMapper.deSerialize(
            dbEntry = entries[dataId.getDataIdHash()]!!,
            getEntry = { entries[it]!! }
        ) as SyntheticTestData

        assertEquals(999L, deserialized.value.get())
    }

    /**
     * `deserializeArray` walks a `TList.ID`-typed entry whose `fields` hold
     * `LongValue` hashes pointing at sibling composite entries. This is the
     * exact shape `DatabaseImpl.addToArray` builds — covering it here keeps
     * the `:core-common` dbEntryMapper-level regression coverage independent
     * of the file-service schema-level test in `:file-service-proxy`.
     */
    @Test
    fun deserializeArray_walksHashesAndReturnsItems() {
        val items = listOf(
            SyntheticTestData.Factory.create(testProxy.tContext, "x", 1L),
            SyntheticTestData.Factory.create(testProxy.tContext, "y", 2L),
            SyntheticTestData.Factory.create(testProxy.tContext, "z", 3L)
        )
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        items.forEach { item ->
            dbEntryMapper.serialize(item) { entries[it.dataIdHash] = it }
        }

        val itemHashes = items.map { item ->
            DataId.StringDataId.create(
                context = testProxy.tContext,
                type = SyntheticTestData.ID,
                keyValue = item.name.get()
            ).getDataIdHash()
        }
        val arrayHash = 9999L
        entries[arrayHash] = ObjectStore.DbEntry(
            type = TList.ID,
            dataIdHash = arrayHash,
            fields = itemHashes.map { ObjectStore.DbValue.LongValue(it) },
            properties = emptyMap()
        )

        val read = dbEntryMapper.deserializeArray(
            arrayDataIdHash = arrayHash,
            getEntry = { entries[it] ?: error("missing $it") }
        ).toList()

        assertEquals(3, read.size)
        assertEquals(
            listOf("x", "y", "z"),
            read.map { (it as SyntheticTestData).name.get() }
        )
    }

    /**
     * The `offset`/`limit` window slices the member-hash list *before*
     * deserialisation: only windowed members are read (`getEntry` never
     * touches the others), and out-of-range windows clamp instead of
     * throwing.
     */
    @Test
    fun deserializeArray_windowSlicesBeforeDeserialising() {
        val items = ('a'..'e').mapIndexed { index, c ->
            SyntheticTestData.Factory.create(testProxy.tContext, c.toString(), index.toLong())
        }
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        items.forEach { item ->
            dbEntryMapper.serialize(item) { entries[it.dataIdHash] = it }
        }
        val itemHashes = items.map { item ->
            DataId.StringDataId.create(
                context = testProxy.tContext,
                type = SyntheticTestData.ID,
                keyValue = item.name.get()
            ).getDataIdHash()
        }
        val arrayHash = 8888L
        entries[arrayHash] = ObjectStore.DbEntry(
            type = TList.ID,
            dataIdHash = arrayHash,
            fields = itemHashes.map { ObjectStore.DbValue.LongValue(it) },
            properties = emptyMap()
        )

        fun readWindow(offset: Int, limit: Int): Pair<List<String>, Set<Long>> {
            val touched = mutableSetOf<Long>()
            val names = dbEntryMapper.deserializeArray(
                arrayDataIdHash = arrayHash,
                offset = offset,
                limit = limit,
                getEntry = { hash ->
                    touched += hash
                    entries[hash] ?: error("missing $hash")
                }
            ).toList().map { (it as SyntheticTestData).name.get() }
            return names to touched
        }

        // Middle window: only the windowed members are deserialised.
        val (middle, touchedMiddle) = readWindow(offset = 1, limit = 2)
        assertEquals(listOf("b", "c"), middle)
        assertEquals(
            setOf(arrayHash, itemHashes[1], itemHashes[2]),
            touchedMiddle,
            "members outside the window must never be read"
        )

        // Clamping: partial last window, offset past the end, zero limit.
        assertEquals(listOf("d", "e"), readWindow(offset = 3, limit = 10).first)
        assertEquals(emptyList(), readWindow(offset = 7, limit = 3).first)
        assertEquals(emptyList(), readWindow(offset = 0, limit = 0).first)

        // Defaults cover the full array (Int.MAX_VALUE limit must not overflow).
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            readWindow(offset = 0, limit = Int.MAX_VALUE).first
        )
    }

    // --- genericData field (DbValue.GenericValue) -----------------------------

    /**
     * A `genericData` field round-trips a **primitive** value: stored as a
     * self-describing `GenericValue(typeId, inline)`, read back as the right
     * concrete `Data` — the case opaque `LongValue` storage can't disambiguate.
     */
    @Test
    fun genericField_roundTripsPrimitiveValue() {
        val holder = SyntheticGenericHolder.Factory.create(
            testProxy.tContext,
            name = "pref-a",
            value = 42L.toTInteger(testProxy.tContext),
        )
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        dbEntryMapper.serialize(holder) { entries[it.dataIdHash] = it }

        // The slot is a self-describing GenericValue, not a bare primitive/reference.
        val hash = testProxy.executionContext.getDataId(holder)!!.getDataIdHash()
        val valueSlot = entries[hash]!!.fields[1]
        assertIs<ObjectStore.DbValue.GenericValue>(valueSlot)
        assertEquals(TInteger.ID, valueSlot.typeId)

        val deserialized = dbEntryMapper.deSerialize(entries[hash]!!) { entries[it]!! }
        assertIs<SyntheticGenericHolder>(deserialized)
        val value = deserialized.value
        assertIs<TInteger>(value)
        assertEquals(42L, value.get())
    }

    /**
     * A `genericData` field round-trips a **composite** value: stored as
     * `GenericValue(typeId, LongValue reference)`, read back by resolving the
     * referenced entry.
     */
    @Test
    fun genericField_roundTripsCompositeValue() {
        val inner = SyntheticTestData.Factory.create(testProxy.tContext, "inner", 5L)
        val holder = SyntheticGenericHolder.Factory.create(
            testProxy.tContext,
            name = "pref-b",
            value = inner,
        )
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        dbEntryMapper.serialize(holder) { entries[it.dataIdHash] = it }

        val hash = testProxy.executionContext.getDataId(holder)!!.getDataIdHash()
        val valueSlot = entries[hash]!!.fields[1]
        assertIs<ObjectStore.DbValue.GenericValue>(valueSlot)
        assertEquals(SyntheticTestData.ID, valueSlot.typeId)

        val deserialized = dbEntryMapper.deSerialize(entries[hash]!!) { entries[it]!! }
        assertIs<SyntheticGenericHolder>(deserialized)
        val value = deserialized.value
        assertIs<SyntheticTestData>(value)
        assertEquals("inner", value.name.get())
    }

    // --- constant fields (TConstant stored by schema id) ----------------------

    /**
     * A **plain** constant field round-trips: a `TConstant` is written as a
     * `StringValue` of its schema id and read back via `createConstant`. This is
     * the positional-field counterpart of the genericData probe that first
     * surfaced the gap (a constant field previously fell through to
     * `error("Unknown field type")`).
     */
    @Test
    fun constantField_roundTripsInPlainCompositeField() {
        val holder = SyntheticConstantHolder.Factory.create(
            testProxy.tContext,
            name = "holder-a",
            kind = SyntheticConstant.Beta,
        )
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        dbEntryMapper.serialize(holder) { entries[it.dataIdHash] = it }

        // The KIND slot is stored as a StringValue of the constant's schema id.
        val hash = testProxy.executionContext.getDataId(holder)!!.getDataIdHash()
        val kindSlot = entries[hash]!!.fields[1]
        assertIs<ObjectStore.DbValue.StringValue>(kindSlot)
        assertEquals(SyntheticConstant.Beta.schema.id.get(), kindSlot.value)

        val deserialized = dbEntryMapper.deSerialize(entries[hash]!!) { entries[it]!! }
        assertIs<SyntheticConstantHolder>(deserialized)
        assertEquals(SyntheticConstant.Beta, deserialized.kind)
    }

    /**
     * An **optional** constant field round-trips both present and absent: a set
     * value comes back as the constant; an unset one comes back `null` (stored as
     * `NullValue`), never a spurious constant.
     */
    @Test
    fun constantField_roundTripsOptionalPresentAndAbsent() {
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()

        val withOptional = SyntheticConstantHolder.Factory.create(
            testProxy.tContext,
            name = "holder-present",
            kind = SyntheticConstant.Alpha,
            optionalKind = SyntheticConstant.Beta,
        )
        dbEntryMapper.serialize(withOptional) { entries[it.dataIdHash] = it }

        val withoutOptional = SyntheticConstantHolder.Factory.create(
            testProxy.tContext,
            name = "holder-absent",
            kind = SyntheticConstant.Alpha,
            optionalKind = null,
        )
        dbEntryMapper.serialize(withoutOptional) { entries[it.dataIdHash] = it }

        fun readBack(key: String): SyntheticConstantHolder {
            val hash = DataId.StringDataId.create(
                context = testProxy.tContext,
                type = SyntheticConstantHolder.ID,
                keyValue = key,
            ).getDataIdHash()
            return dbEntryMapper.deSerialize(entries[hash]!!) { entries[it]!! } as SyntheticConstantHolder
        }

        val present = readBack("holder-present")
        assertEquals(SyntheticConstant.Alpha, present.kind)
        assertEquals(SyntheticConstant.Beta, present.optionalKind)

        val absent = readBack("holder-absent")
        assertEquals(SyntheticConstant.Alpha, absent.kind)
        assertNull(absent.optionalKind, "an unset optional constant field reads back as null")
    }

    /**
     * A `genericData` field round-trips a **constant** value: stored as
     * `GenericValue(typeId = constant schema id, payload = StringValue(schema id))`,
     * read back via `createConstant`. This is the exact shape the SyncPolicy
     * `DeviceSetting.value` preference storage relies on — and the case that first
     * threw `StringValue cannot be cast to LongValue` before the fix.
     */
    @Test
    fun genericField_roundTripsConstantValue() {
        val holder = SyntheticGenericHolder.Factory.create(
            testProxy.tContext,
            name = "pref-const",
            value = SyntheticConstant.Beta,
        )
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        dbEntryMapper.serialize(holder) { entries[it.dataIdHash] = it }

        val hash = testProxy.executionContext.getDataId(holder)!!.getDataIdHash()
        val valueSlot = entries[hash]!!.fields[1]
        assertIs<ObjectStore.DbValue.GenericValue>(valueSlot)
        assertEquals(SyntheticConstant.Beta.schema.id.get(), valueSlot.typeId)

        val deserialized = dbEntryMapper.deSerialize(entries[hash]!!) { entries[it]!! }
        assertIs<SyntheticGenericHolder>(deserialized)
        assertEquals(SyntheticConstant.Beta, deserialized.value)
    }

    // --- Projection pushdown (Phase 2) ----------------------------------------

    /** Serialize a [SyntheticParent] (with a composite [child]); returns the entry
     *  store, the parent's id, and the child entry's hash. */
    private fun serializeParent(
        childName: String = "child-name",
        childValue: Long = 7L,
    ): Triple<MutableMap<Long, ObjectStore.DbEntry>, DataId, Long> {
        val child = SyntheticTestData.Factory.create(testProxy.tContext, childName, childValue)
        val parent = Entity.create<SyntheticParent>(
            context = testProxy.tContext,
            composite = SyntheticParent.Factory.create(testProxy.tContext, "parent", child),
            properties = emptyMap()
        )
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        dbEntryMapper.serialize(parent) { entries[it.dataIdHash] = it }
        val parentDataId = testProxy.executionContext.getDataId(parent)!!
        val childHash = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = SyntheticTestData.ID,
            keyValue = childName
        ).getDataIdHash()
        return Triple(entries, parentDataId, childHash)
    }

    private fun deSerializeParent(
        entries: MutableMap<Long, ObjectStore.DbEntry>,
        parentDataId: DataId,
        projection: Projection?,
        fetched: MutableSet<Long> = mutableSetOf(),
    ): Entity<*> {
        val result = dbEntryMapper.deSerialize(
            dbEntry = entries[parentDataId.getDataIdHash()]!!,
            asEntity = true,
            dataId = parentDataId,
            projection = projection,
        ) { hash -> fetched += hash; entries[hash] ?: error("missing $hash") }
        return result as Entity<*>
    }

    private fun Entity<*>.field(id: String): Data? =
        fields.entries.firstOrNull { it.key.get() == id }?.value

    /**
     * `skipUnprojectedComposites = true` + the field not in `keepFields` ⇒ the
     * composite scalar field is omitted *and its entry is never fetched*.
     */
    @Test
    fun projection_skipsUnprojectedCompositeField() {
        val (entries, parentDataId, childHash) = serializeParent()
        val fetched = mutableSetOf<Long>()

        val entity = deSerializeParent(
            entries, parentDataId,
            Projection(keepFields = emptySet(), skipUnprojectedComposites = true),
            fetched,
        )

        assertNull(entity.field(SyntheticParent.Companion.Field.CHILD), "field should be omitted")
        assertFalse(childHash in fetched, "skipped child entry must not be fetched")
    }

    /** A composite field named in `keepFields` is materialised (and fetched). */
    @Test
    fun projection_keepsNamedCompositeField() {
        val (entries, parentDataId, childHash) = serializeParent()
        val fetched = mutableSetOf<Long>()

        val entity = deSerializeParent(
            entries, parentDataId,
            Projection(
                keepFields = setOf(SyntheticParent.Companion.Field.CHILD),
                skipUnprojectedComposites = true,
            ),
            fetched,
        )

        val child = entity.field(SyntheticParent.Companion.Field.CHILD)
        assertIs<SyntheticTestData>(child)
        assertEquals("child-name", child.name.get())
        assertTrue(childHash in fetched, "kept child entry must be fetched")
    }

    /**
     * The `searchAll().select` shape: a select is downstream but an intervening op
     * may read scalar fields, so `skipUnprojectedComposites = false` keeps all
     * composite scalar fields (only relations are skipped — covered end-to-end in
     * the engine integration test).
     */
    @Test
    fun projection_withoutCompositeSkip_keepsCompositeFields() {
        val (entries, parentDataId, _) = serializeParent()

        val entity = deSerializeParent(
            entries, parentDataId,
            Projection(keepFields = emptySet(), skipUnprojectedComposites = false),
        )

        assertIs<SyntheticTestData>(entity.field(SyntheticParent.Companion.Field.CHILD))
    }

    /** `projection == null` (every non-`select` read) loads the full graph. */
    @Test
    fun nullProjection_loadsCompositeField() {
        val (entries, parentDataId, childHash) = serializeParent()
        val fetched = mutableSetOf<Long>()

        val entity = deSerializeParent(entries, parentDataId, projection = null, fetched = fetched)

        assertIs<SyntheticTestData>(entity.field(SyntheticParent.Companion.Field.CHILD))
        assertTrue(childHash in fetched)
    }

    // --- Nested (tree) projection — core-nested-projection.md Phase 1 ----------

    private data class NestedFixture(
        val entries: MutableMap<Long, ObjectStore.DbEntry>,
        val grandparentDataId: DataId,
        val parentHash: Long,
        val childHash: Long,
    )

    /** Grandparent → parent ([SyntheticParent]) → child ([SyntheticTestData]). */
    private fun serializeGrandparent(): NestedFixture {
        val child = SyntheticTestData.Factory.create(testProxy.tContext, "child", 7L)
        val parent = SyntheticParent.Factory.create(testProxy.tContext, "parent", child)
        val grandparent = Entity.create<SyntheticGrandparent>(
            context = testProxy.tContext,
            composite = SyntheticGrandparent.Factory.create(testProxy.tContext, "gp", parent),
            properties = emptyMap()
        )
        val entries = mutableMapOf<Long, ObjectStore.DbEntry>()
        dbEntryMapper.serialize(grandparent) { entries[it.dataIdHash] = it }
        fun hashOf(type: String, key: String) =
            DataId.StringDataId.create(testProxy.tContext, type, key).getDataIdHash()
        return NestedFixture(
            entries = entries,
            grandparentDataId = testProxy.executionContext.getDataId(grandparent)!!,
            parentHash = hashOf(SyntheticParent.ID, "parent"),
            childHash = hashOf(SyntheticTestData.ID, "child"),
        )
    }

    private fun NestedFixture.deserialize(projection: Projection, fetched: MutableSet<Long>) {
        dbEntryMapper.deSerialize(
            dbEntry = entries[grandparentDataId.getDataIdHash()]!!,
            asEntity = true,
            dataId = grandparentDataId,
            projection = projection,
        ) { hash -> fetched += hash; entries[hash] ?: error("missing $hash") }
    }

    /**
     * A **nested** projection keeps the parent but prunes everything under it — so the
     * grandchild's entry is never fetched. Proves the dbEntryMapper descends with the field's
     * child projection instead of resolving in depth (`core-nested-projection.md`).
     */
    @Test
    fun nestedProjection_prunesGrandchild() {
        val f = serializeGrandparent()
        val fetched = mutableSetOf<Long>()

        f.deserialize(
            Projection(
                keepFields = setOf(SyntheticGrandparent.Companion.Field.CHILD),
                skipUnprojectedComposites = true,
                children = mapOf(
                    // descend into the parent, but prune its composite child
                    SyntheticGrandparent.Companion.Field.CHILD to Projection(
                        keepFields = emptySet(),
                        skipUnprojectedComposites = true,
                    ),
                ),
            ),
            fetched,
        )

        assertTrue(f.parentHash in fetched, "the kept parent is fetched")
        assertFalse(
            f.childHash in fetched,
            "the grandchild, pruned by the nested projection, must NOT be fetched",
        )
    }

    /**
     * Back-compat: the same top-level projection with **no** [Projection.children] entry for
     * the kept field loads that subtree fully — the grandchild IS fetched. Nesting is opt-in.
     */
    @Test
    fun keptField_withoutChildProjection_resolvesInDepth() {
        val f = serializeGrandparent()
        val fetched = mutableSetOf<Long>()

        f.deserialize(
            Projection(
                keepFields = setOf(SyntheticGrandparent.Companion.Field.CHILD),
                skipUnprojectedComposites = true,
                // no children → the kept parent loads fully (today's behaviour)
            ),
            fetched,
        )

        assertTrue(f.parentHash in fetched)
        assertTrue(f.childHash in fetched, "without a child projection the subtree loads in depth")
    }
}
