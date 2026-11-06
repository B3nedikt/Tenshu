package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.trait.TraitRequirement
import org.tenshu.core.trait.Syncable
import org.tenshu.sync.common.CompactionReceipt
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val KEYED_ID = "tenshu/test/compaction/keyed"
private const val KEYLESS_ID = "tenshu/test/compaction/keyless"

/** Keyed by its own `name` — the ordinary case: its `DataId` is re-derivable. */
private val KEYED_PROVIDER: StringDataIdProviderSchema =
    stringDataId(id = "$KEYED_ID/dataIdProvider", fieldRef = "$KEYED_ID/name")

private val KEYED_SCHEMA: CompositeSchema = composite(
    id = KEYED_ID,
    traits = listOf(KEYED_PROVIDER, Syncable(SyncStrategy.Eager)),
    fields = { string("$KEYED_ID/name") },
)

/** No `DataIdProvider` — the row the compactor must skip rather than guess at. */
private val KEYLESS_SCHEMA: CompositeSchema = composite(
    id = KEYLESS_ID,
    fields = { string("$KEYLESS_ID/name") },
)

private class KeyedDto(fields: List<Data?>) : Composite, CompositeDto(fields) {
    override val schema get() = KEYED_SCHEMA
}

private class KeylessDto(fields: List<Data?>) : Composite, CompositeDto(fields) {
    override val schema get() = KEYLESS_SCHEMA
}

/**
 * E4-3b (`core-schema-evolution.md` §8.3.4): current state is re-emitted as fresh
 * blocks through the **ordinary** write path.
 *
 * This is the slice that had to prove §8.3.4's correction — that a stored row's
 * `DataId` is re-derivable from its own fields — actually holds end to end,
 * rather than only in the reading of `getDataId`.
 */
class SchemaCompactorTest {

    private lateinit var store: InMemoryObjectStore

    private fun proxy(): TestProxy =
        TestProxy(
            createSyntheticTestRegistry(),
            objectStore = InMemoryObjectStore().also { store = it },
        ).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(
                tModule {
                    composite(
                        interfaceClass = Composite::class,
                        dataSchema = KEYED_SCHEMA,
                        dtoFactory = ::KeyedDto,
                    )
                    trait(
                        dataSchema = KEYED_SCHEMA,
                        traitSchema = KEYED_PROVIDER,
                        traitFactory = { context, schema ->
                            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
                        },
                    )
                    trait(
                        dataSchema = KEYED_SCHEMA,
                        traitSchema = Syncable.Schema,
                        traitFactory = { _, schema ->
                            val composite = schema as Composite
                            object : Syncable, Composite by composite {
                                override val schema get() = Syncable.Schema
                                override val traitRequirements = SimpleTList<TraitRequirement>(emptyList())
                            }
                        },
                    )
                    composite(
                        interfaceClass = Composite::class,
                        dataSchema = KEYLESS_SCHEMA,
                        dtoFactory = ::KeylessDto,
                    )
                }
            )
        }

    private fun TestProxy.compactor() = SchemaCompactor(
        executionContext = executionContext,
        store = store,
        mapper = mapperFor(this),
    )

    private fun mapperFor(p: TestProxy) = DbEntryMapper(
        p.tContext,
        p.schemaRegistry,
        p.schemaRegistry,
        p.executionContext.traitResolver,
    )

    private fun TestProxy.keyed(name: String): Data = tContext.create<Composite>(
        type = KEYED_ID,
        fields = listOf(name.toTString(tContext)),
    )

    private fun receipt(p: TestProxy): CompactionReceipt? =
        store.rowsOfTypes(setOf(CompactionReceipt.ID))
            .firstOrNull()
            ?.let { entry ->
                mapperFor(p).deSerialize(entry, getEntry = { h -> store.getObject(h)!! })
            } as? CompactionReceipt

    @Test
    fun currentStateIsReEmittedThroughTheOrdinaryWritePath() = runTest {
        val p = proxy()
        p.executionContext.saveData(p.keyed("a"))
        p.executionContext.saveData(p.keyed("b"))

        val outcome = p.compactor().compact(
            serviceId = "tenshu/test/compaction/service",
            typeIds = setOf(KEYED_ID),
            atVersion = 4,
            deviceId = "device-a",
        )

        assertEquals(
            SchemaCompactor.Outcome.Compacted(reEmitted = 2, skipped = 0),
            outcome,
            "both stored rows must be re-emitted — this is what proves the DataId is re-derivable",
        )
        // The rows are still there, unchanged in identity: re-emission republishes
        // state, it does not fork it into new keys.
        assertEquals(2, store.rowsOfTypes(setOf(KEYED_ID)).size)
    }

    @Test
    fun aRowWithNoDataIdProviderIsSkippedRatherThanGuessedAt() = runTest {
        val p = proxy()
        p.executionContext.saveData(p.keyed("a"))
        // A keyless row placed straight into the store — it has no derivable key,
        // so `saveData` could only ever mint a NEW one, which would duplicate
        // rather than re-emit.
        store.storeObject(
            ObjectStore.DbEntry(
                type = KEYLESS_ID,
                dataIdHash = 987654L,
                fields = listOf(ObjectStore.DbValue.StringValue("orphan")),
                properties = emptyMap(),
            )
        )

        val outcome = p.compactor().compact(
            serviceId = "tenshu/test/compaction/service",
            typeIds = setOf(KEYED_ID, KEYLESS_ID),
            atVersion = 4,
            deviceId = "device-a",
        )

        assertEquals(
            SchemaCompactor.Outcome.Compacted(reEmitted = 1, skipped = 1),
            outcome,
            "the keyless row must be skipped, not re-keyed under an invented id",
        )
    }

    @Test
    fun theReceiptRecordsTheVersionTheStateWasLiftedTo() = runTest {
        val p = proxy()
        p.executionContext.saveData(p.keyed("a"))

        p.compactor().compact(
            serviceId = "tenshu/test/compaction/service",
            typeIds = setOf(KEYED_ID),
            atVersion = 7,
            deviceId = "device-a",
        )

        val receipt = assertNotNull(
            receipt(p),
            "a completed pass must leave a receipt, or every peer redoes the work",
        )
        assertEquals(7, receipt.compactedToVersion.get().toInt())
        assertEquals("tenshu/test/compaction/service", receipt.serviceId.get())
    }

    @Test
    fun aStoreThatCannotEnumerateIsRefusedAndLeavesNoReceipt() = runTest {
        val p = proxy()
        val blind = object : ObjectStore by store {
            override fun rowsOfTypes(typeIds: Set<String>): List<ObjectStore.DbEntry>? = null
        }
        val compactor = SchemaCompactor(
            executionContext = p.executionContext,
            store = blind,
            mapper = mapperFor(p),
        )

        val outcome = compactor.compact(
            serviceId = "tenshu/test/compaction/service",
            typeIds = setOf(KEYED_ID),
            atVersion = 4,
            deviceId = "device-a",
        )

        assertEquals(SchemaCompactor.Outcome.Unsupported, outcome)
        // ⚠ The assertion that matters: no receipt. A receipt here would tell every
        // peer the work was done, and the service would never be compacted at all.
        assertTrue(
            store.rowsOfTypes(setOf(CompactionReceipt.ID)).isEmpty(),
            "an unsupported store must not leave a receipt claiming the work happened",
        )
    }
}
