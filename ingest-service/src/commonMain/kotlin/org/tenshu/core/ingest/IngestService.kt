package org.tenshu.core.ingest

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * Control's generic background-ingest registry (`drive-synced-folders-phase2.md`
 * §3). Holds one [IngestSource] per (app, source, device), exposing them as a
 * synced [sources] relation that the management/inspection surface (§8) and each
 * app's read-gate (§5) read directly.
 *
 * The registry is a **pull authority**: apps write their descriptor + status
 * (`RegisterSource` / `UpdateSourceStatus`) and Control writes the kill switch
 * (`SetSourceEnabled`); nobody is pushed to. Reads are plain relation reads —
 * there is no `listSources` / `isSourceEnabled` *operation*, those are client-side
 * filters over [sources] (mirrors `NotificationService.notifications`). The
 * constraint/storage *policy* is **not** here either — it stays the existing
 * `backgroundSyncConstraints()` + `storageService.status()` (§2), read by the
 * consumer alongside this relation.
 */
interface IngestService : Service {

    override val schema: ServiceSchema
        get() = Schema

    val sources: TList<IngestSource>

    companion object {
        const val ID = "tenshu/ingest/service"

        // `order` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:ingest-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.SOURCES,
                    type = TList.typeOf(IngestSource.Schema),
                    maintained = true,
                )
            )
        )
    }

    object Relations {
        const val SOURCES = "$ID/sources"
    }
}
