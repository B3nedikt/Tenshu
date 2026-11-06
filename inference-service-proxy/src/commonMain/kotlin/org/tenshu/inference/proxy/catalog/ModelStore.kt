package org.tenshu.inference.proxy.catalog

import org.tenshu.inference.InferenceCapability

/** What this device has, is fetching, or does not have — the three states a UI shows (IN-D10). */
sealed interface ModelInstallState {

    data object Absent : ModelInstallState

    /** [downloadedBytes] of [totalBytes]; `totalBytes` is the catalog's figure for the whole checkpoint. */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelInstallState

    data class Installed(val model: InstalledModelData) : ModelInstallState
}

/** [ModelStore.read]. ⚠ Three answers; none is another's default. */
sealed interface InstalledIndex {
    /** An index (absent counts: nothing was ever installed), and what it holds. */
    data class Records(val models: List<InstalledModelData>) : InstalledIndex

    /** Bytes are kept there and they are not an index this build wrote. Nothing is known to be installed or not. */
    data object NotAnIndex : InstalledIndex

    data class Unreadable(val why: String) : InstalledIndex
}

/**
 * The proxy's canonical model store (IN-D10, inheriting `maps-region-delivery.md` D5/D6).
 *
 * ⚠⚠ **Device-local cache, never realm content.** A model is a public artifact every device can
 * re-fetch; syncing it would push hundreds of megabytes through the relay and charge it to the
 * user's quota for nothing (IN-D4). It belongs in the local-cache tier
 * (`retention-quota-model.md` §2) where it is *visible* and evictable.
 *
 * ⚠ The bytes land only after their sha256 matches the **signed catalog's** figure. A CDN that
 * serves the wrong bytes fails here, not in an inference run three weeks later.
 *
 * ⚠⚠ **A checkpoint is installed whole or not at all.** Every method below is keyed by
 * `(modelId, role)` because the files are fetched separately, and [commitStaged] takes the whole
 * entry because they must become *visible* together: two of three artifacts is a device that
 * answers questions wrongly rather than one that answers fewer of them (§22.2).
 */
interface ModelStore {

    /**
     * What this device holds. ⚠⚠ An empty list means **nothing is installed**, never *could not
     * tell*: a store whose index cannot be read throws ([ModelIndexUnreadableException] from
     * [DefaultModelStore]), here and from [pathOf], and a caller that turns that into an empty
     * list has reported installed models as gone.
     */
    fun installed(): List<InstalledModelData>

    /**
     * Whether what [installed] answered from **is an index**: absent, or records this build
     * wrote. `false` when the bytes kept there were not an index at all, which [installed]
     * answers as no records (by decision) and against which this store sweeps nothing of its
     * own. ⚠ A caller that would *delete* on "no record" asks this first: no record read from
     * bytes that were not an index is not a model that was removed.
     */
    fun holdsAnIndex(): Boolean = true

