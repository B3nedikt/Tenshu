package org.tenshu.core.mapregion

import org.tenshu.core.Service
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service

/**
 * The map-region service (`docs/proposals/maps-region-delivery.md` D5/D6) — the app-facing
 * interface to the proxy's offline-basemap region delivery. Unlike `:place-service`, this is
 * **not** a synced-entity service: it exposes *operations* (fetch the signed catalog, list
 * what's installed, download/delete a region), and the proxy owns the canonical region store +
 * the network egress. Map apps keep `INTERNET` stripped and reach regions only through this
 * IPC surface (D6, House Rule #1 enforced at the OS boundary).
 *
 * Because nothing here syncs across devices, the service declares **no data relations** —
 * every result is produced by an operation, not read from a synced stream.
 *
 * v1 (slice 2a) ships [Catalog], [Installed] and [DeleteRegion]. `download(regionId)` — the
 * resumable ranged GET + progress stream + pull-range-source hand-off to the app terminus —
 * lands in slice 2b.
 */
interface MapRegionService : Service {

    override val schema: ServiceSchema get() = Schema

    companion object {
        const val ID = "tenshu/mapRegion/service"

        val Schema: ServiceSchema = service(id = ID)
    }
}
