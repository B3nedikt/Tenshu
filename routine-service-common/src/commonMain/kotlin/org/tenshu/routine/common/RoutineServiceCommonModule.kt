package org.tenshu.routine.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.routine.PresenceState
import org.tenshu.routine.Routine
import org.tenshu.routine.RoutineFiringMarker
import org.tenshu.routine.RoutineRun
import org.tenshu.routine.RoutineService
import org.tenshu.routine.common.impl.PresenceStateDto
import org.tenshu.routine.common.impl.PresenceStateRo
import org.tenshu.routine.common.impl.RoutineDto
import org.tenshu.routine.common.impl.RoutineFiringMarkerDto
import org.tenshu.routine.common.impl.RoutineFiringMarkerRo
import org.tenshu.routine.common.impl.RoutineRo
import org.tenshu.routine.common.impl.RoutineRunDto
import org.tenshu.routine.common.impl.RoutineRunRo

/**
 * Schema registration for the routines service (`routines-engine.md` RE-1).
 *
 * Both composites are `Syncable(Eager)` **new** types — additive, no existing
 * row reshaped — so this owes **no `TENSHU_STORAGE_FORMAT_VERSION` bump and no
 * relay change** (the `MovementTrace` / HD-1 precedent, and HR#9's own carve-out
 * for genuinely new types).
 *
 * ⚠ Any later change that adds a field to a *shipped* composite here does owe a
 * bump, and its PR must say so.
 */
val routineServiceCommonModule = tModule {

    composite(
        interfaceClass = Routine::class,
        dataSchema = Routine.Schema,
        dtoFactory = ::RoutineDto,
        roFactory = { chain, ctx ->
            RoutineRo(
                requestChain = chain,
                context = ctx,
                schema = Routine.Schema,
            )
        }
    )

    composite(
        interfaceClass = RoutineRun::class,
        dataSchema = RoutineRun.Schema,
        dtoFactory = ::RoutineRunDto,
        roFactory = { chain, ctx ->
            RoutineRunRo(
                requestChain = chain,
                context = ctx,
                schema = RoutineRun.Schema,
            )
        }
    )

    // ⚠ Local-only (RE-D4): carries no Syncable trait, so it is never
    // serialised into a block or pushed — but it still needs a factory to be
    // materialised out of the local store.
    composite(
        interfaceClass = RoutineFiringMarker::class,
        dataSchema = RoutineFiringMarker.Schema,
        dtoFactory = ::RoutineFiringMarkerDto,
        roFactory = { chain, ctx ->
            RoutineFiringMarkerRo(
                requestChain = chain,
                context = ctx,
                schema = RoutineFiringMarker.Schema,
            )
        }
    )

    // RE-4: per-device presence. ⚠⚠ A new sensitive artifact (T-RE-04) — coarse
    // location about household members. Syncable because the household verdict
    // is cross-device by nature; deliberately carries no coordinates.
    composite(
        interfaceClass = PresenceState::class,
        dataSchema = PresenceState.Schema,
        dtoFactory = ::PresenceStateDto,
        roFactory = { chain, ctx ->
            PresenceStateRo(
                requestChain = chain,
                context = ctx,
                schema = PresenceState.Schema,
            )
        }
    )

    service(RoutineService.Schema)

    trait(
        dataSchema = Routine.Schema,
        traitSchema = Routine.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = RoutineRun.Schema,
        traitSchema = RoutineRun.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = RoutineFiringMarker.Schema,
        traitSchema = RoutineFiringMarker.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = PresenceState.Schema,
        traitSchema = PresenceState.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
}
