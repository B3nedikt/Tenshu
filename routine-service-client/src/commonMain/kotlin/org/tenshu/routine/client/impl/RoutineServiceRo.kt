package org.tenshu.routine.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.routine.PresenceState
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RoutineService

internal class RoutineServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : RoutineService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val routines: TList<Routine>
        get() = serviceRelation(RoutineService.Relations.ROUTINES, Routine.Schema)

    override val routineRuns: TList<RoutineRun>
        get() = serviceRelation(RoutineService.Relations.ROUTINE_RUNS, RoutineRun.Schema)

    override val presenceStates: TList<PresenceState>
        get() = serviceRelation(RoutineService.Relations.PRESENCE_STATES, PresenceState.Schema)
}
