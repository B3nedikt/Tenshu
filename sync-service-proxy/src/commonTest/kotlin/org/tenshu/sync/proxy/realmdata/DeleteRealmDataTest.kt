package org.tenshu.sync.proxy.realmdata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.tenshu.core.WithTaskElement
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.impl.relation.create
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.fulfilValueRequirement
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.module.valueProperty
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.number.TInteger
import org.tenshu.core.realmdata.DeleteRealmData
import org.tenshu.core.realmdata.RealmDataService
import org.tenshu.core.realmdata.deleteRealmData
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.ServiceDataRelation
import org.tenshu.core.relation.valueProperty
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.reference
import org.tenshu.core.schema.service
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.schema.typeOf
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.toTString
import org.tenshu.core.trait.RealmData
import org.tenshu.core.trait.Syncable
import org.tenshu.sync.common.TombstoneEmitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.core.common.module.reference as registerReference
import org.tenshu.core.common.module.service as registerService
import org.tenshu.core.common.module.serviceDataRelation as registerServiceDataRelation
import org.tenshu.core.schema.serviceDataRelation as serviceDataRelationSchema

/*
 * Fixture shape (mirrors what a real realm-data service looks like):
 *
 *  - NOTE + TAG both declare the RealmData marker and bind a `createdAt` value
 *    property to RealmData.CREATED_AT — the one structural field the trait
 *    guarantees, and the field `createdBefore` filters on.
 *  - PLAIN declares NO marker: the reject case.
 *  - DATED declares the marker but binds CREATED_AT to a handle that merely ALIASES a
 *    schema FIELD (`Workout.Start`'s shape, as opposed to `File.CreatedAt`'s genuine
 *    stored value property) — see `fieldAliasedCreatedAt…` below.
 *  - One service holds all four in service-data relation arrays. That array IS
 *    the only route from "a type" to "its rows" — the store has no type-wide
 *    enumeration — so the fixture has to have one for the op to have anything to
 *    walk (⚠ File, today's only production RealmData type, does NOT: its rows
 *    live in the parent-folder tree, which is why it keeps its own delete op).
 */

private const val DATA_SERVICE_ID = "test/realmdata/service"

private const val NOTE_ID = "test/realmdata/note"
private const val TAG_ID = "test/realmdata/tag"
private const val PLAIN_ID = "test/realmdata/plain"

private const val NOTE_NAME = "$NOTE_ID/name"
private const val TAG_NAME = "$TAG_ID/name"
private const val PLAIN_NAME = "$PLAIN_ID/name"

private const val DATED_ID = "test/realmdata/dated"
private const val DATED_NAME = "$DATED_ID/name"

/** ⚠ A schema FIELD id, reused verbatim as the CREATED_AT handle's id — `Workout.Start`'s shape. */
private const val DATED_AT = "$DATED_ID/at"

private const val NOTES_REL = "$DATA_SERVICE_ID/notes"
private const val TAGS_REL = "$DATA_SERVICE_ID/tags"
private const val PLAINS_REL = "$DATA_SERVICE_ID/plains"
private const val DATEDS_REL = "$DATA_SERVICE_ID/dateds"

private val NOTE_DATA_ID = stringDataId("$NOTE_ID/dataIdProvider", NOTE_NAME)
private val TAG_DATA_ID = stringDataId("$TAG_ID/dataIdProvider", TAG_NAME)
private val PLAIN_DATA_ID = stringDataId("$PLAIN_ID/dataIdProvider", PLAIN_NAME)
private val DATED_DATA_ID = stringDataId("$DATED_ID/dataIdProvider", DATED_NAME)

private val NOTE_SCHEMA: CompositeSchema = composite(
    id = NOTE_ID,
    traits = listOf(Syncable(), NOTE_DATA_ID, RealmData.Schema),
    fields = { string(NOTE_NAME) },
)

private val TAG_SCHEMA: CompositeSchema = composite(
    id = TAG_ID,
    traits = listOf(Syncable(), TAG_DATA_ID, RealmData.Schema),
    fields = { string(TAG_NAME) },
)

private val PLAIN_SCHEMA: CompositeSchema = composite(
    id = PLAIN_ID,
    traits = listOf(Syncable(), PLAIN_DATA_ID),
    fields = { string(PLAIN_NAME) },
)

