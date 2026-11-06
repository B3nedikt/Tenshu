package app.tenshu.control.ui.screens.offlinemaps

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.core.fold
import org.tenshu.core.mapregion.InstalledRegion
import org.tenshu.core.mapregion.MeteredNetworkRefusedError
import org.tenshu.core.mapregion.RegionEntry
import org.tenshu.core.mapregion.catalog
import org.tenshu.core.mapregion.deleteRegion
import org.tenshu.core.mapregion.downloadProgress
import org.tenshu.core.mapregion.downloadRegion
import org.tenshu.core.mapregion.installed
import org.tenshu.mapregion.client.mapRegionService

/** One catalog region with its local install state + size, for the Control "Offline maps" list. */
data class OfflineMapRegion(
    val regionId: String,
    val name: String,
    val byteSize: Long,
    val installed: Boolean,
    /**
     * The resident copy is an **older build** than the catalog's (`maps-region-delivery.md` §7.1 track 3b):
     * it is installed, but its bytes' `sha256` differs from the one the signed catalog now advertises, so
     * re-downloading gets fresher map data.
     *
     * ⚠ Keyed on the **per-region sha256**, deliberately *not* on the catalog-wide `buildDate`: every new
     * tiles build bumps `buildDate` for every region, so a `buildDate` comparison would flag *all* resident
     * regions as stale on any build — even the ones whose bytes are byte-identical. Only a region whose
     * content actually changed should ask the user for a multi-hundred-MB re-download.
     */
    val updateAvailable: Boolean = false,
    /**
     * The catalog's `west,south,east,north` box, which [regionGroupOf] derives the list's grouping from.
     *
     * ⚠ Null for a row built from the **install index**, which carries no coverage — so a catalog-unavailable
     * list cannot be grouped at all, and deliberately isn't (see [groupRegions]).
     */
    val coverage: String? = null,
)

/**
 * What the screen could actually find out, so it never has to guess.
 *
 * ⚠ [catalogUnavailable] exists because the two failure modes look identical in a plain
 * `List<OfflineMapRegion>` and are not: an **empty catalog** means there is nothing to download yet,
 * whereas an **unreadable catalog** means we do not know. Collapsing them into `emptyList()` made a
 * device with no network report the cheerful "No map regions are available yet." — and, worse, hid the
 * regions it *had already downloaded*, which are precisely the ones that still work offline.
 */
data class OfflineMapsSnapshot(
    val regions: List<OfflineMapRegion>,
    /**
     * The signed catalog could not be read — the device is offline, the tile host is unreachable, or
     * the catalog failed its Ed25519 check against the pinned key. [regions] is then the **local
     * install index alone**: what is resident still shows (and still works), but nothing new is
     * listed and no update check has run.
     */
    val catalogUnavailable: Boolean = false,
)

/**
 * What a download attempt did.
 *
 * ⚠ [NeedsMeteredConsent] is a *third* outcome, not a flavour of failure: nothing is broken, the user
 * simply has not been asked whether to spend mobile data on a several-hundred-megabyte file. Folding it
 * into [Failed] would show "check your connection and try again" to someone whose connection is fine,
 * and offer no way through.
 */
enum class RegionDownloadOutcome { Landed, Failed, NeedsMeteredConsent }

/**
 * The Control-side seam onto offline basemap regions (`maps-region-delivery.md` §7 phase 6 /
 * D5-amended: Control is the shared region-store owner, so the canonical "Offline maps" management
 * lives here — a data-availability surface, distinct from the account/entitlement Services surface).
 * The UI depends only on this interface; the client-backed implementation reads Control's **own**
 * in-process `MapRegionService`.
 */
interface OfflineMapsRepository {
    /** The catalog regions joined with local install state (installed regions show a delete). */
    suspend fun regions(): OfflineMapsSnapshot

    /**
     * "Make available offline": downloads the whole bundle.
     *
     * [allowMetered] is the user's explicit "yes, use mobile data" — absent, the proxy refuses on a
     * metered network and this returns [RegionDownloadOutcome.NeedsMeteredConsent] so the caller can ask.
     */
    suspend fun download(regionId: String, allowMetered: Boolean = false): RegionDownloadOutcome

    /**
     * A push stream of [regionId]'s downloaded bytes so far, while a [download] is in flight — the
     * proxy ticks once per streamed chunk it writes to the staging file, so a stalled fetch shows a
     * stalled bar. The flow **completes** when the download does; success or failure is [download]'s
     * own return, not a phase on this stream.
     *
     * Bytes rather than a fraction, matching `RegionTerminus.downloadProgress`: the total is already
     * known from the catalog entry, and bytes are what a caller totalling several regions needs.
     */
    fun downloadProgress(regionId: String): Flow<Long>

    /** Evicts an installed region from the canonical store (frees the disk + the Free-tier slot). */
    suspend fun delete(regionId: String)
}

/**
 * [OfflineMapsRepository] over Control's in-process [TenshuClient] — the same client Control's other
 * screens read (`ViewModelsModule.storageStatusModelFlow`). `MapRegionService` is already registered
 * on it (Control hosts the proxy), so this is a plain in-process call, no IPC.
 */
