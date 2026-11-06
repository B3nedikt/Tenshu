package org.tenshu.routine.client

import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.routine.DeleteRoutine
import org.tenshu.routine.EvaluateDueRoutines
import org.tenshu.routine.RoutineService
import org.tenshu.routine.RunRoutineNow
import org.tenshu.routine.SaveRoutine
import org.tenshu.routine.SetRoutineEnabled
import org.tenshu.routine.client.impl.DeleteRoutineRo
import org.tenshu.routine.client.impl.EvaluateDueRoutinesRo
import org.tenshu.routine.client.impl.RoutineServiceRo
import org.tenshu.routine.client.impl.RunRoutineNowRo
import org.tenshu.routine.client.impl.SaveRoutineRo
import org.tenshu.routine.client.impl.SetRoutineEnabledRo

/**
 * ⚠⚠ **Client-module Ro registration is not optional.** An operation whose Ro is
 * registered only in the common module dies as an opaque `error/internal` at
 * every call site — the dynamic-property template dispatches through the
 * *client* module's `roFactory` (the `GetWorkoutStreamsRo` finding, bounded-store
 * work). Every op declared in `:routine-service` must appear here.
 */
val routineServiceClientModule = tModule {

    operation(
        interfaceClass = SaveRoutine::class,
        protocolSchema = SaveRoutine.Schema,
        roFactory = ::SaveRoutineRo
    )

    operation(
        interfaceClass = DeleteRoutine::class,
        protocolSchema = DeleteRoutine.Schema,
        roFactory = ::DeleteRoutineRo
    )

    operation(
        interfaceClass = SetRoutineEnabled::class,
        protocolSchema = SetRoutineEnabled.Schema,
        roFactory = ::SetRoutineEnabledRo
    )

    operation(
        interfaceClass = RunRoutineNow::class,
        protocolSchema = RunRoutineNow.Schema,
        roFactory = ::RunRoutineNowRo
    )

    operation(
        interfaceClass = EvaluateDueRoutines::class,
        protocolSchema = EvaluateDueRoutines.Schema,
        roFactory = ::EvaluateDueRoutinesRo
    )

    service(
        interfaceClass = RoutineService::class,
        serviceSchema = RoutineService.Schema,
        roFactory = ::RoutineServiceRo
    )
}
