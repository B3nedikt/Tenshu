package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.schemaMigration
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.schema.migration.schemaMigration as buildSchemaMigration
import org.tenshu.core.core.create
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.service as serviceSchema
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.toTString
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable
import org.tenshu.sync.common.CompactionReceipt
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SVC = "tenshu/test/maintenance/service"
private const val THING = "tenshu/test/maintenance/thing"

private val THING_PROVIDER: StringDataIdProviderSchema =
    stringDataId(id = "$THING/dataIdProvider", fieldRef = "$THING/name")

private val THING_V1: CompositeSchema = composite(
    id = THING,
    traits = listOf(THING_PROVIDER, Syncable(SyncStrategy.Eager)),
    fields = { string("$THING/name") },
)

private class MaintenanceThingDto(fields: List<Data?>) : Composite, CompositeDto(fields) {
    override val schema get() = THING_V1
}

/**
 * E4-5 (`core-schema-evolution.md` §8.3): the maintenance sweep that composes
 * policy + compactor.
 *
 * The two cases that matter are opposites, and the *first* is the one the repo
 * lives in today.
 */
class SchemaCompactionMaintenanceTest {

    private lateinit var store: InMemoryObjectStore

    private fun proxy(withChain: Boolean): TestProxy =
        TestProxy(
            createSyntheticTestRegistry(),
            objectStore = InMemoryObjectStore().also { store = it },
        ).also { p ->
            p.schemaRegistry.registerConfig(syncServiceCommonModule)
            p.schemaRegistry.registerConfig(
                tModule {
                    service(serviceSchema(id = SVC, version = if (withChain) 2 else 1))
                    composite(
                        interfaceClass = Composite::class,
                        dataSchema = THING_V1,
                        dtoFactory = ::MaintenanceThingDto,
                    )
                    trait(
                        dataSchema = THING_V1,
                        traitSchema = THING_PROVIDER,
                        traitFactory = { c, s ->
                            StringDataIdProviderImpl(c, s as StringDataIdProviderSchema)
                        },
                    )
                    if (withChain) {
                        // A real (shape-preserving) step: the version moved and the
                        // type is named, so there IS history to lift.
                        schemaMigration(
                            buildSchemaMigration(SVC, fromVersion = 1) {
                                migration(THING, from = THING_V1, to = THING_V1)
                            }
                        )
                    }
                }
            )
        }

    private fun TestProxy.thing(name: String): Data = tContext.create<Composite>(
        type = THING,
        fields = listOf(name.toTString(tContext)),
    )

    @Test
    fun withNoRegisteredChainTheSweepDoesNothingAtAll() = runTest {
        val p = proxy(withChain = false)
        p.executionContext.saveData(p.thing("a"))

        val result = SchemaCompactionMaintenance(p.executionContext, store).runOnce("device-a")

        // ⚠⚠ Scoped to SVC, and the reason is worth the line: this used to assert the *global*
        // list was empty, because "every floor equals its current version" held for every service
        // in the repo. `tenshu/sync/service` broke that when it gained a real v5→v6 chain, so the
        // global form would now fail here on every future version bump — for a fact that says
        // nothing about the mechanism under test.
        assertTrue(SVC !in result.compactedServices, "no chain for SVC ⇒ SVC is not compacted")
        assertEquals(
            result.compactedServices.size,
            store.rowsOfTypes(setOf(CompactionReceipt.ID)).size,
            "one receipt per compacted service, and none claiming work that did not happen",
        )
    }

    @Test
    fun aServiceWithHistoryBelowItsVersionIsCompactedAndReceipted() = runTest {
        val p = proxy(withChain = true)
        p.executionContext.saveData(p.thing("a"))
        p.executionContext.saveData(p.thing("b"))

        val result = SchemaCompactionMaintenance(p.executionContext, store).runOnce("device-a")

        assertTrue(SVC in result.compactedServices, "a chain below the version ⇒ SVC is compacted")
        assertEquals(
            result.compactedServices.size,
            store.rowsOfTypes(setOf(CompactionReceipt.ID)).size,
            "one receipt per service, so a peer can see the work was done",
        )
    }

    @Test
    fun aSecondSweepIsANoOpBecauseTheReceiptSaysSo() = runTest {
        val p = proxy(withChain = true)
        p.executionContext.saveData(p.thing("a"))
        val maintenance = SchemaCompactionMaintenance(p.executionContext, store)

        assertTrue(SVC in maintenance.runOnce("device-a").compactedServices)

        // ⚠ The dedup that makes this safe to schedule at all: without it a
        // recurring maintenance item re-emits the whole store on every wake.
        assertTrue(
            SVC !in maintenance.runOnce("device-a").compactedServices,
            "the receipt from the first pass must stop the second",
        )
    }
}
