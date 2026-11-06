package app.tenshu.maps.data

import app.tenshu.maps.model.Place
import app.tenshu.maps.model.SavedList
import kotlinx.coroutines.flow.Flow

/**
 * The Maps data seam (docs/proposals/maps-app.md §6; handoff `maps-agent.md` §7). View
 * models depend on this interface only; the shells bind the `:place-service`-backed
 * implementation and previews and tests bind [MockPlaceRepository] — no VM/UI change.
 *
 * Named `PlaceRepository` (not `MapsRepository`) per the handoff — the store *is*
 * `:place-service`; Maps is one consumer of it. Observable collections are `Flow` (the mock
 * backs them with `StateFlow`); the single-entity lookup is a `suspend` getter returning
 * null when absent; mutations re-emit through the held state.
 */
interface PlaceRepository {

    /**
     * Whether the last IPC read actually reached Control — `null` until a first outcome. The
     * real repository derives it from read outcomes plus a slow probe (`:app-reachability`,
     * fit-app.md §9.14); the app shell renders the "Can't reach Tenshu Control" banner off
     * `false` — without this, an unreachable Control folds every read to an empty list and the
     * app is silently blank. Defaults to always-reachable for
     * implementations that are not IPC-backed (the mock, previews).
     */
    fun controlReachable(): kotlinx.coroutines.flow.StateFlow<Boolean?> =
        kotlinx.coroutines.flow.MutableStateFlow(true)

    /**
     * *Why* Control is unreachable (`core-schema-skew-surface.md` SK-1b) — `null` while it is fine
     * or not yet known. [controlReachable] decides **whether** the banner shows; this decides what
     * it says, because a proxy that is answering and refusing on a schema version needs the user
     * to update something, and a dead channel does not.
     *
     * Defaults to a permanent `null`, which renders the generic unreachable copy.
     */
    fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<app.tenshu.reachability.UnreachableReason?> =
        app.tenshu.reachability.NO_UNREACHABLE_REASON

    /**
     * The user asked to check again ("Try again" on the checking gate's retry wall): re-probe Control
     * and re-read the realm now rather than at the next poll. A no-op without an engine.
     */
    fun recheckControl() = Unit

    /** Every place, across all sections — the VM filters into the four Places tabs (§1.1). */
    fun places(): Flow<List<Place>>

    /** The Saved tab's organizing lists (Coffee & books / Weekend / To try). */
    fun savedLists(): Flow<List<SavedList>>

    /** A place by id, with its visit history — the canonical, embeddable detail (D2). */
    suspend fun place(id: String): Place?

    /** Toggle a place's bookmark (Saved-tab toggle + detail header + "For you" Save). Re-emits. */
    suspend fun setSaved(id: String, saved: Boolean)
}
