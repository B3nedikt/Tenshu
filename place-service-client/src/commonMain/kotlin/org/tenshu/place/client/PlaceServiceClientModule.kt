package org.tenshu.place.client

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.core.place.ExternalPlaceRef
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.PlaceService
import org.tenshu.core.place.RecordPlace
import org.tenshu.core.place.RecordPlaceMerge
import org.tenshu.core.place.RecordPlaceNetworkFingerprint
import org.tenshu.core.place.RecordPlaceRole
import org.tenshu.place.client.impl.PlaceServiceRo
import org.tenshu.place.client.impl.RecordPlaceMergeRo
import org.tenshu.place.client.impl.RecordPlaceNetworkFingerprintRo
import org.tenshu.place.client.impl.RecordPlaceRo
import org.tenshu.place.client.impl.RecordPlaceRoleRo

val placeServiceClientModule = tModule {

    type(
        interfaceClass = ExternalPlaceRef::class,
        dataSchema = ExternalPlaceRef.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row
        // when it is READ; this builds the RECEIVER a chain-DSL predicate lambda runs
        // against, so registering only the former leaves the type readable and
        // **unfilterable** — `filter<ExternalPlaceRef> { … }` dies casting `CompositeRo`, a long way
        // from the registration that caused it. The `FileModification` / `Workout`
        // registrations are the shape to copy.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, ExternalPlaceRef.Schema), ExternalPlaceRef {
                override val schema: CompositeSchema get() = ExternalPlaceRef.Schema
            }
        }
    )

    type(
        interfaceClass = Place::class,
        dataSchema = Place.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row
        // when it is READ; this builds the RECEIVER a chain-DSL predicate lambda runs
        // against, so registering only the former leaves the type readable and
        // **unfilterable** — `filter<Place> { … }` dies casting `CompositeRo`, a long way
        // from the registration that caused it. The `FileModification` / `Workout`
        // registrations are the shape to copy.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, Place.Schema), Place {
                override val schema: CompositeSchema get() = Place.Schema
            }
        }
    )

    // ⚠ A composite that rides an op parameter needs its `type(...)` registration HERE, in the
    // CLIENT module — not only in the common one. Without it the chain cannot build the Ro and the
    // call dies as an opaque `error/internal` (the Workout.Streams / TS-4 precedent).
    type(
        interfaceClass = PlaceNetworkFingerprint::class,
        dataSchema = PlaceNetworkFingerprint.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row
        // when it is READ; this builds the RECEIVER a chain-DSL predicate lambda runs
        // against, so registering only the former leaves the type readable and
        // **unfilterable** — `filter<PlaceNetworkFingerprint> { … }` dies casting `CompositeRo`, a long way
        // from the registration that caused it. The `FileModification` / `Workout`
        // registrations are the shape to copy.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, PlaceNetworkFingerprint.Schema), PlaceNetworkFingerprint {
                override val schema: CompositeSchema get() = PlaceNetworkFingerprint.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordPlace::class,
        protocolSchema = RecordPlace.Schema,
        roFactory = ::RecordPlaceRo
    )

    type(
        interfaceClass = PlaceMerge::class,
        dataSchema = PlaceMerge.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row
        // when it is READ; this builds the RECEIVER a chain-DSL predicate lambda runs
        // against, so registering only the former leaves the type readable and
        // **unfilterable** — `filter<PlaceMerge> { … }` dies casting `CompositeRo`, a long way
        // from the registration that caused it. The `FileModification` / `Workout`
        // registrations are the shape to copy.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, PlaceMerge.Schema), PlaceMerge {
                override val schema: CompositeSchema get() = PlaceMerge.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordPlaceNetworkFingerprint::class,
        protocolSchema = RecordPlaceNetworkFingerprint.Schema,
        roFactory = ::RecordPlaceNetworkFingerprintRo
    )

    operation(
        interfaceClass = RecordPlaceMerge::class,
        protocolSchema = RecordPlaceMerge.Schema,
        roFactory = ::RecordPlaceMergeRo
    )

    // ⚠⚠ `PlaceRole` rides as a `recordPlaceRole` parameter, so it needs this CLIENT-side
    // `type(...)` too — the common module's `composite(...)` alone is not enough. See the ⚠ above.
    type(
        interfaceClass = PlaceRole::class,
        dataSchema = PlaceRole.Schema,
        // ⚠⚠ A TYPED request object, not a bare `CompositeRo`. `dtoFactory` builds the row
        // when it is READ; this builds the RECEIVER a chain-DSL predicate lambda runs
        // against, so registering only the former leaves the type readable and
        // **unfilterable** — `filter<PlaceRole> { … }` dies casting `CompositeRo`, a long way
        // from the registration that caused it. The `FileModification` / `Workout`
        // registrations are the shape to copy.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, PlaceRole.Schema), PlaceRole {
                override val schema: CompositeSchema get() = PlaceRole.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordPlaceRole::class,
        protocolSchema = RecordPlaceRole.Schema,
        roFactory = ::RecordPlaceRoleRo
    )

    type(
        interfaceClass = PlaceService::class,
        dataSchema = PlaceService.Schema,
        roFactory = ::PlaceServiceRo
    )
}
