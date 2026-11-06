package org.tenshu.timeline.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.SnappedRoute
import org.tenshu.core.timeline.TimelineService
import org.tenshu.core.timeline.Trip

internal class TimelineServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : TimelineService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val moments: TList<Moment>
        get() = serviceRelation(TimelineService.Relations.MOMENTS, Moment.Schema)

    override val trips: TList<Trip>
        get() = serviceRelation(TimelineService.Relations.TRIPS, Trip.Schema)

    override val traces: TList<MovementTrace>
        get() = serviceRelation(TimelineService.Relations.TRACES, MovementTrace.Schema)

    override val snappedRoutes: TList<SnappedRoute>
        get() = serviceRelation(TimelineService.Relations.SNAPPED_ROUTES, SnappedRoute.Schema)
}
