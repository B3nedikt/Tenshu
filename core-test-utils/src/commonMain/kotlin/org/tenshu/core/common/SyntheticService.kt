package org.tenshu.core.common

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.SyntheticTestData.Companion.Field
import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.reference
import org.tenshu.core.common.module.service as registerService
import org.tenshu.core.common.module.serviceDataRelation as registerServiceDataRelation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.module.type
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.id
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.reference
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf
import org.tenshu.core.common.relation.installRelationMaintainer

/**
 * A minimal synthetic **service with one streamable relation** — the core-common test fixture
 * that lets a [org.tenshu.core.common.proxy.InProcessProxy]-based test drive the full client
 * reactive path (`client.syntheticService.items.select { … }.flow()`) with pure synthetic data,
 * no real feature service. It is the generic twin of `:health-service`'s `workouts` relation,
 * built from [SyntheticTestData].
 *
 * Register [syntheticServiceModule] alongside [createSyntheticTestRegistry], wire
 * [initSyntheticService] into the proxy's `initialization`, and write via
 * `executionContext.saveData(item)` — the [SyntheticServiceRelationMaintainer] folds each save
 * into the `items` relation exactly as a real service's maintainer does.
 */
interface SyntheticService : Service {

    override val schema: ServiceSchema get() = Schema

    /** Every saved [SyntheticTestData], as a reactive relation. */
    val items: TList<SyntheticTestData>

    companion object {
        const val ID = "test/synthetic/service"

        object Relations {
            const val ITEMS = "$ID/items"
        }

        // `order` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:core-test-utils` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.ITEMS,
                    type = TList.typeOf(SyntheticTestData.Schema),
                    maintained = true,
                ),
            ),
        )
    }
}

/** Client RO for [SyntheticService] — resolves `items` to the relation array (mirrors HealthServiceRo). */
internal class SyntheticServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : SyntheticService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent> get() = context.components

    override val items: TList<SyntheticTestData>
        get() = serviceRelation(SyntheticService.Companion.Relations.ITEMS, SyntheticTestData.Schema)
}

/**
 * The synthetic-service module — self-contained so it can be an `extraConfig` to
 * `createDefaultSchemaRegistry` (the [org.tenshu.core.common.proxy.InProcessProxy] path). Registers
 * both the [SyntheticTestData] composite (proxy dto + client RO + its DataId trait / field
 * references — mirrors [createSyntheticTestRegistry]) and the [SyntheticService] RO.
 */
val syntheticServiceModule = tModule {
    composite(
        interfaceClass = SyntheticTestData::class,
        dataSchema = SyntheticTestData.Schema,
        dtoFactory = ::SyntheticTestDataDto,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, SyntheticTestData.Schema), SyntheticTestData {
                override val schema get() = SyntheticTestData.Schema
            }
        },
    )
    reference(reference(Field.NAME))
    reference(reference(Field.VALUE))
    trait(
        dataSchema = SyntheticTestData.Schema,
        traitSchema = SyntheticTestData.DataIdTraitSchema,
        traitFactory = { context, schema -> StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema) },
    )

    // `registerService` walks `SyntheticService.Schema.relationSchemas` and registers
    // both the chain reference and the maintenance entry from the declaration itself
    // (`core-relation-system.md` §9.2.1) — no manual `registerServiceDataRelation`.
    registerService(SyntheticService.Schema)
    type(
        interfaceClass = SyntheticService::class,
        dataSchema = SyntheticService.Schema,
        roFactory = ::SyntheticServiceRo,
    )
}

/** The client-side accessor, mirroring `TenshuClient.healthService`. */
val TenshuClient.syntheticService: SyntheticService
    get() = getService(SyntheticService.ID)

/**
 * Installs the generic relation maintainer so saved [SyntheticTestData] lands in `items`
 * (mirrors `initHealthService`). `items` is declared on [SyntheticService.Schema] with an
 * `order`, so `SyntheticServiceRelationMaintainer` is gone — `core-relation-system.md` §9.2.1.
 */
fun ExecutionContext.initSyntheticService() {
    installRelationMaintainer()
}
