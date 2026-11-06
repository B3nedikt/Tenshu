package org.tenshu.mapregion.client

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.core.mapregion.Catalog
import org.tenshu.core.mapregion.DeleteRegion
import org.tenshu.core.mapregion.DownloadRegion
import org.tenshu.core.mapregion.Installed
import org.tenshu.core.mapregion.InstalledRegion
import org.tenshu.core.mapregion.MapRegionService
import org.tenshu.core.mapregion.ReadRegionRange
import org.tenshu.core.mapregion.RailLineGeometry
import org.tenshu.core.mapregion.RailLinesIn
import org.tenshu.core.mapregion.RegionCatalog
import org.tenshu.core.mapregion.RegionDownloadProgress
import org.tenshu.core.mapregion.RegionEntry
import org.tenshu.core.mapregion.DownloadProgress
import org.tenshu.core.mapregion.GeocodedPlace
import org.tenshu.core.mapregion.LinesServing
import org.tenshu.core.mapregion.NearestTransitStop
import org.tenshu.core.mapregion.ReverseGeocodePlace
import org.tenshu.core.mapregion.ReverseGeocodeCandidates
import org.tenshu.core.mapregion.StreamRegionRange
import org.tenshu.mapregion.client.impl.CatalogRo
import org.tenshu.mapregion.client.impl.DeleteRegionRo
import org.tenshu.mapregion.client.impl.DownloadRegionRo
import org.tenshu.mapregion.client.impl.InstalledRo
import org.tenshu.mapregion.client.impl.MapRegionServiceRo
import org.tenshu.mapregion.client.impl.ReadRegionRangeRo
import org.tenshu.mapregion.client.impl.DownloadProgressRo
import org.tenshu.mapregion.client.impl.LinesServingRo
import org.tenshu.mapregion.client.impl.NearestTransitStopRo
import org.tenshu.mapregion.client.impl.ReverseGeocodeRo
import org.tenshu.mapregion.client.impl.ReverseGeocodeCandidatesRo
import org.tenshu.mapregion.client.impl.RailLinesInRo
import org.tenshu.mapregion.client.impl.StreamRegionRangeRo

val mapRegionServiceClientModule = tModule {

    type(
        interfaceClass = RegionEntry::class,
        dataSchema = RegionEntry.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, RegionEntry.Schema), RegionEntry {
                override val schema: CompositeSchema get() = RegionEntry.Schema
            }
        }
    )

    type(
        interfaceClass = RegionCatalog::class,
        dataSchema = RegionCatalog.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, RegionCatalog.Schema), RegionCatalog {
                override val schema: CompositeSchema get() = RegionCatalog.Schema
            }
        }
    )

    type(
        interfaceClass = InstalledRegion::class,
        dataSchema = InstalledRegion.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, InstalledRegion.Schema), InstalledRegion {
                override val schema: CompositeSchema get() = InstalledRegion.Schema
            }
        }
    )

    type(
        interfaceClass = RailLineGeometry::class,
        dataSchema = RailLineGeometry.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, RailLineGeometry.Schema), RailLineGeometry {
                override val schema: CompositeSchema get() = RailLineGeometry.Schema
            }
        }
    )

    // ⚠⚠ Declaring the operation is not enough for a composite return: the client dispatches through
    // *this* registration's `roFactory`, and without it the read fails as an opaque `error/internal`
    // rather than anything that names the cause. Same trap as `GetWorkoutStreamsRo`
    // (`bounded-store-cache.md` as-built) and `RailLineGeometry` directly above.
    type(
        interfaceClass = GeocodedPlace::class,
        dataSchema = GeocodedPlace.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, GeocodedPlace.Schema), GeocodedPlace {
                override val schema: CompositeSchema get() = GeocodedPlace.Schema
            }
        }
    )

    type(
        interfaceClass = RegionDownloadProgress::class,
        dataSchema = RegionDownloadProgress.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, RegionDownloadProgress.Schema), RegionDownloadProgress {
                override val schema: CompositeSchema get() = RegionDownloadProgress.Schema
            }
        }
    )

    operation(
        interfaceClass = RailLinesIn::class,
        protocolSchema = RailLinesIn.Schema,
        roFactory = ::RailLinesInRo
    )

    operation(
        interfaceClass = Catalog::class,
        protocolSchema = Catalog.Schema,
        roFactory = ::CatalogRo
    )

    operation(
        interfaceClass = Installed::class,
        protocolSchema = Installed.Schema,
        roFactory = ::InstalledRo
    )

    operation(
        interfaceClass = DeleteRegion::class,
        protocolSchema = DeleteRegion.Schema,
        roFactory = ::DeleteRegionRo
    )

    operation(
        interfaceClass = DownloadRegion::class,
        protocolSchema = DownloadRegion.Schema,
        roFactory = ::DownloadRegionRo
    )

    operation(
        interfaceClass = ReadRegionRange::class,
        protocolSchema = ReadRegionRange.Schema,
        roFactory = ::ReadRegionRangeRo
    )

    operation(
        interfaceClass = StreamRegionRange::class,
        protocolSchema = StreamRegionRange.Schema,
        roFactory = ::StreamRegionRangeRo
    )

    operation(
        interfaceClass = NearestTransitStop::class,
        protocolSchema = NearestTransitStop.Schema,
        roFactory = { chain, ctx -> NearestTransitStopRo(chain, ctx) }
    )

    operation(
        interfaceClass = LinesServing::class,
        protocolSchema = LinesServing.Schema,
        roFactory = { chain, ctx -> LinesServingRo(chain, ctx) }
    )

    operation(
        interfaceClass = ReverseGeocodePlace::class,
        protocolSchema = ReverseGeocodePlace.Schema,
        roFactory = ::ReverseGeocodeRo
    )

    operation(
        interfaceClass = ReverseGeocodeCandidates::class,
        protocolSchema = ReverseGeocodeCandidates.Schema,
        roFactory = ::ReverseGeocodeCandidatesRo
    )

    operation(
        interfaceClass = DownloadProgress::class,
        protocolSchema = DownloadProgress.Schema,
        roFactory = ::DownloadProgressRo
    )

    type(
        interfaceClass = MapRegionService::class,
        dataSchema = MapRegionService.Schema,
        roFactory = ::MapRegionServiceRo
    )
}