private val DATED_SCHEMA: CompositeSchema = composite(
    id = DATED_ID,
    traits = listOf(Syncable(), DATED_DATA_ID, RealmData.Schema),
    fields = {
        string(DATED_NAME)
        timestamp(DATED_AT)
    },
)

/**
 * ⚠⚠ The trap: a CREATED_AT handle whose id IS a schema field id. It reads as an ordinary
 * `Property.Value`, and it even sorts (the wire reference-weight system resolves a field
 * reference), but it is NOT in an entity's property map — see the test below.
 */
private val DATED_CREATED_AT: Property.Value<UnixInstant> =
    valueProperty(id = DATED_AT, schema = UnixInstant.Schema)

private val NOTE_CREATED_AT: Property.Value<UnixInstant> =
    valueProperty(id = "$NOTE_ID/createdAt", schema = UnixInstant.Schema)

private val TAG_CREATED_AT: Property.Value<UnixInstant> =
    valueProperty(id = "$TAG_ID/createdAt", schema = UnixInstant.Schema)

private class NoteDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = NOTE_SCHEMA
}

private class TagDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = TAG_SCHEMA
}

private class PlainDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = PLAIN_SCHEMA
}

private class DatedDto(fields: List<Data?>) : CompositeDto(fields) {
    override val schema: CompositeSchema get() = DATED_SCHEMA
}

private val realmDataTestModule = tModule {
    composite(interfaceClass = NoteDto::class, dataSchema = NOTE_SCHEMA, dtoFactory = ::NoteDto)
    composite(interfaceClass = TagDto::class, dataSchema = TAG_SCHEMA, dtoFactory = ::TagDto)
    composite(interfaceClass = PlainDto::class, dataSchema = PLAIN_SCHEMA, dtoFactory = ::PlainDto)
    composite(interfaceClass = DatedDto::class, dataSchema = DATED_SCHEMA, dtoFactory = ::DatedDto)

    registerReference(reference(NOTE_NAME))
    registerReference(reference(TAG_NAME))
    registerReference(reference(PLAIN_NAME))
    registerReference(reference(DATED_NAME))
    registerReference(reference(DATED_AT))

    trait(NOTE_SCHEMA, NOTE_DATA_ID) { context, schema ->
        StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
    }
    trait(TAG_SCHEMA, TAG_DATA_ID) { context, schema ->
        StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
    }
    trait(PLAIN_SCHEMA, PLAIN_DATA_ID) { context, schema ->
        StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
    }
    trait(DATED_SCHEMA, DATED_DATA_ID) { context, schema ->
        StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
    }

    valueProperty(fromId = NOTE_ID, property = NOTE_CREATED_AT) { UnixInstantDto(it as Long) }
    valueProperty(fromId = TAG_ID, property = TAG_CREATED_AT) { UnixInstantDto(it as Long) }
    fulfilValueRequirement(NOTE_ID, RealmData.CREATED_AT, NOTE_CREATED_AT)
    fulfilValueRequirement(TAG_ID, RealmData.CREATED_AT, TAG_CREATED_AT)
    // ⚠ No `valueProperty(...)` registration — the handle aliases the DATED_AT *field*.
    fulfilValueRequirement(DATED_ID, RealmData.CREATED_AT, DATED_CREATED_AT)

    registerService(
        service(
            id = DATA_SERVICE_ID,
            relations = listOf(
                serviceDataRelationSchema(id = NOTES_REL, type = TList.typeOf(NOTE_SCHEMA)),
                serviceDataRelationSchema(id = TAGS_REL, type = TList.typeOf(TAG_SCHEMA)),
                serviceDataRelationSchema(id = PLAINS_REL, type = TList.typeOf(PLAIN_SCHEMA)),
                serviceDataRelationSchema(id = DATEDS_REL, type = TList.typeOf(DATED_SCHEMA)),
            ),
        )
    )
    registerServiceDataRelation(
        fromId = DATA_SERVICE_ID,
        targetId = NOTES_REL,
        relation = ServiceDataRelation.create(
            from = DATA_SERVICE_ID,
            to = NOTES_REL,
            type = TList.typeOf(NOTE_SCHEMA),
        ),
    )
    registerServiceDataRelation(
        fromId = DATA_SERVICE_ID,
        targetId = TAGS_REL,
        relation = ServiceDataRelation.create(
            from = DATA_SERVICE_ID,
            to = TAGS_REL,
            type = TList.typeOf(TAG_SCHEMA),
        ),
    )
    registerServiceDataRelation(
        fromId = DATA_SERVICE_ID,
        targetId = PLAINS_REL,
        relation = ServiceDataRelation.create(
            from = DATA_SERVICE_ID,
            to = PLAINS_REL,
            type = TList.typeOf(PLAIN_SCHEMA),
        ),
    )
    registerServiceDataRelation(
        fromId = DATA_SERVICE_ID,
        targetId = DATEDS_REL,
        relation = ServiceDataRelation.create(
            from = DATA_SERVICE_ID,
            to = DATEDS_REL,
            type = TList.typeOf(DATED_SCHEMA),
        ),
    )
}

