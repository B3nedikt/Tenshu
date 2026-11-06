package app.tenshu.ui.map

import app.tenshu.vectortile.PmtilesDirectory
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile

/**
 * The **disk-backed range cache** behind the streamed basemap (`maps-region-delivery.md` §7.1 track 1a).
 *
 * One **accumulating sparse `.pmtiles` per region**, not a throwaway file per camera. Every byte range
 * ever fetched for that region (header, directories, metadata, tiles) is written into it and recorded in
 * a sibling manifest; the holes stay unwritten (they read as zero and MapLibre culls them, which is what
 * makes the whole streamed approach work — see [buildStreamedPartial]). Consequences:
 *
 *  - **Re-opening the same snippet fetches nothing** — the ranges are already resident. This also means the
 *    surface renders with no network at all, which the previous per-camera rebuild could not do.
 *  - **A new camera over the same region only fetches what it is missing.** Neighbouring places and a trip
 *    route through the same city share most of their tiles, and *all* cameras share the ~21 KB
 *    header + root-dir + metadata prelude that used to be refetched on every single build.
 *  - MapLibre opens this file directly, so there is no per-camera copy to write either.
 *
 * **Why a manifest is required.** A hole and a legitimately-zero byte are indistinguishable on disk, so
 * "is this range resident?" cannot be answered from the file alone. [manifestFile] records one
 * `start length` line per populated range; it is the cache's index.
 *
 * **Identity / staleness.** The cache directory is keyed by region id **and archive size**
 * ([cacheDirFor]) — region bundles are immutable dated builds (D2), so a rebuild that changes the bytes
 * changes the size in practice. Stale sibling directories for the same region (a previous build) are
 * evicted on open. A rebuild that preserved the exact byte size would not be detected; the robust fix is
 * to key on the catalog's per-region **sha256** once it is threaded through to the render layer (§7.1
 * track 3b covers the same staleness question for resident whole regions).
 *
 * **Concurrency.** Writers are serialised by [lock] (surfaces can render in parallel — a Maps snippet and a
 * Timeline route can both stream). A reader (MapLibre) only ever reads ranges the caller already ensured
 * are resident, and a concurrent writer only appends *other* ranges (disjoint byte intervals), so an
 * in-flight append cannot tear a range being read.
 *
 * **Disk budget.** A sparse file occupies only its populated blocks, so disk use follows what was actually
 * viewed rather than the region size — but that is a consequence of the viewing pattern, not a bound: a
 * heavy panner over a large region had no ceiling at all. [enforceCeiling] trims the whole cache root to
 * [REGION_CACHE_CEILING_BYTES], coldest region first, whenever a cache is opened. See its KDoc for why the
 * cache currently streaming is exempt.
 */
