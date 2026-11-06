package app.tenshu.sdk.internal

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.place.client.placeServiceClientModule
import org.tenshu.place.common.placeServiceCommonModule
import org.tenshu.place.proxy.PlaceMergeMerger
import org.tenshu.place.proxy.PlaceMerger
import org.tenshu.place.proxy.PlaceNetworkFingerprintMerger
import org.tenshu.place.proxy.PlaceRoleMerger
import org.tenshu.place.proxy.placeServiceProxyImplementationFactory

/**
 * `:place-service` on web (`web-app-suite.md` Wave 2, Timeline run A) — what makes a timeline
 * `Visit`'s place resolve, and the place writes (`renamePlace`, `setPlaceRole`, merge / unmerge)
 * dispatch. Until this run it was an F7 sync-only entry in `SyncedServicePlugins.kt`.
 *
 * ⚠⚠ **`initPlaceService` is NOT run, and must not be.** It does three things and a tab wants one:
 *  - `installRelationMaintainer()` — membership of `PlaceService.{places, merges, roles, …}`. The
 *    reads need it, and it starts nothing: an observer that runs inside each save. Called here.
 *  - registering the four mergers — done through the [mergers] seam instead, so they are in the
 *    registry before `SyncPlugin.initialize` hands it to `initSyncService` (the F7 posture, kept).
 *  - installing `PlaceReconciler` **and running `reconcileAll()`** — the automatic place-merge pass,
 *    which scans the whole store at start and writes `PlaceMerge` rows. That is background work a
 *    tab must not do (the same decision as `inferPlaceRoles = false`, maintainer 2026-10-23): the
 *    phones decide place identity, the browser syncs the result. `NoBackgroundWorkInATabTest`
 *    holds it out.
 *
 * The factory maps only the four `Record*` ops, each a plain `saveData` — no geocoding, no egress.
 */
internal object PlacePlugin : JsRuntimePlugin {
    override val id: String = "place"

    override val schemaConfigs = listOf(placeServiceCommonModule, placeServiceClientModule)

    // The four `initPlaceService` registers. ⚠ Without `PlaceMerger` a peer's blank capture erases a
    // user's rename (§9.1a); without `PlaceRoleMerger` a peer's inference overwrites the user's own
    // answer — which is the guarantee `setPlaceRole` on web leans on.
    override val mergers = listOf(PlaceMerger(), PlaceNetworkFingerprintMerger(), PlaceMergeMerger(), PlaceRoleMerger())

    override fun operationFactory(ctx: RuntimeContext) =
        OperationImplementationFactory { id -> placeServiceProxyImplementationFactory(id) }

    override fun initialize(executionContext: ExecutionContext, runtime: RuntimeContext) {
        // Idempotent (one generic maintainer per context): the relation reads, nothing else.
        executionContext.installRelationMaintainer()
    }
}
