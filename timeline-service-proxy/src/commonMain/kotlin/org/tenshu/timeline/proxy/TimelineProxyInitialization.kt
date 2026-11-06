package org.tenshu.timeline.proxy

import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.TimelineService
import org.tenshu.core.timeline.Trip
import org.tenshu.sync.common.SemanticMergerRegistry

/**
 * Initialises the timeline service's proxy-side observers.
 *
 * [TimelineService.Relations.MOMENTS], `TRIPS` and `TRACES` are derived views of
 * every locally-known [Moment] / [Trip] / `MovementTrace`: relation membership
 * is not a synced primitive — `SyncBlock`s carry the entity, and each receiver
 * derives the relation locally.
 *
 * The three are **declared on [TimelineService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, phase R1).
 * They were the local `StringKeyedRelationMaintainer` — one parameterised
 * [DataObserver] serving all three — which is the class the generic design was
 * promoted from, and which R1 deletes.
 *
 * Idempotent via remove-then-add keyed on the entity's own `DataId`, so the
 * explicit `saveData` and the sync-merge path converge to one entry.
 *
 * ⚠⚠ **[mergerRegistry] is required** (`timeline-recognition-and-place-naming.md` §9.1c). Timeline's
 * moments, trips and places live in Control's durable, synced store, so [MomentMerger] and
 * [TripMerger] must be registered in production. Unregistered, `Moment` and `Trip` silently run on
 * last-writer-wins on every device — no `sources` union, no trip membership union — and every test
 * that builds its own registry still passes. A required parameter turns "forgot to register" from a
 * silent behaviour change into a compile error.
 */
fun ExecutionContext.initTimelineService(
    mergerRegistry: SemanticMergerRegistry,
    // ⚠ `false` only in the browser runtime (`:tenshu-sdk-js` TimelinePlugin, web-app-suite.md F7
    // follow-up, maintainer 2026-10-23). A tab syncs place roles but never COMPUTES them: inference
    // is background work, and a browser deployed from master ahead of the released phones would,
    // by writing inferred rows, raise the realm's place-schema epoch and fence those phones'
    // place pulls (`RealmSchemaEpochWriter`). Every native device keeps the default.
    inferPlaceRoles: Boolean = true,
) {
    installRelationMaintainer()

    // Idempotent: the registry is keyed by typeId, so registering twice is harmless.
    mergerRegistry.register(MomentMerger())
    mergerRegistry.register(TripMerger())

    // P4e-2 (§10.3): cross-source moment reconciliation graduates from a per-read recompute in each
    // app to a persisted decision here — bounded to the recent window, idempotent, and visible to
    // every client rather than only the ones that run the pass.
    if (!observerRegistry.any { it is MomentReconciliationPass }) {
        addDataObserver(MomentReconciliationPass(this))
    }

    // place-roles.md PR-2: Home / Work inferred from a bounded window of recent visits, written as
    // `roleSource = inferred` rows for the UI to offer for confirmation. Runs once here as well as on
    // the save path, so a device that already holds history reaches a verdict without waiting for the
    // next 20 visits — the same "repair rows written before this pass existed" reason
    // `initPlaceService` runs `reconcileAll()` eagerly.
    if (inferPlaceRoles && !observerRegistry.any { it is PlaceRoleInferencePass }) {
        val roles = PlaceRoleInferencePass(this)
        addDataObserver(roles)
        roles.inferNow()
    }
}