internal class RegionRangeCache private constructor(
    private val dir: File,
    private val totalSize: Long,
) {

    /** The accumulating sparse archive MapLibre opens (`pmtiles://file://…`). */
    val dataFile: File get() = File(dir, DATA_NAME)

    private val manifestFile: File get() = File(dir, MANIFEST_NAME)

    /** Resident ranges, `start → length`. A range is only added after its bytes are durably written. */
    private val resident = mutableMapOf<Long, Long>()

    private fun load() {
        if (!dataFile.exists() || !manifestFile.exists()) {
            // No (or half-created) cache: start clean so a truncated file can never be served.
            dir.listFiles()?.forEach { it.delete() }
            RandomAccessFile(dataFile, "rw").use { it.setLength(totalSize) } // sparse: holes read as zero
            manifestFile.writeText("")
            return
        }
        manifestFile.forEachLine { line ->
            val sep = line.indexOf(' ')
            if (sep > 0) {
                val start = line.substring(0, sep).toLongOrNull()
                val length = line.substring(sep + 1).toLongOrNull()
                if (start != null && length != null) resident[start] = length
            }
        }
    }

    /** True when [range]'s bytes are already on disk (fetched by an earlier camera / an earlier launch). */
    private fun isResident(range: PmtilesDirectory.ByteRange): Boolean =
        resident[range.start] == range.length

    /**
     * Ensures every range in [ranges] is resident, fetching **only the missing ones** through [fetch]
     * (bound to `streamRegionRange`). Returns the fetched byte count, or `null` if any fetch failed — the
     * caller then falls back to the whole-region path, and the cache keeps whatever it did land (partial
     * progress is not lost; the manifest only ever records fully-written ranges).
     */
    suspend fun ensure(
        ranges: List<PmtilesDirectory.ByteRange>,
        fetch: suspend (offset: Long, length: Int) -> ByteArray?,
    ): Long? = lock.withLock {
        val missing = ranges.filterNot(::isResident)
        if (missing.isEmpty()) return@withLock 0L

        var fetched = 0L
        RandomAccessFile(dataFile, "rw").use { sparse ->
            for (r in missing) {
                val bytes = fetch(r.start, r.length.toInt()) ?: return@withLock null
                write(sparse, r.start, bytes)
                fetched += bytes.size
            }
        }
        fetched
    }

    /**
     * Reads one range **through** the cache: served from disk when resident, otherwise fetched once and
     * stored. Used for the header / root directory / leaf-directory section, which the builder must decode
     * *before* it can compute the covering tiles — without this they would be fetched for the decode and
     * then fetched again as part of the covering set, on every launch. Null when the fetch fails.
     */
    suspend fun read(
        range: PmtilesDirectory.ByteRange,
        fetch: suspend (offset: Long, length: Int) -> ByteArray?,
    ): ByteArray? = lock.withLock {
        if (isResident(range)) {
            RandomAccessFile(dataFile, "r").use { f ->
                f.seek(range.start)
                val buf = ByteArray(range.length.toInt())
                f.readFully(buf)
                return@withLock buf
            }
        }
        val bytes = fetch(range.start, range.length.toInt()) ?: return@withLock null
        RandomAccessFile(dataFile, "rw").use { write(it, range.start, bytes) }
        bytes
    }

    /**
     * Writes [bytes] at [start] and records them. The manifest records the **written** length (not the
     * requested one) and is only appended after an `fsync`, so it can never claim bytes the file lacks —
     * a short read near EOF simply leaves that range non-resident and it is re-fetched next time.
     */
    private fun write(sparse: RandomAccessFile, start: Long, bytes: ByteArray) {
        sparse.seek(start)
        sparse.write(bytes)
        sparse.fd.sync()
        manifestFile.appendText("$start ${bytes.size}\n")
        resident[start] = bytes.size.toLong()
    }

    /** Bytes currently resident — what the cache saved us re-fetching, for the `MapStream` log line. */
    fun residentBytes(): Long = resident.values.sum()

    /** Records this cache as used *now*, so the budget's LRU reflects reads as well as fetches. */
    private fun touch() {
        runCatching { File(dir, USED_NAME).writeText(System.currentTimeMillis().toString()) }
    }

    companion object {
        private const val DATA_NAME = "region.pmtiles"
        private const val MANIFEST_NAME = "ranges.txt"
        private const val USED_NAME = "used"
        private const val ROOT = "mapstream"

        /** Serialises writers across surfaces (a Maps snippet and a Timeline route can stream at once). */
        private val lock = Mutex()

        private val open = mutableMapOf<String, RegionRangeCache>()

        /**
         * The cache for [regionId] at [totalSize], under [cacheRoot] — which must be the app's
         * **`cacheDir`**. Created on first use, evicting stale builds of the same region; instances
         * are memoised so parallel surfaces share one manifest in memory.
         *
         * ⚠⚠ `cacheDir`, deliberately, not `filesDir`. These bytes are a re-fetchable derivative of a
         * public archive: losing them costs a re-download, never data. Filing them as app *data* had
         * three consequences, all wrong — the OS could not reclaim them under storage pressure, they did
         * not appear as **Cache** in the system's per-app storage screen, and the user's only "clear
         * this" button (which clears `cacheDir`) did not reach them. Moving them makes the cache
         * visible and clearable with no new UI and no new IPC, which is the whole reason Control cannot
         * manage it: it lives in *each app's* sandbox, not the proxy's.
         *
         * ⚠ The OS may now delete these files at any moment. That is already safe by construction —
         * [load] starts clean whenever the data file or its manifest is missing, and reclamation removes
         * whole files — but it is the reason that branch must never be "optimised" into trusting the
         * manifest alone.
         */
        fun open(
            cacheRoot: File,
            regionId: String,
            totalSize: Long,
        ): RegionRangeCache = synchronized(open) {
            val key = cacheDirFor(regionId, totalSize)
            val cache = open.getOrPut(key) {
                val root = File(cacheRoot, ROOT)
                // Evict other builds of the same region (a new bundle → a new size → a new dir).
                val prefix = "${safe(regionId)}_"
                root.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith(prefix) && it.name != key }
                    ?.forEach {
                        Log.d("MapStream", "evicting stale region cache ${it.name}")
                        it.deleteRecursively()
                    }
                enforceCeiling(root, opening = key)
                RegionRangeCache(File(root, key).apply { mkdirs() }, totalSize).also { it.load() }
            }
            cache.touch()
            cache
        }

        /**
         * Trims the cache root to [REGION_CACHE_CEILING_BYTES], coldest region first
         * (`maps-region-delivery.md` §7.1 track 1a). Without this the cache is bounded only by what the
         * user happened to pan over: sparse files keep a *static* camera cheap, but a heavy panner over a
         * large region has no ceiling at all.
         *
         * ⚠ **Enforced when a cache is opened, not while one is streaming.** A directory with a live
         * instance is never evicted: its in-memory manifest would go on claiming ranges the file no longer
         * has, and `read` would serve the holes as zeroes — silent corruption, where growing past the
         * ceiling until the next open is merely untidy. So a long session that pans across a whole region
         * can still exceed the ceiling; the next open reclaims it. Bounding *within* a session needs the
         * live instance to be trimmable, which means invalidating its manifest under a renderer holding
         * the file open — a bigger change than this budget.
         */
        private fun enforceCeiling(root: File, opening: String) {
            val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
            val evict = regionCacheEvictions(
                dirs.map { dir ->
                    CachedRegionDir(
                        name = dir.name,
                        bytes = residentBytesOf(dir),
                        lastUsedMs = lastUsedOf(dir),
                        // Pinned: a live instance would be left claiming bytes that are gone, and the
                        // directory we are about to open is about to be written to.
                        evictable = dir.name != opening && !open.containsKey(dir.name),
                    )
                },
            )
            if (evict.isEmpty()) return
            // Named, never silent: a cap that quietly deletes reads as "the cache just isn't working".
            Log.d("MapStream", "cache over ${REGION_CACHE_CEILING_BYTES}B — evicting ${evict.joinToString()}")
            evict.forEach { File(root, it).deleteRecursively() }
        }

        /**
         * Resident bytes for a cache directory read straight from its manifest — the same rule [load]
         * applies, keyed by start so a re-fetched range counts once, so the budget and the `MapStream`
         * log line can never disagree.
         *
         * ⚠ Logical bytes, slightly under true disk use (the filesystem allocates whole blocks per
         * populated range). Under-counting is the safe direction for a cache: it evicts a little later
         * than strictly needed rather than deleting what still fits.
         */
        private fun residentBytesOf(dir: File): Long {
            val manifest = File(dir, MANIFEST_NAME)
            if (!manifest.isFile) return 0L
            val ranges = mutableMapOf<Long, Long>()
            runCatching {
                manifest.forEachLine { line ->
                    val sep = line.indexOf(' ')
                    if (sep > 0) {
                        val start = line.substring(0, sep).toLongOrNull()
                        val length = line.substring(sep + 1).toLongOrNull()
                        if (start != null && length != null) ranges[start] = length
                    }
                }
            }
            return ranges.values.sum()
        }

        /** Last-opened stamp, falling back to the directory's mtime; null when neither can be read. */
        private fun lastUsedOf(dir: File): Long? =
            File(dir, USED_NAME).takeIf { it.isFile }?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
                ?: dir.lastModified().takeIf { it > 0L }

        private fun cacheDirFor(regionId: String, totalSize: Long) = "${safe(regionId)}_$totalSize"

        /** Filename-safe region id (ids come from the signed catalog, but never trust them into a path). */
        private fun safe(regionId: String) = regionId.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
    }
}
