package org.tenshu.timeline.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.timeline.RecordMoment
import org.tenshu.core.timeline.RecordMovementTrace
import org.tenshu.core.timeline.RecordSnappedRoute
import org.tenshu.core.timeline.RecordTrip
import org.tenshu.timeline.proxy.executor.RecordMomentImplementation
import org.tenshu.timeline.proxy.executor.RecordMovementTraceImplementation
import org.tenshu.timeline.proxy.executor.RecordSnappedRouteImplementation
import org.tenshu.timeline.proxy.executor.RecordTripImplementation

fun timelineServiceProxyImplementationFactory(
    id: String
): OperationImplementation? {
    return when (id) {
        RecordMoment.ID -> RecordMomentImplementation()
        RecordTrip.ID -> RecordTripImplementation()
        RecordMovementTrace.ID -> RecordMovementTraceImplementation()
        RecordSnappedRoute.ID -> RecordSnappedRouteImplementation()
        else -> null
    }
}
