package org.tenshu.timeline.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MomentDataIdProvider
import org.tenshu.core.timeline.MomentSource
import org.tenshu.core.timeline.MomentSourceDataIdProvider
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.MovementTraceDataIdProvider
import org.tenshu.core.timeline.SnappedRoute
import org.tenshu.core.timeline.SnappedRouteDataIdProvider
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService
import org.tenshu.core.timeline.Trip
import org.tenshu.core.timeline.TripDataIdProvider
import org.tenshu.core.timeline.TripMember
import org.tenshu.core.timeline.TripMemberDataIdProvider
import org.tenshu.timeline.common.impl.MomentDataIdProviderImpl
import org.tenshu.timeline.common.impl.MomentDto
import org.tenshu.timeline.common.impl.MomentSourceDataIdProviderImpl
import org.tenshu.timeline.common.impl.MomentSourceDto
import org.tenshu.timeline.common.impl.MovementTraceDataIdProviderImpl
import org.tenshu.timeline.common.impl.MovementTraceDto
import org.tenshu.timeline.common.impl.SnappedRouteDataIdProviderImpl
import org.tenshu.timeline.common.impl.SnappedRouteDto
import org.tenshu.timeline.common.impl.SubjectRefDto
import org.tenshu.timeline.common.impl.TripDataIdProviderImpl
import org.tenshu.timeline.common.impl.TripDto
import org.tenshu.timeline.common.impl.TripMemberDataIdProviderImpl
import org.tenshu.timeline.common.impl.TripMemberDto

val timelineServiceCommonModule = tModule {

    service(TimelineService.Schema)

    composite(
        interfaceClass = SubjectRef::class,
        dataSchema = SubjectRef.Schema,
        dtoFactory = ::SubjectRefDto
    )

    composite(
        interfaceClass = Moment::class,
        dataSchema = Moment.Schema,
        dtoFactory = ::MomentDto
    )

    trait(
        dataSchema = Moment.Schema,
        traitSchema = MomentDataIdProvider.Schema,
        traitFactory = { context, schema ->
            MomentDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = MomentSource::class,
        dataSchema = MomentSource.Schema,
        dtoFactory = ::MomentSourceDto
    )

    trait(
        dataSchema = MomentSource.Schema,
        traitSchema = MomentSourceDataIdProvider.Schema,
        traitFactory = { context, schema ->
            MomentSourceDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = TripMember::class,
        dataSchema = TripMember.Schema,
        dtoFactory = ::TripMemberDto
    )

    trait(
        dataSchema = TripMember.Schema,
        traitSchema = TripMemberDataIdProvider.Schema,
        traitFactory = { context, schema ->
            TripMemberDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = Trip::class,
        dataSchema = Trip.Schema,
        dtoFactory = ::TripDto
    )

    trait(
        dataSchema = Trip.Schema,
        traitSchema = TripDataIdProvider.Schema,
        traitFactory = { context, schema ->
            TripDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = MovementTrace::class,
        dataSchema = MovementTrace.Schema,
        dtoFactory = ::MovementTraceDto
    )

    trait(
        dataSchema = MovementTrace.Schema,
        traitSchema = MovementTraceDataIdProvider.Schema,
        traitFactory = { context, schema ->
            MovementTraceDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = SnappedRoute::class,
        dataSchema = SnappedRoute.Schema,
        dtoFactory = ::SnappedRouteDto
    )

    trait(
        dataSchema = SnappedRoute.Schema,
        traitSchema = SnappedRouteDataIdProvider.Schema,
        traitFactory = { context, schema ->
            SnappedRouteDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
}
