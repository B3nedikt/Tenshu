package org.tenshu.inference.proxy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The steps of one tower's prepare, in order (`ios-on-device-inference.md` §3.4). */
enum class PrepareStep { CHECK_SPACE, UNPACK, COMPILE, MOVE, DELETE_PACKAGE, FIRST_OPEN, MARK_READY }

/** A ready marker, as it was read. ⚠ Three answers; none is folded into another. */
sealed interface MarkerRead {
    data object Absent : MarkerRead

    /** [digest] is the archive digest the marker names. */
    data class Held(val digest: String?) : MarkerRead

    data class Unreadable(val why: String) : MarkerRead
}

/**
 * What the compiled cache does to the disk and asks of Core ML, **one primitive each**, with no
 * order and no cleanup of its own: those are [CompiledCache]'s, in common code, so the rule that
 * is tested is the rule that ships and not a second copy of it in a fake.
 *
 * ⚠⚠ **A primitive that did not do its work throws.** None answers a success flag: there is no
 * result to forget to look at. A delete throws [CompiledCacheNotClearedException] unless the
 * thing **is gone now** (a re-stat, never that a call was made); a move, a write, an unpack, a
 * compile and an open throw what went wrong. It was a `Boolean` for the deletes, and the one
 * call that ignored it went on to mark a tower ready beside the package it had not deleted.
 */
interface CompiledFiles {
    fun marker(archiveDigest: String): MarkerRead

    /** Whether the compiled directory of [role] is there. */
    fun towerThere(role: String, archiveDigest: String): Boolean

    fun towerPath(role: String, archiveDigest: String): String

    /** The cache's name for [archiveDigest]'s directory. @throws IllegalArgumentException for text that is no digest. */
    fun tagOf(archiveDigest: String): String

    /** Why there is no room to prepare [archivePath], or `null`. */
    fun spaceRefusal(archivePath: String): SpaceRefusal?

    /** Unpacks into the staging area of [role]; answers where the package is. */
    fun unpack(archivePath: String, role: String): String

    /** Compiles; answers where Core ML put its output, which is **outside** the cache. */
    fun compile(packagePath: String): String

    fun move(temporary: String, role: String, archiveDigest: String)

    /** Deletes Core ML's output at [temporary]. @throws CompiledCacheNotClearedException */
    fun dropTemporary(temporary: String)

    fun firstOpen(role: String, archiveDigest: String)

    fun writeMarker(archiveDigest: String)

    /** @throws CompiledCacheNotClearedException */
    fun deleteMarker(archiveDigest: String)

    /** The whole directory of [archiveDigest]. @throws CompiledCacheNotClearedException */
    fun deleteTowers(archiveDigest: String)

    /** @throws CompiledCacheNotClearedException */
    fun deleteStaging(role: String)

    /** The cache's entries by name; empty when it has no directory; `null` when it could not be listed. */
    fun entries(): List<String>?

    /** One entry of the cache, a tower's directory marker first. @throws CompiledCacheNotClearedException */
    fun deleteEntry(name: String)
}

/**
 * What brackets a tower's compile (unpack, compile, move) and its first open (§14.5): the
 * readiness's [AiReadiness.attempting], so a load the process does not survive is counted at the
 * next launch. ⚠ It runs [load] or throws; a throw before [load] means nothing was loaded.
 */
fun interface LoadRecording {
    suspend fun around(kind: AttemptKind, role: String, archiveDigest: String, load: () -> Unit)

    companion object {
        /** ⚠ For the latency harness and the cache's own tests alone: nothing that ships loads unrecorded. */
        val UNRECORDED: LoadRecording = LoadRecording { _, _, _, load -> load() }
    }
}

/** A delete that did not delete. Nothing is compiled, and nothing reported as done, over it. */
class CompiledCacheNotClearedException(message: String) : Exception(message)

/**
 * Why a tower is not prepared for want of room, **as values** (§14.18, M8): a screen words the
 * two figures itself, in its own language. [why] is for a log.
 */
sealed interface SpaceRefusal {
    val why: String

    /** There is no archive at the path to measure. */
    data class NoArchive(val archivePath: String) : SpaceRefusal {
        override val why: String get() = "no archive at $archivePath"
    }

    data class Insufficient(val archivePath: String, val neededBytes: Long, val freeBytes: Long) : SpaceRefusal {
        override val why: String get() = "preparing $archivePath needs about $neededBytes bytes free, the volume has $freeBytes"
    }
}

/** [SpaceRefusal.Insufficient], thrown before anything of the tower is written. */
class CompiledSpaceInsufficientException(val neededBytes: Long, val freeBytes: Long, message: String) : Exception(message)

/**
 * The compiled cache's rules over [CompiledFiles] (§3.4, §14.17): what [ModelPreparer] drives, on
 * iOS and in every test.
 *
 * ⚠⚠ **The marker is written last and removed first.** Every step before it can be interrupted
 * and leave a directory that looks complete; so no marker means not ready, and a tower is
 * *discarded* marker first: a process killed half-way through a delete leaves a directory with
 * no marker, never a marker beside a gutted directory.
 *
 * ⚠⚠ **A throw at any step leaves nothing of the tower**, the question asked before each step
 * included ([prepare]'s `proceed` is a throw point seven times): the staging area, the digest's
 * directory, **and Core ML's own output**, which is outside both and as large as the model.
 */
class CompiledCache(private val files: CompiledFiles) : CompiledModels {

    private val preparing = Mutex()

