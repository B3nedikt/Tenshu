package org.tenshu.mapregion.test

import org.tenshu.mapregion.proxy.InstalledRegionData
import org.tenshu.mapregion.proxy.RegionEntryData
import org.tenshu.mapregion.proxy.RegionStore

/**
 * In-memory [RegionStore] for tests: seed with installed regions, assert on [installed] / [add] /
 * [remove]. Stands in for the `DefaultRegionStore` (`SecureStorageDelegate` + filesystem).
 */
class FakeRegionStore(
    initial: List<InstalledRegionData> = emptyList(),
    /** Optional bundle bytes per regionId, so [readRange] can be exercised in tests. */
    bytes: Map<String, ByteArray> = emptyMap(),
) : RegionStore {

    private val regions: MutableMap<String, InstalledRegionData> =
        initial.associateBy { it.regionId }.toMutableMap()

    private val bytes: MutableMap<String, ByteArray> = bytes.toMutableMap()

    override fun installed(): List<InstalledRegionData> = regions.values.toList()

    override fun add(entry: RegionEntryData, buildDate: String, bytes: ByteArray): InstalledRegionData {
        val record = InstalledRegionData(
            regionId = entry.regionId,
            name = entry.name,
            localPath = "/fake/${entry.regionId}.pmtiles",
            byteSize = bytes.size.toLong(),
            sha256 = entry.sha256,
            buildDate = buildDate,
        )
        regions[entry.regionId] = record
        this.bytes[entry.regionId] = bytes
        return record
    }

    override fun remove(regionId: String): Boolean {
        bytes.remove(regionId)
        return regions.remove(regionId) != null
    }

    // ── streamed landing (§7.1 track 3e) ─────────────────────────────────────────────────────────
    // Modelled faithfully, not stubbed: staged bytes are kept in a SEPARATE map, so a test can assert
    // the thing that matters — that a half-written or failed download is invisible to installed() /
    // readRange(), and that the canonical bundle only changes at commitStaged.

    private val staged: MutableMap<String, MutableList<Byte>> = mutableMapOf()

    /**
     * The digest each region's staged bytes were fetched for — the real store keeps this beside the
     * `.part` file, and it is what makes a resume provably about the same content. Modelled rather than
     * stubbed, because "resumes only across a matching digest" is precisely what needs testing.
     */
    private val stagedFor: MutableMap<String, String> = mutableMapOf()

    /** The chunk sizes handed to [writeStaged], so a test can prove the body was streamed, not buffered. */
    val stagedChunkSizes: MutableList<Int> = mutableListOf()

    override fun beginStaging(regionId: String, expectedSha256: String): Long {
        val existing = staged[regionId]
        if (existing != null && existing.isNotEmpty() && stagedFor[regionId] == expectedSha256) {
            return existing.size.toLong()
        }
        staged[regionId] = mutableListOf()
        stagedFor[regionId] = expectedSha256
        return 0L
    }

    override fun readStaged(regionId: String, offset: Long, length: Int): ByteArray? {
        val sink = staged[regionId] ?: return null
        if (offset >= sink.size) return ByteArray(0)
        val end = minOf(sink.size.toLong(), offset + length).toInt()
        return sink.subList(offset.toInt(), end).toByteArray()
    }

    /** Pre-stages [bytes] as if an earlier attempt had left them, for [expectedSha256]'s content. */
    fun seedStaged(regionId: String, bytes: ByteArray, expectedSha256: String) {
        staged[regionId] = bytes.toMutableList()
        stagedFor[regionId] = expectedSha256
    }

    /** Bytes currently staged for [regionId] — what a resume would pick up from. */
    fun stagedSize(regionId: String): Long = staged[regionId]?.size?.toLong() ?: 0L

    /** The committed bundle's bytes, so a test can prove a resumed file is byte-identical to a fresh one. */
    fun bytesOf(regionId: String): ByteArray? = bytes[regionId]

    override fun writeStaged(regionId: String, offset: Long, bytes: ByteArray) {
        val sink = staged.getOrPut(regionId) { mutableListOf() }
        require(offset == sink.size.toLong()) {
            "staged writes must be sequential (offset $offset, staged ${sink.size})"
        }
        stagedChunkSizes += bytes.size
        sink += bytes.toList()
    }

    override fun commitStaged(
        entry: RegionEntryData,
        buildDate: String,
        byteSize: Long,
    ): InstalledRegionData {
        val sink = staged.remove(entry.regionId) ?: ByteArray(0).toList().toMutableList()
        stagedFor.remove(entry.regionId)
        val record = InstalledRegionData(
            regionId = entry.regionId,
            name = entry.name,
            localPath = "/fake/${entry.regionId}.pmtiles",
            byteSize = byteSize,
            sha256 = entry.sha256,
            buildDate = buildDate,
        )
        regions[entry.regionId] = record
        bytes[entry.regionId] = sink.toByteArray()
        return record
    }

    override fun abortStaging(regionId: String) {
        staged.remove(regionId)
        stagedFor.remove(regionId)
    }

    /** Whether a staging file is currently lying around (a failed download must leave none). */
    fun hasStaged(regionId: String): Boolean = staged.containsKey(regionId)

    override fun readRange(regionId: String, offset: Long, length: Int): ByteArray? {
        val all = bytes[regionId] ?: return null
        if (offset >= all.size) return ByteArray(0)
        val end = minOf(all.size.toLong(), offset + length).toInt()
        return all.copyOfRange(offset.toInt(), end)
    }
}
