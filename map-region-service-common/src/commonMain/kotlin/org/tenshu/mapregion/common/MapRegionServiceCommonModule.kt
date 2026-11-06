package org.tenshu.mapregion.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.mapregion.GeocodedPlace
import org.tenshu.core.mapregion.InstalledRegion
import org.tenshu.core.mapregion.MeteredNetworkRefusedError
import org.tenshu.core.mapregion.MapRegionService
import org.tenshu.core.mapregion.RailLineGeometry
import org.tenshu.core.mapregion.RegionCatalog
import org.tenshu.core.mapregion.RegionDownloadProgress
import org.tenshu.core.mapregion.RegionEntry
import org.tenshu.mapregion.common.impl.GeocodedPlaceDto
import org.tenshu.mapregion.common.impl.InstalledRegionDto
import org.tenshu.mapregion.common.impl.MeteredNetworkRefusedErrorDto
import org.tenshu.mapregion.common.impl.RailLineGeometryDto
import org.tenshu.mapregion.common.impl.RegionCatalogDto
import org.tenshu.mapregion.common.impl.RegionDownloadProgressDto
import org.tenshu.mapregion.common.impl.RegionEntryDto

/**
 * Common-side registration for `:map-region-service`. Registers the service schema and every wire
 * composite with its DTO factory, so the operation return values materialise from the wire on both
 * the proxy and client sides.
 *
 * ⚠⚠ **Every composite an operation returns belongs here.** The client module's `type(...)` registers
 * a *request-object* factory only (`registerType` writes `requestObjectFactories`, never
 * `compositeFactories`), so a composite registered only there cannot be **built** — and the proxy
 * builds it, via `context.create`, to answer the op. The omission is invisible to a compiler, to an
 * in-process unit test of the geocoder, and to the schema-lockstep beacon; it shows up only as an
 * opaque `error/internal` on a device. See the note on [GeocodedPlace] below for the one that shipped.
 *
 * No `trait(...)` / `serviceDataRelation(...)` here (cf. `placeServiceCommonModule`): the region
 * types are operation results, not synced entities, so there is no `DataIdProvider` trait and no
 * relation to maintain.
 */
val mapRegionServiceCommonModule = tModule {

    service(MapRegionService.Schema)

    composite(
        interfaceClass = RegionEntry::class,
        dataSchema = RegionEntry.Schema,
        dtoFactory = ::RegionEntryDto
    )

    composite(
        interfaceClass = RegionCatalog::class,
        dataSchema = RegionCatalog.Schema,
        dtoFactory = ::RegionCatalogDto
    )

    composite(
        interfaceClass = InstalledRegion::class,
        dataSchema = InstalledRegion.Schema,
        dtoFactory = ::InstalledRegionDto
    )

    composite(
        interfaceClass = RailLineGeometry::class,
        dataSchema = RailLineGeometry.Schema,
        dtoFactory = ::RailLineGeometryDto
    )

    // ⚠⚠ Registering the type on the *client* side is not enough, and the omission is silent until
    // it runs on a device. `ReverseGeocodeImplementation` builds this composite proxy-side via
    // `context.create`, which resolves through `SchemaRegistry.compositeFactory` — an
    // `error("No composite factory registered for type: …")` when the type is absent. That throw
    // escapes the executor into `LocalTaskExecutor`'s catch-all and reaches the app as an opaque
    // `error/internal`, which `ProxyReverseGeocoder.resolve` folds to `null` and treats as "not
    // found" — so the whole proxy venue-naming tier dies quietly and the chain falls through to the
    // device geocoder, which answers with the street instead of the venue.
    //
    // A primitive return such as `TString` needs no factory; this composite does. Pinned by
    // `ReverseGeocodePlaceOpTest`.
    composite(
        interfaceClass = GeocodedPlace::class,
        dataSchema = GeocodedPlace.Schema,
        dtoFactory = ::GeocodedPlaceDto
    )

    // A TError is a composite like any other: the proxy BUILDS this one to refuse a download, so it
    // needs a factory here and not only a client-side `type(...)` — the same omission as
    // `GeocodedPlace` above. Pinned by `MeteredDownloadGateTest`.

    composite(
        interfaceClass = MeteredNetworkRefusedError::class,
        dataSchema = MeteredNetworkRefusedError.Schema,
        dtoFactory = ::MeteredNetworkRefusedErrorDto
    )

    composite(
        interfaceClass = RegionDownloadProgress::class,
        dataSchema = RegionDownloadProgress.Schema,
        dtoFactory = ::RegionDownloadProgressDto
    )
}
