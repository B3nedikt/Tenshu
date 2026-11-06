package app.tenshu.sdk.internal

import org.tenshu.core.common.module.ModuleConfiguration
import org.tenshu.inference.client.inferenceServiceClientModule
import org.tenshu.inference.common.inferenceServiceCommonModule
import org.tenshu.ingest.client.ingestServiceClientModule
import org.tenshu.ingest.common.ingestServiceCommonModule
import org.tenshu.logging.client.loggingServiceClientModule
import org.tenshu.logging.common.loggingServiceCommonModule
import org.tenshu.sync.common.SemanticMerger

/**
 * ⚠⚠ **F7 (`web-app-suite.md` §3): every pinned service the browser had no plugin for, registered
 * for SYNC ONLY — schema + mergers, nothing else.**
 *
 * The browser runtime is a sync peer, and sync drops a block whose type it has no schema for while
 * the lane cursor still moves past it (`SchemaPullFence`'s KDoc: "dropped as unparseable by the
 * ordinary ingest path"). With only the Drive-era services registered, every browser device was
 * discarding calendar, health, photo, place, routine, … rows at ingest, permanently. iOS settled the
 * same question as "the full graph, no exceptions" (`ios-per-app-frameworks.md` D4, §6.2); the web
 * follows it, and `DeclaredServiceVersionsTest` holds it to the same committed list
 * (`PinnedServiceVersions`).
 *
 * ⚠⚠ **No `operationFactory`, no `initialize` — on purpose.** A browser tab is a poor executor
 * (`web-app-suite.md` §2, the Home row): no routine evaluation, no inference indexer or model
 * download, no geocoding, no capture, no import jobs. What ingest needs is the schema (so the row
 * parses and the service's version is declared) and the mergers (so a concurrent edit merges the way
 * every other device merges it rather than falling back to last-writer-wins). The per-app operation
 * surfaces arrive with their apps, each in its own slice.
 *
 * ⚠ Consequence of no `initialize`: these services' maintained relations
 * (`PhotoService.albums`, …) are **not** maintained here. Nothing on web reads them yet; the slice that
 * adds a read surface owes the `init*Service` call, and must check it starts no background work.
 *
 * ⚠ Mergers ride the [JsRuntimePlugin.mergers] seam, so they are in the registry before
 * `SyncPlugin.initialize` hands it to `initSyncService` regardless of plugin order. They are the same
 * set the three native `PlatformProxyModule`s and `initPlaceService` register.
 *
 * ⚠ Safe for stores that already exist: `StoreSchemaMigrator.migrateStoreAtOpen` stamps a service
 * the recorded vector has never seen at its current version and touches no rows (the `null ->` arm);
 * only `recorded > declared` and `recorded < floor` wipe, and an unrecorded service is neither.
 * `DeclaredServiceVersionsTest` runs the pre-F7 vector through the real migrator to prove it.
 */
private class SyncedServicePlugin(
    override val id: String,
    override val schemaConfigs: List<ModuleConfiguration>,
    override val mergers: List<SemanticMerger> = emptyList(),
) : JsRuntimePlugin

/**
 * The F7 plugins, one per service. A stable list of instances: [JsRuntimeBuilder.register] is
 * idempotent on identity, so they must be built once.
 */
internal val SyncedServicePlugins: List<JsRuntimePlugin> = listOf(
    // `calendar`, `health`, `place`, `photo`, `mapRegion` and `mia` graduated to their own plugins
    // (CalendarPlugin.kt, HealthPlugin.kt, PlacePlugin.kt, PhotoPlugin.kt, MapRegionPlugin.kt,
    // MiaPlugin.kt), and `routine`, `homeDevice` and `hubManagement` to the read-only Home plugins
    // (HomePlugin.kt).
    SyncedServicePlugin(
        id = "inference",
        schemaConfigs = listOf(inferenceServiceCommonModule, inferenceServiceClientModule),
    ),
    // ⚠ Ingest's `-client` was already on this module's classpath, but nothing registered its
    // schema, so the browser never declared `tenshu/ingest/service` — found by the F7 guard.
    SyncedServicePlugin(
        id = "ingest",
        schemaConfigs = listOf(ingestServiceCommonModule, ingestServiceClientModule),
    ),
    SyncedServicePlugin(
        id = "logging",
        schemaConfigs = listOf(loggingServiceCommonModule, loggingServiceClientModule),
    ),
)