    /**
     * What this device holds, **as one answer that cannot be half-asked**: the records, or that
     * what is kept there is not an index, or that it could not be read.
     *
     * ⚠⚠ What a caller that *shows* or *decides on* "nothing installed" asks, and not
     * [installed]: [installed] answers bytes that are no index as no records (by decision), so
     * a caller of it alone offers a download over an index nobody can write to, and every
     * caller would have to remember [holdsAnIndex] beside it.
     * ⚠ The default asks the two in turn; [DefaultModelStore] answers both from one look.
     */
    fun read(): InstalledIndex = try {
        val records: List<InstalledModelData> = installed()
        if (holdsAnIndex()) InstalledIndex.Records(records) else InstalledIndex.NotAnIndex
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (unreadable: Exception) {
        InstalledIndex.Unreadable(unreadable.message ?: unreadable.toString())
    }

    /**
     * Lands a whole checkpoint from memory, or `null` when any artifact's bytes do not match its
     * digest. [bytes] is keyed by [ModelArtifactData.role].
     *
     * ⚠ Takes every artifact in memory at once. Fine for a fixture; ⚠⚠ a real ~383 MB checkpoint
     * uses the staged path below and is never materialised whole.
     */
    fun add(entry: ModelEntryData, bytes: Map<String, ByteArray>): InstalledModelData?

    fun remove(modelId: String)

    /**
     * Where [modelId]'s [role] bytes are, or `null` when this device does not hold that file —
     * what a backend loads and what the download path makes true.
     */
    fun pathOf(modelId: String, role: String): String?

    // ─── The staged path, on the interface for the same reason `RegionStore` carries it:
    // the downloader drives it and must not know which implementation it is driving.

    /**
     * Prepares staging for one artifact, **resuming** an earlier attempt whose bytes belong to the
     * same content. Returns the reusable byte count, or `0` after clearing.
     *
     * ⚠⚠ A model id is stable across versions, so `.part` bytes from an earlier attempt may belong
     * to a **different version of the same model**. Appending to them yields a file that fails the
     * digest gate only after the whole remainder has been downloaded — over a metered connection,
     * twice. [expectedSha256] is what makes that distinguishable.
     */
    fun beginStaging(modelId: String, role: String, expectedSha256: String): Long

    /**
     * How many bytes are staged for this artifact **under [expectedSha256]**, or `0`. The
     * question [beginStaging] answers, asked without its side effects: nothing is written and
     * nothing staged for another digest is dropped.
     *
     * For a caller that must know what is staged before it is entitled to change anything (a
     * download that may yet be refused). ⚠ A store that cannot say answers `0`, which costs a
     * caller its credit for staged bytes and never a byte of anyone's staging.
     */
    fun stagedBytes(modelId: String, role: String, expectedSha256: String): Long = 0L

    /** Reads staged bytes back, so a resumed download can rehydrate its running digest. */
    fun readStaged(modelId: String, role: String, offset: Long, length: Int): ByteArray?

    /** Appends a chunk to staging. Never visible to [installed] or [pathOf] until [commitStaged]. */
    fun writeStaged(modelId: String, role: String, offset: Long, bytes: ByteArray)

    /**
     * Promotes **every** staged artifact of [entry] and writes one index record.
     *
     * ⚠⚠ All-or-nothing on purpose: the renames happen first and the index last, so a crash
     * anywhere in between leaves no record and the next attempt re-downloads. There is no ordering
     * of these calls that leaves a *half* checkpoint installed, which is the property
     * `OnnxModelFiles`' filename convention could never give.
     *
     * ⚠ The caller owns the digest checks, because a streamed download computes them as the bytes
     * arrive rather than over a `ByteArray` that never exists.
     *
     * [stagedBytes] is keyed by role; a role it leaves out has the catalog's size.
     *
     * ⚠⚠ **An artifact is promoted only when its staging was begun for the digest [entry] gives
     * it** ([beginStaging], [adoptStaged]: the marker). An artifact that is already installed
     * under that digest, whole, is adopted where it is, and whatever is staged beside it is
     * discarded, whichever digest it was staged for.
     *
     * @throws ModelNotCommittableException when an artifact is neither, and then nothing changed.
     */
    fun commitStaged(entry: ModelEntryData, stagedBytes: Map<String, Long>): InstalledModelData

    /**
     * Discards one artifact's staged bytes **and** its marker.
     *
     * ⚠ For content known to be wrong (the digest gate failed), never for a plain transport failure
     * — that case deliberately leaves the bytes for the next attempt to resume.
     */
    fun abortStaging(modelId: String, role: String)

    /**
     * Makes the file at [fromPath] this artifact's staged bytes, **by moving it**, and records
     * [expectedSha256] as what they are staged for. Returns the staged byte count.
     *
     * For a transport that delivers a whole file somewhere of its own (a background `URLSession`
     * on iOS, `ios-on-device-inference.md` §14.11) rather than streaming chunks through
     * [writeStaged]: copying ~555 MB through [writeStaged] would hold the artifact on disk twice.
     *
     * ⚠⚠ The caller has already verified [fromPath] against the signed catalog's digest and size.
     * Nothing here does, and [commitStaged] does not either.
     * ⚠ Whatever an earlier attempt had staged for this role is replaced.
     * ⚠ Only a store that keeps its bytes in the same file space as [fromPath] can do this; the
     * default refuses, loudly.
     */
    fun adoptStaged(modelId: String, role: String, expectedSha256: String, fromPath: String): Long =
        throw UnsupportedOperationException("This model store cannot adopt a delivered file.")
}

/**
 * How a commit or a removal of the checkpoint is **wrapped** on a platform where something must
 * stop serving the old generation first (`ios-on-device-inference.md` §3.2, readiness's writer 3:
 * the store declares the old archive's files garbage the moment it commits).
 *
 * ⚠ [Direct] everywhere but the iOS owner's graph: Android and desktop resolve the model per call
 * from the store itself, so there is nothing to stand down.
 */
interface ModelStoreChanges {
    /** Runs [change], which commits or removes; answers what it answered. */
    suspend fun <T> changing(change: suspend () -> T): T