/** Records what [DeleteRealmDataImplementation] tombstoned, in order. */
private class RecordingTombstoneEmitter(
    /**
     * Runs at the top of every batch, before it is recorded — the probe point for
     * the ordering and cancellation tests (a real emitter's batch is durable at
     * exactly this moment).
     */
    private val beforeBatch: (List<TombstoneEmitter.Target>) -> Unit = {},
    /**
     * ⚠⚠ `null` = *"these could not be named for the wire — do not delete"*, the answer a locked
     * realm gives. Distinct from `emptyList()` ("no realm at all, deleting locally is correct"),
     * and the whole reason the caller must tell them apart.
     */
    private val cannotName: Boolean = false,
) : TombstoneEmitter {
        /** ⚠ A fake with no realm: it names locally and therefore does NOT cover the wire (EI-1). */
        override fun emit(
            targetEntityId: org.tenshu.core.entity.DataId,
            targetEntityType: String,
            revokedDeviceId: String?,
        ): String? = emit(
            targetEntityIdHash = targetEntityId.getDataIdHash().toString(),
            targetEntityType = targetEntityType,
            revokedDeviceId = revokedDeviceId,
        )

    val emitted = mutableListOf<Pair<String, String>>()

    /** Sizes of the batches [emitAll] was handed, in order. */
    val batchSizes = mutableListOf<Int>()

    override fun emit(
        targetEntityIdHash: String,
        targetEntityType: String,
        revokedDeviceId: String?
    ): String {
        emitted += targetEntityIdHash to targetEntityType
        return "block-${++emitCount}"
    }

    private var emitCount = 0

    override fun emitAll(targets: List<TombstoneEmitter.Target>): List<String>? {
        beforeBatch(targets)
        if (cannotName) return null
        batchSizes += targets.size
        targets.forEach { emit(it.entityId, it.entityType, it.revokedDeviceId) }
        return targets.map { "block-${++emitCount}" }
    }
}

/**
 * R1 of `docs/proposals/control-data-reset.md` — `DeleteRealmData` end-to-end
 * through the SDK chain (`RealmDataService.deleteRealmData(...)`), over the real
 * `SchemaRegistry` type→relation walk.
 *
 * The load-bearing assertion is the tombstone count: §3 records that a bulk delete
 * written as a bare `deleteData` loop deletes locally and **converges nowhere**
 * (`SyncObserver.onDataDeleted` is deliberately a no-op), so "one tombstone per
 * top-level entity, none for cascaded children" is the contract, not an incidental.
 */
class DeleteRealmDataTest {

