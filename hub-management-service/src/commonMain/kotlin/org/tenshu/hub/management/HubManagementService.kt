package org.tenshu.hub.management

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * Management surface for Tenshu Hub nodes (hub proposal §8, as-built H4).
 *
 * v1 shape — **synced state, not remote operations** (see [HubStatus] KDoc
 * for the deviation rationale):
 *
 *  - [hubStatuses]: one hub-authored [HubStatus] row per hub device.
 *    "Get the hub's status" on a managing device is a local read of the
 *    freshest snapshot ( `:hub-management-service-client` ergonomics).
 *  - [hubCommands]: idempotent [HubCommand] rows a managing device authors
 *    (via [IssueHubCommand]) and the hub consumes at most once.
 *
 * The §8 stub's `ExpansionModule` relation (detected expansion stack) stays
 * stub-level and is deliberately **not** part of v1 — it lands with the
 * hardware detection slice.
 */
interface HubManagementService : Service {

    override val schema: ServiceSchema get() = Schema

    /** Hub-authored status snapshots, one per hub device. */
    val hubStatuses: TList<HubStatus>

    /** Management commands addressed to hubs; consumed at most once by the target. */
    val hubCommands: TList<HubCommand>

    companion object {
        const val ID = "tenshu/hub/management/service"

        // `maintained = true` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:hub-management-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.HUB_STATUSES,
                    type = TList.typeOf(HubStatus.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.HUB_COMMANDS,
                    type = TList.typeOf(HubCommand.Schema),
                    maintained = true,
                ),
            ),
        )
    }

    object Relations {
        const val HUB_STATUSES = "$ID/hubStatuses"
        const val HUB_COMMANDS = "$ID/hubCommands"
    }
}
