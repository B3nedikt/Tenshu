package org.tenshu.core.timeline

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * The Timeline service (`docs/proposals/timeline-services.md` §5) — the app-specific spine:
 * the store of [Moment]s (the atom) and [Trip]s (curations). Days / place-visit history /
 * activity roll-ups / trip contents are **derived projections** the real `TimelineRepository`
 * computes at read time (§1, §6), not stored here.
 */
interface TimelineService : Service {

    override val schema: ServiceSchema get() = Schema

    /** Every moment (the spine). */
    val moments: TList<Moment>

    /** Every bound trip (a curation). */
    val trips: TList<Trip>

    /** Every captured movement path (the subject of a `MOVEMENT` moment, movement capture M2). */
    val traces: TList<MovementTrace>

    /**
     * Every movement path corrected onto the map geometry it ran on (B4). A **derived** sibling of
     * [traces], keyed by the same `traceId`; read alongside them by anything that draws a route.
     * Sparse by design — only a trace that provably followed exclusive rail carries one.
     */
    val snappedRoutes: TList<SnappedRoute>

    companion object {
        const val ID = "tenshu/timeline/service"

        // `order` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:timeline-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            // v1 is the release baseline (`core-schema-evolution.md` Appendix A).
            // ⚠ `MovementTrace` rides as a `recordMovementTrace` parameter and so cannot gain a
            // field (`SchemaMigrationRegistry.verify`); see `SnappedRoute`.
            version = 1,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.MOMENTS,
                    type = TList.typeOf(Moment.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.TRIPS,
                    type = TList.typeOf(Trip.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.TRACES,
                    type = TList.typeOf(MovementTrace.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.SNAPPED_ROUTES,
                    type = TList.typeOf(SnappedRoute.Schema),
                    maintained = true,
                )
            )
        )
    }

    object Relations {
        const val MOMENTS = "$ID/moments"
        const val TRIPS = "$ID/trips"
        const val TRACES = "$ID/traces"
        const val SNAPPED_ROUTES = "$ID/snappedRoutes"
    }
}
