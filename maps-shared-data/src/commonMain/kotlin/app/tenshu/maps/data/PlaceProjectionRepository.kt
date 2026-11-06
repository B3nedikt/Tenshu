package app.tenshu.maps.data

import app.tenshu.maps.model.Place
import app.tenshu.maps.model.SavedList
import app.tenshu.reachability.ControlReachability
import app.tenshu.reachability.retryingOnThrow
import app.tenshu.reachability.UnreachableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.tenshu.core.TString
import org.tenshu.core.fold
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.place.Place as SvcPlace
import org.tenshu.core.place.PlaceRole as SvcPlaceRole
import org.tenshu.core.place.RoleSource as SvcRoleSource
import org.tenshu.place.client.placeService

/**
 * The real [PlaceRepository] over a [TenshuClient]'s `:place-service` (Ph9, `maps-app.md` §6). Reads
 * the stored places reactively and projects them to the Maps UI model via [MapsPlaceProjection] —
 * so the same VM/UI that ran over [MockPlaceRepository] now renders real, synced places.
 *
 * **Thin-screens tradeoff (documented on [MapsPlaceProjection]):** the store carries only
 * `{placeId, latE7, lngE7, label}`, so [savedLists] is empty, [setSaved] is a no-op (no `saved`
 * field to persist), and all places project under **Yours** — the Saved / For-you tabs are empty and
 * detail loses photos/visit-history until `:place-service` graduates those fields.
 */
class PlaceProjectionRepository(
    private val client: TenshuClient,
    /**
     * The app's **shared** Control-reachability engine ([mapsControlReachability] — one signal
     * per app: the region repository feeds the same instance). ⚠ Required, never defaulted
     * (ADR 0025 §8.6): a silent probe-less fallback here is the inert-seam trap.
     */
    internal val reach: ControlReachability,
) : PlaceRepository {

    /**
     * Retry a read that threw, naming it — [app.tenshu.reachability.retryingOnThrow] carries why
     * this is not a `catch`, and why it does not report an outage.
     */
    private fun <T> Flow<T>.retrying(read: String): Flow<T> =
        retryingOnThrow(read) { message, _ -> println("[maps] $message") }

    override fun controlReachable(): StateFlow<Boolean?> = reach.state

    override fun controlUnreachableReason(): kotlinx.coroutines.flow.StateFlow<UnreachableReason?> = reach.reason

    override fun recheckControl() = reach.recheck()

    override fun places(): Flow<List<Place>> =
        reach.resubscribing {
            client.placeService.places.select { fields(*PLACE_FIELDS) }.flow()
                .map { r ->
                    // ⚠ Read per emission rather than `combine`d: the roles relation holds one row
                    // per roled place (a handful), and a second reactive source here would have to
                    // carry `mergesFlow`'s never-blank discipline or it could stall the map on an
                    // empty combine. Hoisted out of `fold` because that lambda is not a suspend
                    // context — so it also runs on the failure path, which costs one tiny read.
                    val roles = confirmedRoles()
                    r.fold(
                        onSuccess = { rows ->
                            rows.toList().map { placeInput(it, roles) }
                        },
                        onFailure = { reach.markUnreachable(); emptyList() },
                    )
                }
                // ⚠⚠ Was a `catch` that emitted an empty list. That is not "keep the screen
                // rendering" — `catch` COMPLETES the flow, so the screen kept rendering the empty
                // list and nothing else, forever, whatever the proxy did next. And the empty
                // emission was indistinguishable from "the realm holds no places".
                // [retryingOnThrow] carries the rest, including why a throw is not an outage.
                .retrying("places")
        }
            .map(MapsPlaceProjection::places)


    /**
     * `placeId → confirmed role token`. Tiny (one row per roled place) and **best-effort**: a failed
     * read degrades to "no place has a role", which renders exactly as before PR-4 rather than
     * blanking the map. Suggestions are filtered out here — only an answered role may change how a
     * place is drawn.
     */
    private suspend fun confirmedRoles(): Map<String, String> = try {
        client.placeService.roles.select { fields(*PLACE_ROLE_FIELDS) }.getAll()
            .fold(onSuccess = { it.toList() }, onFailure = { emptyList() })
            .mapNotNull { r ->
                val source = r.getFieldValue<TString>(SvcPlaceRole.Fields.ROLE_SOURCE).get()
                if (!SvcRoleSource.isUser(source)) return@mapNotNull null
                val role = r.getFieldValue<TString>(SvcPlaceRole.Fields.ROLE).get()
                if (role.isBlank() || role == ROLE_NONE) return@mapNotNull null
                r.getFieldValue<TString>(SvcPlaceRole.Fields.PLACE_ID).get() to role
            }
            .toMap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("[maps] role read failed, rendering roleless: ${e::class.simpleName}: ${e.message}")
        emptyMap()
    }

    /** No saved-list store in `:place-service` → empty (the Saved tab's organiser has no source). */
    override fun savedLists(): Flow<List<SavedList>> = flowOf(emptyList())

    override suspend fun place(id: String): Place? =
        try {
            client.placeService.places.select { fields(*PLACE_FIELDS) }.getAll()
                .fold(
                    onSuccess = { it.toList() },
                    onFailure = { reach.markUnreachable(); emptyList() },
                )
                .let { rows -> val roles = confirmedRoles(); rows.map { placeInput(it, roles) } }
                .map(MapsPlaceProjection::place)
                .find { it.id == id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Same silence as `places()`: `null` here is indistinguishable from "no such place".
            println("[maps] place($id) read failed, reporting absent: ${e::class.simpleName}: ${e.message}")
            reach.markUnreachable()
            null
        }

    /** No `saved` field in the store → a bookmark has nowhere to persist. Documented no-op. */
    override suspend fun setSaved(id: String, saved: Boolean) = Unit

    private fun placeInput(e: Entity<out SvcPlace>, roles: Map<String, String> = emptyMap()) = PlaceInput(
        id = e.getFieldValue<TString>(SvcPlace.Fields.PLACE_ID).get(),
        label = e.getFieldValue<TString>(SvcPlace.Fields.LABEL).get(),
        latE7 = e.getFieldValue<TInteger>(SvcPlace.Fields.LAT_E7).get(),
        lngE7 = e.getFieldValue<TInteger>(SvcPlace.Fields.LNG_E7).get(),
        role = roles[e.getFieldValue<TString>(SvcPlace.Fields.PLACE_ID).get()].orEmpty(),
    )

    private companion object {
        val PLACE_FIELDS = arrayOf(
            SvcPlace.Fields.PLACE_ID, SvcPlace.Fields.LAT_E7, SvcPlace.Fields.LNG_E7,
            SvcPlace.Fields.CAPTURED_AT, SvcPlace.Fields.LABEL,
        )
        val PLACE_ROLE_FIELDS = arrayOf(
            SvcPlaceRole.Fields.PLACE_ID, SvcPlaceRole.Fields.ROLE, SvcPlaceRole.Fields.ROLE_SOURCE,
        )
        const val ROLE_NONE = "none"
    }
}
