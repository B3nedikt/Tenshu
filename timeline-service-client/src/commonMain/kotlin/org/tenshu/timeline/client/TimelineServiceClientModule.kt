package org.tenshu.timeline.client

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.MovementTrace
import org.tenshu.core.timeline.RecordMoment
import org.tenshu.core.timeline.RecordMovementTrace
import org.tenshu.core.timeline.RecordSnappedRoute
import org.tenshu.core.timeline.RecordTrip
import org.tenshu.core.timeline.SnappedRoute
import org.tenshu.core.timeline.SubjectRef
import org.tenshu.core.timeline.TimelineService
import org.tenshu.core.timeline.Trip
import org.tenshu.timeline.client.impl.RecordMomentRo
import org.tenshu.timeline.client.impl.RecordMovementTraceRo
import org.tenshu.timeline.client.impl.RecordSnappedRouteRo
import org.tenshu.timeline.client.impl.RecordTripRo
import org.tenshu.timeline.client.impl.TimelineServiceRo

val timelineServiceClientModule = tModule {

    type(
        interfaceClass = SubjectRef::class,
        dataSchema = SubjectRef.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row
        // when it is READ; this builds the RECEIVER a chain-DSL predicate lambda runs
        // against, so registering only the former leaves the type readable and
        // **unfilterable** — `filter<SubjectRef> { … }` dies casting `CompositeRo`, a long way
        // from the registration that caused it. The `FileModification` / `Workout`
        // registrations are the shape to copy.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, SubjectRef.Schema), SubjectRef {
                override val schema: CompositeSchema get() = SubjectRef.Schema
            }
        }
    )

    type(
        interfaceClass = Moment::class,
        dataSchema = Moment.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row
        // when it is READ; this builds the RECEIVER a chain-DSL predicate lambda runs
        // against, so registering only the former leaves the type readable and
        // **unfilterable** — `filter<Moment> { … }` dies casting `CompositeRo`, a long way
        // from the registration that caused it. The `FileModification` / `Workout`
        // registrations are the shape to copy.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, Moment.Schema), Moment {
                override val schema: CompositeSchema get() = Moment.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordMoment::class,
        protocolSchema = RecordMoment.Schema,
        roFactory = ::RecordMomentRo
    )

    type(
        interfaceClass = Trip::class,
        dataSchema = Trip.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row
        // when it is READ; this builds the RECEIVER a chain-DSL predicate lambda runs
        // against, so registering only the former leaves the type readable and
        // **unfilterable** — `filter<Trip> { … }` dies casting `CompositeRo`, a long way
        // from the registration that caused it. The `FileModification` / `Workout`
        // registrations are the shape to copy.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, Trip.Schema), Trip {
                override val schema: CompositeSchema get() = Trip.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordTrip::class,
        protocolSchema = RecordTrip.Schema,
        roFactory = ::RecordTripRo
    )

    type(
        interfaceClass = MovementTrace::class,
        dataSchema = MovementTrace.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row
        // when it is READ; this builds the RECEIVER a chain-DSL predicate lambda runs
        // against, so registering only the former leaves the type readable and
        // **unfilterable** — `filter<MovementTrace> { … }` dies casting `CompositeRo`, a long way
        // from the registration that caused it. The `FileModification` / `Workout`
        // registrations are the shape to copy.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, MovementTrace.Schema), MovementTrace {
                override val schema: CompositeSchema get() = MovementTrace.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordMovementTrace::class,
        protocolSchema = RecordMovementTrace.Schema,
        roFactory = ::RecordMovementTraceRo
    )

    type(
        interfaceClass = SnappedRoute::class,
        dataSchema = SnappedRoute.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row
        // when it is READ; this builds the RECEIVER a chain-DSL predicate lambda runs
        // against, so registering only the former leaves the type readable and
        // **unfilterable** — `filter<SnappedRoute> { … }` dies casting `CompositeRo`, a long way
        // from the registration that caused it. The `FileModification` / `Workout`
        // registrations are the shape to copy.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, SnappedRoute.Schema), SnappedRoute {
                override val schema: CompositeSchema get() = SnappedRoute.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordSnappedRoute::class,
        protocolSchema = RecordSnappedRoute.Schema,
        roFactory = ::RecordSnappedRouteRo
    )

    type(
        interfaceClass = TimelineService::class,
        dataSchema = TimelineService.Schema,
        roFactory = ::TimelineServiceRo
    )
}