    /**
     * The same, **saying which model** [change] commits or removes. ⚠⚠ What every caller that
     * knows the model uses: a wrapper that stands something down for one model (on iOS, the
     * pinned checkpoint's serving) must not stand it down for a change to another.
     */
    suspend fun <T> changing(modelId: String, change: suspend () -> T): T = changing(change)

    object Direct : ModelStoreChanges {
        override suspend fun <T> changing(change: suspend () -> T): T = change()
    }
}

/**
 * An in-memory [ModelStore] — the test double.
 *
 * ⚠ Named for what it is. A `DefaultModelStore` that silently forgot everything on restart would
 * be discovered as "my model downloads again every launch".
 */
class InMemoryModelStore(
    private val digestOf: (ByteArray) -> String,
) : ModelStore {

    private val index = mutableMapOf<String, InstalledModelData>()
    private val blobs = mutableMapOf<String, ByteArray>()
    private val staged = mutableMapOf<String, MutableList<Byte>>()
    private val markers = mutableMapOf<String, String>()

    private fun key(modelId: String, role: String) = "$modelId/$role"

    override fun installed(): List<InstalledModelData> = index.values.toList()

    override fun add(entry: ModelEntryData, bytes: Map<String, ByteArray>): InstalledModelData? {
        // ⚠⚠ The gate. Everything downstream — every derived row, every answer — rests on these
        // bytes being the ones the signed catalog named. ⚠ A missing artifact fails too: a partial
        // checkpoint must not become an index record.
        val landed = entry.artifacts.map { artifact ->
            val actual = bytes[artifact.role] ?: return null
            if (digestOf(actual) != artifact.sha256) return null
            artifact to actual
        }
        landed.forEach { (artifact, actual) -> blobs[key(entry.modelId, artifact.role)] = actual }
        val installed = entry.installedRecord { role, size -> "memory://${entry.modelId}/$role" to size }
        index[entry.modelId] = installed
        return installed
    }

    override fun remove(modelId: String) {
        index.remove(modelId)
        blobs.keys.filter { it.startsWith("$modelId/") }.forEach { blobs.remove(it) }
        staged.keys.toList().filter { it.startsWith("$modelId/") }.forEach { staged.remove(it) }
        markers.keys.toList().filter { it.startsWith("$modelId/") }.forEach { markers.remove(it) }
    }

    override fun pathOf(modelId: String, role: String): String? = index[modelId]?.pathOf(role)

    fun bytesOf(modelId: String, role: String): ByteArray? = blobs[key(modelId, role)]

    // ─── The staged path, in memory. ⚠ It mirrors the real one's *observable ordering* — staged
    // bytes invisible until commit, a marker that decides resume-vs-restart — because that is what
    // the downloader's tests are about. It does not mirror its crash behaviour, which needs a
    // filesystem and is `DefaultModelStoreTest`'s subject.

    override fun beginStaging(modelId: String, role: String, expectedSha256: String): Long {
        val k = key(modelId, role)
        val existing = staged[k]
        if (markers[k] == expectedSha256 && existing != null && existing.isNotEmpty()) {
            return existing.size.toLong()
        }
        staged[k] = mutableListOf()
        markers[k] = expectedSha256
        return 0L
    }

    override fun readStaged(modelId: String, role: String, offset: Long, length: Int): ByteArray? {
        val bytes = staged[key(modelId, role)] ?: return null
        if (offset >= bytes.size) return null
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.subList(offset.toInt(), end).toByteArray()
    }

    override fun writeStaged(modelId: String, role: String, offset: Long, bytes: ByteArray) {
        val target = staged.getOrPut(key(modelId, role)) { mutableListOf() }
        // Truncate-then-append: a write at an offset inside what is already staged replaces the
        // tail, which is what a restart-from-0 after a 200-not-206 response does.
        while (target.size > offset) target.removeAt(target.size - 1)
        target.addAll(bytes.toList())
    }

    override fun commitStaged(
        entry: ModelEntryData,
        stagedBytes: Map<String, Long>,
    ): InstalledModelData {
        entry.artifacts.forEach { artifact ->
            val k = key(entry.modelId, artifact.role)
            blobs[k] = (staged.remove(k) ?: mutableListOf()).toByteArray()
            markers.remove(k)
        }
        val installed = entry.installedRecord { role, _ ->
            "memory://${entry.modelId}/$role" to (stagedBytes[role] ?: 0L)
        }
        index[entry.modelId] = installed
        return installed
    }

    override fun abortStaging(modelId: String, role: String) {
        staged.remove(key(modelId, role))
        markers.remove(key(modelId, role))
    }
}

/**
 * Builds the index record for [this] entry, asking [locate] where each artifact landed and how big
 * it is. Shared so both stores agree on what an installed checkpoint *is*.
 */
internal fun ModelEntryData.installedRecord(
    locate: (role: String, catalogSize: Long) -> Pair<String, Long>,
): InstalledModelData = InstalledModelData(
    modelId = modelId,
    modelVersion = modelVersion,
    artifacts = artifacts.map { artifact ->
        val (path, size) = locate(artifact.role, artifact.byteSize)
        InstalledArtifactData(
            role = artifact.role,
            localPath = path,
            byteSize = size,
            sha256 = artifact.sha256,
        )
    },
    labelSetVersion = labelSetVersion,
    licence = licence,
)

/**
 * What a device holds against what the catalog offers.
 *
 * ⚠⚠ **Downloaded and enabled are two different questions, and this only answers one of them.**
 * IN-D10 requires the UI to say which one an action does: deleting a model must not silently
 * disable the capability, and turning a capability off must not silently delete anything. Keeping
 * consent out of this class is what makes that hard to get wrong — `permittedInference` is the
 * other half, and it lives elsewhere on purpose.
 */
class ModelInventory(
    private val store: ModelStore,
) {

    fun stateOf(entry: ModelEntryData, inFlight: ModelInstallState.Downloading? = null): ModelInstallState {
        val installed = store.installed().firstOrNull { it.modelId == entry.modelId }
        return when {
            // ⚠ An installed copy of an OLDER version reads as installed, not absent: it still
            // works, and presenting it as missing would push the user into a download they do not
            // need to make. Staleness is `ModelProvenance`'s job, and it re-derives rows rather
            // than re-downloading weights.
            installed != null -> ModelInstallState.Installed(installed)
            inFlight != null -> inFlight
            else -> ModelInstallState.Absent
        }
    }

    /** Which capabilities this device could serve if the user consented — the honest half of any
     * Settings line, and deliberately independent of whether they have. */
    fun downloadedCapabilities(catalog: ModelCatalogData): Set<InferenceCapability> {
        val installedIds = store.installed().mapTo(mutableSetOf()) { it.modelId }
        return catalog.models
            .filter { it.modelId in installedIds }
            .flatMapTo(mutableSetOf()) { it.capabilities }
    }
}

/** [ModelStore.commitStaged] found an artifact that is neither installed nor staged for its digest. */
class ModelNotCommittableException(message: String) : Exception(message)
