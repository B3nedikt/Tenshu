package app.tenshu.maps.data

import org.tenshu.mapregion.proxy.InstalledRegionData
import org.tenshu.mapregion.proxy.RegionEntryData
import org.tenshu.mapregion.proxy.RegionStore

/**
 * A process-lifetime in-memory [RegionStore] for local bring-up — stands in for the on-device
 * canonical store (`DefaultRegionStore`) until the app-graph DI wiring lands. Seedable so the
 * picker shows a realistic mix of installed vs. available regions.
 */
class InMemoryRegionStore(
    seed: List<InstalledRegionData> = emptyList(),
) : RegionStore {

    private val regions: MutableMap<String, InstalledRegionData> =
        seed.associateBy { it.regionId }.toMutableMap()

    /** Bundle bytes kept in-memory so [readRange] serves the dev render-hop (no real filesystem). */
    private val bytes: MutableMap<String, ByteArray> = mutableMapOf()

    override fun installed(): List<InstalledRegionData> = regions.values.toList()

    override fun add(entry: RegionEntryData, buildDate: String, bytes: ByteArray): InstalledRegionData {
        this.bytes[entry.regionId] = bytes
        return install(
            InstalledRegionData(
                regionId = entry.regionId,
                name = entry.name,
                localPath = "/regions/${entry.regionId}.pmtiles",
                byteSize = bytes.size.toLong(),
                sha256 = entry.sha256,
                buildDate = buildDate,
            ),
        )
    }

    override fun remove(regionId: String): Boolean {
        bytes.remove(regionId)
        return regions.remove(regionId) != null
    }

    // The streamed-landing surface (§7.1 track 3e). Held apart from [bytes] on purpose: a staged (or
    // failed) download must be invisible to [installed] / [readRange], exactly as on the real store.
    private val staged: MutableMap<String, ByteArray> = mutableMapOf()

    /** The digest each staged buffer was fetched for — the resume marker the real store keeps on disk. */
    private val stagedFor: MutableMap<String, String> = mutableMapOf()

    override fun beginStaging(regionId: String, expectedSha256: String): Long {
        val existing = staged[regionId]
        // Resume only across a matching digest: bundles are immutable dated builds, so bytes staged
        // before a rebuild belong to different content (see RegionStore.beginStaging).
        if (existing != null && existing.isNotEmpty() && stagedFor[regionId] == expectedSha256) {
            return existing.size.toLong()
        }
        staged[regionId] = ByteArray(0)
        stagedFor[regionId] = expectedSha256
        return 0L
    }

    override fun readStaged(regionId: String, offset: Long, length: Int): ByteArray? {
        val sink = staged[regionId] ?: return null
        if (offset >= sink.size) return ByteArray(0)
        val end = minOf(sink.size.toLong(), offset + length).toInt()
        return sink.copyOfRange(offset.toInt(), end)
    }

    override fun writeStaged(regionId: String, offset: Long, bytes: ByteArray) {
        val existing = staged[regionId] ?: ByteArray(0)
        val end = (offset + bytes.size).toInt()
        val grown = if (end > existing.size) existing.copyOf(end) else existing
        bytes.copyInto(grown, destinationOffset = offset.toInt())
        staged[regionId] = grown
    }

    override fun commitStaged(
        entry: RegionEntryData,
        buildDate: String,
        byteSize: Long,
    ): InstalledRegionData {
        val landed = staged.remove(entry.regionId) ?: ByteArray(0)
        stagedFor.remove(entry.regionId)
        bytes[entry.regionId] = landed
        return install(
            InstalledRegionData(
                regionId = entry.regionId,
                name = entry.name,
                localPath = "/regions/${entry.regionId}.pmtiles",
                byteSize = byteSize,
                sha256 = entry.sha256,
                buildDate = buildDate,
            ),
        )
    }

    override fun abortStaging(regionId: String) {
        staged.remove(regionId)
        stagedFor.remove(regionId)
    }

    override fun readRange(regionId: String, offset: Long, length: Int): ByteArray? {
        val all = bytes[regionId] ?: return null
        if (offset >= all.size) return ByteArray(0)
        val end = minOf(all.size.toLong(), offset + length).toInt()
        return all.copyOfRange(offset.toInt(), end)
    }

    /**
     * Installs a pre-built record directly (used by the dev [MockRegionDownloader], which has no
     * real bytes to land but wants to preserve the catalog's advertised size).
     */
    fun install(record: InstalledRegionData): InstalledRegionData {
        regions[record.regionId] = record
        return record
    }
}