    private class Fixture(
        val proxy: TestProxy,
        val service: RealmDataService,
        val emitter: RecordingTombstoneEmitter,
    ) {
        val context: TContext get() = proxy.tContext

        fun arrayRef(relationId: String, elementTypeId: String) = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = context,
                type = TList.ID,
                relationId = relationId,
            ),
            isEntity = false,
            elementSchemaId = elementTypeId,
        )

        fun rows(relationId: String, elementTypeId: String): List<Data> =
            proxy.executionContext.database
                .getArrayData(arrayRef(relationId, elementTypeId))
                ?.toList()
                ?: emptyList()

        fun dataIdOf(typeId: String, key: String): DataId.StringDataId =
            DataId.StringDataId.create(context = context, type = typeId, keyValue = key)

        fun stored(typeId: String, key: String): Data? =
            proxy.executionContext.database.getData(dataIdOf(typeId, key))

        /**
         * Writes one row: the entity (carrying the createdAt property) into the
         * store, and the composite into the service-data relation array — exactly
         * what a relation maintainer does on save.
         */
        fun seed(
            typeId: String,
            relationId: String,
            key: String,
            createdAt: Long? = null,
            createdAtProperty: Property.Value<UnixInstant>? = null,
        ) {
            val composite = context.create<Composite>(
                type = typeId,
                fields = listOf(key.toTString(context)),
            )
            val entity = Entity.create<Composite>(context, composite) {
                if (createdAt != null && createdAtProperty != null) {
                    set(createdAtProperty, createdAt)
                }
            }
            proxy.executionContext.saveData(entity)
            // ⚠ The ENTITY, not the bare composite: `addToArray` persists the member
            // row too, so adding the composite would overwrite the just-saved row and
            // silently drop its value properties (createdAt among them). Production
            // maintainers add whatever `onDataSaved` handed them — which for an entity
            // save is the entity.
            proxy.executionContext.database.addToArray(arrayRef(relationId, typeId), entity)
        }

        fun seedNote(key: String, createdAt: Long) =
            seed(NOTE_ID, NOTES_REL, key, createdAt, NOTE_CREATED_AT)

        fun seedTag(key: String, createdAt: Long) =
            seed(TAG_ID, TAGS_REL, key, createdAt, TAG_CREATED_AT)

        fun seedPlain(key: String) = seed(PLAIN_ID, PLAINS_REL, key)

        /**
         * A DATED row whose timestamp is written as the schema **field** it is — no value
         * property is set, because the CREATED_AT handle bound for this type *is* that field.
         */
        fun seedDated(key: String, at: Long) {
            val composite = context.create<Composite>(
                type = DATED_ID,
                fields = listOf(key.toTString(context), UnixInstantDto(at)),
            )
            val entity = Entity.create<Composite>(context, composite) {}
            proxy.executionContext.saveData(entity)
            proxy.executionContext.database.addToArray(arrayRef(DATEDS_REL, DATED_ID), entity)
        }
    }

    private fun fixture(
        scope: CoroutineScope,
        emitter: RecordingTombstoneEmitter = RecordingTombstoneEmitter(),
    ): Fixture {
        val proxy = TestProxy(coroutineScope = scope)
        proxy.schemaRegistry.registerConfig(realmDataTestModule)

        proxy.additionalImplementations[DeleteRealmData.ID] =
            DeleteRealmDataImplementation(emitter)

        val executor = LocalTaskExecutor(proxy.executionContext)
        val environment = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = proxy.tContext,
        )
        return Fixture(proxy, environment.getService(RealmDataService.ID), emitter)
    }

    // ---- the reverse walk ---------------------------------------------------

    @Test
    fun schemaRegistry_findsTheServiceDataArraysHoldingAType() = runTest {
        val f = fixture(backgroundScope)

        val bindings = f.proxy.schemaRegistry.serviceDataArraysHolding(NOTE_ID)

        assertEquals(1, bindings.size, "exactly one array holds notes")
        assertEquals(NOTE_ID, bindings.single().elementTypeId)
        // A type with no service-data relation is a truthful empty, not a failure.
        assertTrue(f.proxy.schemaRegistry.serviceDataArraysHolding("test/realmdata/unheard-of").isEmpty())
    }

    // ---- delete all ---------------------------------------------------------

    @Test
    fun deletesEveryRowOfTheNamedType() = runTest {
        val f = fixture(backgroundScope)
        f.seedNote("a", createdAt = 1_000)
        f.seedNote("b", createdAt = 2_000)
        f.seedNote("c", createdAt = 3_000)

        val result = f.service.deleteRealmData(NOTE_ID)

        assertTrue(result is TResult.Success<*>, "deleteRealmData failed: $result")
        assertEquals(3L, (result.value as TInteger).get(), "returns the deleted-row count")

        assertNull(f.stored(NOTE_ID, "a"), "the row must be gone from the store")
        assertNull(f.stored(NOTE_ID, "b"))
        assertNull(f.stored(NOTE_ID, "c"))
        // ⚠ A row deleted while still listed in its relation array leaves a
        // dangling member — the array must be emptied too.
        assertEquals(emptyList(), f.rows(NOTES_REL, NOTE_ID), "the relation array must be emptied")
    }

    @Test
    fun emitsExactlyOneTombstonePerTopLevelEntity() = runTest {
        val f = fixture(backgroundScope)
        f.seedNote("a", createdAt = 1_000)
        f.seedNote("b", createdAt = 2_000)

        f.service.deleteRealmData(NOTE_ID)

        assertEquals(2, f.emitter.emitted.size, "one tombstone per deleted row, no more")
        assertTrue(
            f.emitter.emitted.all { it.second == NOTE_ID },
            "every tombstone names the deleted entity's type: ${f.emitter.emitted}",
        )
        assertEquals(
            setOf(
                f.dataIdOf(NOTE_ID, "a").getDataIdHash().toString(),
                f.dataIdOf(NOTE_ID, "b").getDataIdHash().toString(),
            ),
            f.emitter.emitted.map { it.first }.toSet(),
            "tombstones address the rows' own DataId hashes",
        )
    }

    // ---- batching, ordering, cancellation (§11) ------------------------------

    @Test
    fun tombstonesAreQueuedInBatchesNotOnePerRow() = runTest {
        // Batching is the whole fix for the reset ANR: per-row emission made the
        // pending-tombstone array's read-modify-write quadratic.
        val f = fixture(backgroundScope)
        repeat(600) { f.seedNote("n$it", createdAt = it.toLong()) }

        f.service.deleteRealmData(NOTE_ID)

        assertEquals(listOf(250, 250, 100), f.emitter.batchSizes, "one emit per chunk, not per row")
        assertEquals(600, f.emitter.emitted.size, "every row is still tombstoned exactly once")
    }

    @Test
    fun aChunksTombstonesAreQueuedBeforeItsRowsAreRemoved() = runTest {
        // ⚠ The load-bearing ordering (§11): a row deleted before its tombstone is
        // durable can never be tombstoned again — its id is gone from this device,
        // so a kill in that window would leave the row on every peer forever.
        var probedRows = 0
        var rowsAlreadyGone = 0
        lateinit var f: Fixture
        val emitter = RecordingTombstoneEmitter(
            beforeBatch = { targets ->
                probedRows += targets.size
                // Every row of this batch must still be readable at emit time.
                val liveHashes = f.rows(NOTES_REL, NOTE_ID)
                    .mapNotNull { f.proxy.executionContext.getDataId(it)?.getDataIdHash()?.toString() }
                    .toSet()
                rowsAlreadyGone += targets.count { it.entityId.getDataIdHash().toString() !in liveHashes }
            },
        )
        f = fixture(backgroundScope, emitter)
        repeat(300) { f.seedNote("n$it", createdAt = it.toLong()) }

        f.service.deleteRealmData(NOTE_ID)

        assertEquals(300, probedRows, "the probe must have seen every row")
        assertEquals(0, rowsAlreadyGone, "no row may be removed before its tombstone is queued")
    }

    /**
     * ⚠⚠ The consumer half of the `null` contract — the guard that actually prevents the loss.
     * `emitAll` answering `null` means *"these rows could not be named for the wire"* (a locked
     * realm, mid-call). Deleting the chunk anyway destroys the only ids that could ever tombstone
     * them, so peers keep the rows for ever and no retry can name them again. The producer side is
     * pinned in `TombstoneEmitWithoutRealmTest`; without this, deleting the `emitted == null` guard
     * leaves the suite green.
     */
    @Test
    fun aBatchThatCouldNotBeNamedLeavesItsRowsAlone() = runTest {
        val f = fixture(backgroundScope, RecordingTombstoneEmitter(cannotName = true))
        repeat(40) { f.seedNote("n$it", createdAt = it.toLong()) }
        assertEquals(40, f.rows(NOTES_REL, NOTE_ID).size, "precondition: the rows are there")

        f.service.deleteRealmData(NOTE_ID)

        assertEquals(
            40,
            f.rows(NOTES_REL, NOTE_ID).size,
            "a chunk whose tombstones could not be named must survive — deleting it strands those " +
                "rows on every peer with no id left to name them",
        )
    }

    @Test
    fun cancellingMidResetLeavesThePartialDeleteTombstonedAndConsistent() = runTest {
        // Cancellation lands between chunks, so what is written stays coherent:
        // the rows that are gone are exactly the rows that were tombstoned.
        var job: Job? = null
        val emitter = RecordingTombstoneEmitter(
            beforeBatch = { if (it.isNotEmpty()) job?.cancel() },
        )
        val f = fixture(backgroundScope, emitter)
        repeat(600) { f.seedNote("n$it", createdAt = it.toLong()) }

        // Through the L2 cancellable envelope, as `withTask` does in production —
        // that is what binds the op's evaluation scope to the caller's coroutine
        // and so makes the caller's cancel reach it.
        job = launch { withContext(WithTaskElement("task-test")) { f.service.deleteRealmData(NOTE_ID) } }
        job.join()

        assertEquals(listOf(250), emitter.batchSizes, "cancel takes effect at the next chunk boundary")
        assertEquals(350, f.rows(NOTES_REL, NOTE_ID).size, "the untouched rows are still listed")
        // ⚠ The chunk is atomic (`NonCancellable`): a row dropped from the array must have
        // had its store row deleted too. Without that, a cancel between the array rewrite
        // and the deletes would strand rows — unreachable from any array, already
        // tombstoned, still occupying the store.
        assertEquals(
            350,
            (0 until 600).count { f.stored(NOTE_ID, "n$it") != null },
            "no row may be dropped from the array while its store row survives",
        )
        val tombstoned = emitter.emitted.map { it.first }.toSet()
        val remaining = f.rows(NOTES_REL, NOTE_ID)
            .mapNotNull { f.proxy.executionContext.getDataId(it)?.getDataIdHash()?.toString() }
            .toSet()
        assertTrue(
            (tombstoned intersect remaining).isEmpty(),
            "no row may be both tombstoned and still present",
        )
    }

    @Test
    fun cascadedChildRemovalDoesNotProduceExtraTombstones() = runTest {
        val f = fixture(backgroundScope)
        f.seedNote("a", createdAt = 1_000)
        // A stand-in cascade maintainer: on each note delete it drops a companion
        // row. Real maintainers (e.g. file history cascade) do the same — and the
        // point is that only the OPERATION emits, never the delete itself
        // (SyncObserver.onDataDeleted is a no-op by design, §3).
        f.seedPlain("a-child")
        f.proxy.executionContext.addDataObserver(
            object : DataObserver {
                override fun onDataSaved(data: Data, origin: WriteOrigin) = Unit

                override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) {
                    if ((dataId as? DataId.StringDataId)?.type?.get() != NOTE_ID) return
                    // Raw store delete, no observer fanout — the child is removed
                    // silently, exactly as a cascade should be.
                    f.proxy.executionContext.database
                        .deleteData(f.dataIdOf(PLAIN_ID, "a-child"))
                }
            }
        )

        f.service.deleteRealmData(NOTE_ID)

        assertNull(f.stored(PLAIN_ID, "a-child"), "the cascade did run")
        assertEquals(
            1,
            f.emitter.emitted.size,
            "the cascaded child must NOT get its own tombstone: ${f.emitter.emitted}",
        )
    }

    // ---- createdBefore ------------------------------------------------------

    @Test
    fun createdBeforeDeletesOnlyOlderRows() = runTest {
        val f = fixture(backgroundScope)
        f.seedNote("old-1", createdAt = 1_000)
        f.seedNote("old-2", createdAt = 1_500)
        f.seedNote("boundary", createdAt = 2_000)
        f.seedNote("new", createdAt = 5_000)

        val result = f.service.deleteRealmData(NOTE_ID, createdBefore = 2_000)

        assertTrue(result is TResult.Success<*>, "deleteRealmData failed: $result")
        assertEquals(2L, (result.value as TInteger).get())

        assertNull(f.stored(NOTE_ID, "old-1"))
        assertNull(f.stored(NOTE_ID, "old-2"))
        // Exclusive bound: a row created exactly AT the cut-off survives.
        assertNotNull(f.stored(NOTE_ID, "boundary"), "createdBefore is an exclusive upper bound")
        assertNotNull(f.stored(NOTE_ID, "new"))
        assertEquals(
            setOf("boundary", "new"),
            f.rows(NOTES_REL, NOTE_ID)
                .map { (it as Composite).getField<TString>(NOTE_NAME).get() }
                .toSet(),
        )
        assertEquals(2, f.emitter.emitted.size, "only the deleted rows are tombstoned")
    }

    @Test
    fun rowWithoutACreatedAtIsKeptByANarrowedDelete() = runTest {
        val f = fixture(backgroundScope)
        // No createdAt property written at all.
        f.seed(NOTE_ID, NOTES_REL, "undated")

        val result = f.service.deleteRealmData(NOTE_ID, createdBefore = 9_000)

        assertTrue(result is TResult.Success<*>, "deleteRealmData failed: $result")
        assertEquals(0L, (result.value as TInteger).get())
        // ⚠ Conservative by design: a bulk delete has no undo, so an unreadable
        // timestamp must not be treated as "old enough".
        assertNotNull(f.stored(NOTE_ID, "undated"))
    }

    /**
     * ⚠⚠ **A CREATED_AT handle that merely aliases a schema FIELD narrows nothing.**
     *
     * [isBeforeCutoff][DeleteRealmDataImplementation] reads the cut-off with
     * `Entity.getProperty(handle.id)`, and an entity's property map holds only *registered
     * value properties* — never its fields (`DbEntryMapper` deserialises fields and relations
     * into separate maps, and `ReferenceResolver.resolveStaticProperty` reads the same
     * property map). A field-aliased handle therefore reads back `null`, the row falls on the
     * conservative "keep" side, and a narrowed reset deletes **nothing**.
     *
     * It is not detectable at the guard: `resolveValueRequirement` returns the handle
     * happily, so the op's "binds no CREATED_AT" rejection does not fire either.
     *
     * This is exactly `Workout.Start`'s shape (`healthServiceCommonModule`) — the handle
     * exists for `sortBy`, which resolves through the *wire reference* layer where a field
     * reference wins (`WorkoutSortWireTest`), not through the property map. `File.CreatedAt`
     * is the other, working shape: a genuine op-written value property.
     *
     * Whole-type resets — the only thing Control's Data section offers — are unaffected.
     * Pinned here so the next person to bind CREATED_AT knows which shape to pick;
     * `docs/proposals/control-data-reset.md` §10 Q1 carries the follow-up.
     */
    @Test
    fun fieldAliasedCreatedAtBinding_narrowsNothing_soARangedDeleteKeepsEveryRow() = runTest {
        val f = fixture(backgroundScope)
        f.seedDated("ancient", at = 1_000)
        f.seedDated("old", at = 2_000)

        val narrowed = f.service.deleteRealmData(DATED_ID, createdBefore = 9_000)

        assertTrue(narrowed is TResult.Success<*>, "not an error — it simply matches nothing")
        assertEquals(
            0L,
            (narrowed.value as TInteger).get(),
            "a field-aliased CREATED_AT is unreadable via getProperty ⇒ nothing is in range",
        )
        assertNotNull(f.stored(DATED_ID, "ancient"))

        // …while the un-narrowed reset over the very same type works, because it never
        // consults CREATED_AT at all. This is what the Data section ships.
        val all = f.service.deleteRealmData(DATED_ID)
        assertEquals(2L, ((all as TResult.Success<*>).value as TInteger).get())
        assertNull(f.stored(DATED_ID, "ancient"))
        assertNull(f.stored(DATED_ID, "old"))
    }

    // ---- guards -------------------------------------------------------------

    @Test
    fun rejectsATypeThatIsNotRealmData() = runTest {
        val f = fixture(backgroundScope)
        f.seedPlain("p")

        val result = f.service.deleteRealmData(PLAIN_ID)

        assertTrue(
            result is TResult.Failure<*>,
            "a non-RealmData type must be rejected, not silently no-op: $result",
        )
        assertNotNull(f.stored(PLAIN_ID, "p"), "nothing may be deleted on rejection")
        assertTrue(f.emitter.emitted.isEmpty(), "and nothing may be tombstoned")
    }

    @Test
    fun leavesOtherRealmDataTypesUntouched() = runTest {
        val f = fixture(backgroundScope)
        f.seedNote("n", createdAt = 1_000)
        f.seedTag("t", createdAt = 1_000)
        f.seedPlain("p")

        f.service.deleteRealmData(NOTE_ID)

        assertNull(f.stored(NOTE_ID, "n"))
        assertNotNull(f.stored(TAG_ID, "t"), "a sibling realm-data type must survive")
        assertNotNull(f.stored(PLAIN_ID, "p"))
        assertEquals(1, f.rows(TAGS_REL, TAG_ID).size, "the sibling's array is untouched")
        assertEquals(1, f.rows(PLAINS_REL, PLAIN_ID).size)
        assertEquals(1, f.emitter.emitted.size)
    }
}
