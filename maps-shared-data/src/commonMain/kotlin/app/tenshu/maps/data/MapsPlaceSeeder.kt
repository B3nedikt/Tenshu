package app.tenshu.maps.data

import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.core.TContext
import org.tenshu.core.place.Place
import org.tenshu.core.place.recordPlace
import org.tenshu.place.client.placeService

/**
 * Seeds the Maps mock POIs into `:place-service` through the real `recordPlace` op — the
 * Timeline/Fit **seed == import** pattern (`ReferenceTimelineSource` → `TimelineImportWriter`). Each
 * mock place's projected Firenze coordinate + name is written as the thin `Place` record the store
 * carries; the rich mock fields (category / photos / saved lists / reasons / visit history) have no
 * store and are intentionally dropped (they reappear only when `:place-service` graduates, or a real
 * POI/geocoding source lands — this is the accepted thin-screens tradeoff).
 *
 * Idempotent: `recordPlace` is content-addressed by `placeId`, so re-seeding folds to one row.
 */
class MapsPlaceSeeder(
    private val client: TenshuClient,
    private val context: TContext,
) {
    suspend fun seed() {
        MapsMockData.places.forEach { p ->
            val location = p.location ?: return@forEach
            client.placeService.recordPlace(
                Place.create(
                    context = context,
                    placeId = p.id,
                    latE7 = (location.latitude * E7).toLong(),
                    lngE7 = (location.longitude * E7).toLong(),
                    capturedAt = UnixInstantDto(0L), // no capture time in the mock; a placeholder epoch
                    label = p.name,
                ),
            )
        }
    }

    private companion object {
        const val E7 = 1e7
    }
}