    override fun tower(role: String, archiveDigest: String): CompiledTower =
        when (val marker: MarkerRead = files.marker(archiveDigest)) {
            is MarkerRead.Unreadable -> CompiledTower.Unreadable(marker.why)
            MarkerRead.Absent -> CompiledTower.Absent
            is MarkerRead.Held ->
                if (marker.digest == archiveDigest && files.towerThere(role, archiveDigest)) {
                    CompiledTower.Ready(files.towerPath(role, archiveDigest))
                } else {
                    CompiledTower.Absent
                }
        }

    override suspend fun prepare(role: String, archivePath: String, archiveDigest: String, proceed: () -> Unit, loads: LoadRecording) =
        prepare(role, archivePath, archiveDigest, proceed, onStep = {}, loads = loads)

    /**
     * @param onStep called as each step starts, after [proceed] let it. The latency harness times them.
     * @param loads around the compile (unpack to move) and around [PrepareStep.FIRST_OPEN].
     */
    suspend fun prepare(
        role: String,
        archivePath: String,
        archiveDigest: String,
        proceed: () -> Unit,
        onStep: (PrepareStep) -> Unit,
        loads: LoadRecording = LoadRecording.UNRECORDED,
    ) {
        preparing.withLock {
            when (val held: CompiledTower = tower(role, archiveDigest)) {
                is CompiledTower.Ready -> return@withLock
                // ⚠ Nothing is removed and compiled over a marker that could not be read.
                is CompiledTower.Unreadable -> throw CompiledCacheNotClearedException(held.why)
                CompiledTower.Absent -> Unit
            }
            // What an interrupted prepare left has no marker and is not trusted. ⚠ And is GONE
            // before anything is written beside it, or this is not started.
            clear(role, archiveDigest, temporary = null)?.let { throw CompiledCacheNotClearedException(it) }
            // ⚠ From here every primitive either did its work or threw: nothing below looks at a result.
            // ⚠⚠ Core ML's output, from the moment it exists to the moment it is moved: the one
            // thing of this tower that is in neither directory the cleanup knows by name.
            var temporary: String? = null
            fun step(step: PrepareStep) {
                proceed()
                onStep(step)
            }
            try {
                step(PrepareStep.CHECK_SPACE)
                when (val refusal: SpaceRefusal? = files.spaceRefusal(archivePath)) {
                    null -> Unit
                    is SpaceRefusal.NoArchive -> throw CompiledCacheNotClearedException(refusal.why)
                    is SpaceRefusal.Insufficient ->
                        throw CompiledSpaceInsufficientException(refusal.neededBytes, refusal.freeBytes, refusal.why)
                }
                // ⚠⚠ The two loads a phone may not survive, each recorded on disk before it and
                // counted by the next process when it is still there (§14.5): the compile, then
                // the first open.
                loads.around(AttemptKind.COMPILE, role, archiveDigest) {
                    step(PrepareStep.UNPACK)
                    val packagePath: String = files.unpack(archivePath, role)
                    step(PrepareStep.COMPILE)
                    val output: String = files.compile(packagePath)
                    temporary = output
                    step(PrepareStep.MOVE)
                    files.move(output, role, archiveDigest)
                    temporary = null
                }
                step(PrepareStep.DELETE_PACKAGE)
                files.deleteStaging(role)
                step(PrepareStep.FIRST_OPEN)
                loads.around(AttemptKind.FIRST_OPEN, role, archiveDigest) { files.firstOpen(role, archiveDigest) }
                // ⚠⚠ LAST. Everything above this line can stop half-way and look finished.
                step(PrepareStep.MARK_READY)
                files.writeMarker(archiveDigest)
            } catch (failure: Throwable) {
                // ⚠⚠ What the cleanup itself could not delete is SAID, with what went wrong: the
                // caller compiles nothing beside it, and the next call's sweep meets it again.
                val left: String = clear(role, archiveDigest, temporary) ?: throw failure
                throw CompiledCacheNotClearedException("${failure.message ?: failure}; and $left").also { it.addSuppressed(failure) }
            }
        }
    }

    /**
     * Removes everything of one tower's prepare, **each part whatever became of the others**.
     * @return what could not be removed, or `null`.
     */
    private fun clear(role: String, archiveDigest: String, temporary: String?): String? {
        val left = ArrayList<String>()
        fun attempt(delete: () -> Unit) {
            try {
                delete()
            } catch (notCleared: CompiledCacheNotClearedException) {
                left += notCleared.message.orEmpty()
            }
        }
        if (temporary != null) attempt { files.dropTemporary(temporary) }
        attempt { files.deleteStaging(role) }
        attempt { discard(archiveDigest) }
        return left.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** ⚠⚠ Marker first: a marker that could not be removed leaves the directory untouched. */
    override fun discard(archiveDigest: String) {
        files.deleteMarker(archiveDigest)
        files.deleteTowers(archiveDigest)
    }

    override fun sweep(liveDigests: Set<String>): CompiledSweep {
        // ⚠ A digest the cache cannot turn into a name names no directory: there is nothing of
        // it to keep, and it does not stop the sweep (the caller hands every installed
        // artifact's digest, of every model, and not all of them are towers).
        val keep: Set<String> = liveDigests.mapNotNullTo(mutableSetOf()) { digest ->
            try {
                files.tagOf(digest)
            } catch (notADigest: IllegalArgumentException) {
                null
            }
        }
        val entries: List<String> = files.entries()
            ?: return CompiledSweep.Unlisted("the compiled cache could not be listed")
        val (removed: List<String>, left: List<String>) = entries.filter { it !in keep }.partition { name ->
            try {
                files.deleteEntry(name)
                true
            } catch (notCleared: CompiledCacheNotClearedException) {
                false
            }
        }
        return CompiledSweep.Swept(removed, left)
    }
}
