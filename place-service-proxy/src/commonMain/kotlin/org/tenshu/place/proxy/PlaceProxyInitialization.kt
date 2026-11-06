package org.tenshu.place.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.relation.installRelationMaintainer
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceCorroboration
import org.tenshu.core.place.PlaceService
import org.tenshu.core.id
import kotlin.time.Clock
import org.tenshu.sync.common.SemanticMergerRegistry

/**
 * Initialises the place service's proxy-side observers and mergers.
 *
 * [PlaceService.Relations.PLACES] (and its `networkFingerprints` sibling) are derived views of every
 * locally-known [Place]: relation membership is not a synced primitive — `SyncBlock`s carry the
 * entity, and each receiver derives the relation locally. Without it a `Place` arriving via sync
 * lands in the local DB but stays invisible to the places stream.
 *
 * They are **declared on [PlaceService.Schema]**
 * (`serviceDataRelation(id, type, maintained = true)`) and served by the single generic
 * `RelationMaintainer` this installs (`core-relation-system.md` §9.2.1, R2) — it was the local
 * `PlaceRelationMaintainer`.
 *
 * ⚠⚠ **[mergerRegistry] is deliberately a required parameter, not an optional one**
 * (`timeline-recognition-and-place-naming.md` §9.2). The repo has a standing, expensive gotcha here:
 * `MomentMerger` and `TripMerger` were built, registered in tests only, and left out of production —
 * so every timeline type silently ran on last-writer-wins. A default value would let exactly that
 * recur; a required parameter makes forgetting it a **compile error** in every wiring that hosts
 * this service.
 */
fun ExecutionContext.initPlaceService(
    mergerRegistry: SemanticMergerRegistry,
    nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * Evidence for the ambiguous middle (§10.12). Optional — unlike [mergerRegistry] — because
     * omitting it is *not* a silent behaviour change: [PlaceCorroboration.Unknown] merges exactly what
     * the pass merged before there was such a thing, and a place-only wiring (Maps) genuinely has no
     * visit history to consult. Production hands it `VisitTimeCorroboration` from
     * `:timeline-service-proxy`, which is where the moments live.
     */
    corroboration: PlaceCorroboration = PlaceCorroboration.Unknown,
) {
    installRelationMaintainer()

    // P4e (§10.3): place identity is decided here, not in each app's projector — the write-time snap
    // in the app only ever sees what its own device knows, so it cannot converge two devices that
    // captured the same venue before either pulled the other. Runs on local writes AND on inbound
    // sync applies, because a duplicate is created by the second device and discovered by the first.
    if (!observerRegistry.any { it is PlaceReconciler }) {
        val reconciler = PlaceReconciler(this, nowEpochMs, corroboration)
        addDataObserver(reconciler)
        // ⚠⚠ One-shot repair of rows written before this pass existed. Load-bearing: a repeat visit
        // to an already-named place writes NO `Place` row (the §4.4 clobber guard skips it), so the
        // duplicates that most need merging are exactly the ones that stop firing the observer.
        reconciler.reconcileAll()
    }

    // Without these, `Place` and `PlaceNetworkFingerprint` conflicts fall back to last-writer-wins —
    // which for `Place` means a peer's blank capture erasing a user's rename (§9.1a). Registration is
    // idempotent (the registry is keyed by typeId), so a wiring that registers twice is fine.
    mergerRegistry.register(PlaceMerger())
    mergerRegistry.register(PlaceNetworkFingerprintMerger())
    mergerRegistry.register(PlaceMergeMerger())
    // Without this one, an inference on a peer would overwrite the user's own answer as soon as it
    // arrived — last-writer-wins cannot tell the two apart (`PlaceRoleMerger`). Registered HERE, in
    // production wiring, not only in tests: that omission is the standing gotcha this function's
    // ⚠⚠ above records.
    mergerRegistry.register(PlaceRoleMerger())
}