class ClientOfflineMapsRepository(
    private val client: TenshuClient,
) : OfflineMapsRepository {

    override suspend fun regions(): OfflineMapsSnapshot = offlineMapsSnapshot(
        // A local index read (the store's own JSON), so a failure here is not the offline case the
        // catalog fallback handles — it degrades to "nothing is installed", as it always has.
        installed = client.mapRegionService.installed().getOrNull()?.toList().orEmpty().map { it.facts() },
        // null, deliberately not emptyList(): the whole point is that "no catalog" and "an empty
        // catalog" are different answers.
        catalog = client.mapRegionService.catalog().getOrNull()?.regions?.toList()?.map { it.facts() },
    )

    override suspend fun download(regionId: String, allowMetered: Boolean): RegionDownloadOutcome =
        client.mapRegionService.downloadRegion(regionId, allowMetered).fold(
            onSuccess = { RegionDownloadOutcome.Landed },
            // Typed, so "you are on mobile data" is distinguishable from "the network died" — which is
            // the entire reason the proxy refuses with a `MeteredNetworkRefusedError` rather than a
            // generic one.
            onFailure = { error ->
                if (error is MeteredNetworkRefusedError) RegionDownloadOutcome.NeedsMeteredConsent
                else RegionDownloadOutcome.Failed
            },
        )

    override fun downloadProgress(regionId: String): Flow<Long> =
        client.mapRegionService.downloadProgress(regionId).map { it.writtenBytes.get() }

    override suspend fun delete(regionId: String) {
        client.mapRegionService.deleteRegion(regionId)
    }
}

/**
 * The plain-data projection of a region row from either source, so the join rule below stays pure and
 * testable without a client — the same reason [offlineMapRegion] is extracted.
 */
internal data class RegionFacts(
    val regionId: String,
    val name: String,
    val byteSize: Long,
    val sha256: String,
    /** The catalog's `west,south,east,north` box; ⚠ null from the install index, which has no such field. */
    val coverage: String? = null,
)

private fun InstalledRegion.facts() =
    RegionFacts(regionId.get(), name.get(), byteSize.get(), sha256.get(), coverage = null)

private fun RegionEntry.facts() =
    RegionFacts(regionId.get(), name.get(), byteSize.get(), sha256.get(), coverage = coverage.get())

/**
 * Joins the signed catalog with the local install index.
 *
 * ⚠ [catalog] is **nullable rather than possibly-empty**, and that is the whole fix: a null catalog
 * means "we could not look", an empty one means "there is nothing to download". Folding the first into
 * the second is what made an offline device claim `No map regions are available yet.` *and* drop the
 * regions it had already downloaded — the ones that still work precisely because there is no network.
 *
 * With no catalog, a resident region is reported installed with `updateAvailable = false`: staleness is
 * a *comparison* against the catalog, so with no catalog there is no claim to make, and inventing one
 * would offer a multi-hundred-MB re-download on no evidence.
 */
internal fun offlineMapsSnapshot(
    installed: List<RegionFacts>,
    catalog: List<RegionFacts>?,
): OfflineMapsSnapshot {
    if (catalog == null) {
        return OfflineMapsSnapshot(
            regions = installed.map {
                OfflineMapRegion(
                    regionId = it.regionId,
                    name = it.name,
                    byteSize = it.byteSize,
                    installed = true,
                )
            },
            catalogUnavailable = true,
        )
    }

    // regionId → the sha256 of the bytes actually resident, so a resident region whose content has
    // moved on in the catalog can offer an update (track 3b).
    val residentShas = installed.associate { it.regionId to it.sha256 }
    return OfflineMapsSnapshot(
        regions = catalog.map { entry ->
            offlineMapRegion(
                regionId = entry.regionId,
                name = entry.name,
                byteSize = entry.byteSize,
                catalogSha256 = entry.sha256,
                residentSha256 = residentShas[entry.regionId],
                coverage = entry.coverage,
            )
        },
    )
}

/**
 * Joins one catalog region with the local install state — pure, so the **staleness rule** (track 3b) is
 * testable without a client.
 *
 * [residentSha256] is the sha256 of the bytes actually on disk (null ⇒ not installed). A resident region
 * whose sha differs from the catalog's is an **older build**: re-downloading gets fresher map data.
 *
 * Compared on the **per-region sha256**, deliberately not the catalog-wide `buildDate` — every tiles build
 * bumps `buildDate` for every region, so a `buildDate` comparison would flag *all* resident regions as
 * stale on any build, even ones whose bytes never changed, and ask the user for pointless
 * multi-hundred-MB re-downloads.
 */
internal fun offlineMapRegion(
    regionId: String,
    name: String,
    byteSize: Long,
    catalogSha256: String,
    residentSha256: String?,
    coverage: String? = null,
): OfflineMapRegion = OfflineMapRegion(
    regionId = regionId,
    name = name,
    byteSize = byteSize,
    installed = residentSha256 != null,
    updateAvailable = residentSha256 != null && residentSha256 != catalogSha256,
    coverage = coverage,
)
