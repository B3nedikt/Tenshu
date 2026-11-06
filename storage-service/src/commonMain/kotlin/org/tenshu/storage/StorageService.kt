package org.tenshu.storage

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * Service surface for the user's *storage provision* — "is storage available
 * to me, how much is used, how much is left". See
 * `docs/proposals/storage-capacity-gating.md`.
 *
 * **Decoupled from Publishing.** Entitlement (*what* the user is granted) is the
 * Publisher's / Rei's job; provision (*storing the bytes*) is the relay's. This
 * service owns the provision view: it reads the limit from the entitlement and
 * the usage from the relay, and depends on neither's code — the figures arrive
 * through the `StorageProvider` seam wired at the proxy DI layer.
 *
 * **One provider per user (v1).** "One Remote Service == one Provider", so the
 * conceptual surface is a single [StorageStatus], not a list. Services expose
 * relations as [TList], though, and there is no bare-`Maybe` service property —
 * so [statuses] is a **single-row relation** and the client accessor projects it
 * to one value (see `:storage-service-client` `status`). Multiple concurrent
 * providers is a deliberate non-goal.
 *
 * Consumers traverse [statuses] with the standard chain DSL:
 *
 * ```
 * service.statuses
 *     .search<StorageStatus> { key equalTo StorageStatus.SINGLETON_KEY }
 *     .get()                                   // one-shot read
 *
 * service.statuses.flow()                      // reactive (single-row stream)
 * ```
 */
interface StorageService : Service {

    override val schema: ServiceSchema get() = Schema

    /**
     * Single-row relation carrying the current [StorageStatus]. The proxy
     * maintains exactly one element (keyed on [StorageStatus.SINGLETON_KEY]);
     * the client projects it to a single value.
     */
    val statuses: TList<StorageStatus>

    companion object {
        const val ID = "tenshu/storage/service"

        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.STATUSES,
                    type = TList.typeOf(StorageStatus.Schema),
                ),
            ),
        )
    }

    object Relations {
        const val STATUSES = "$ID/statuses"
    }
}
