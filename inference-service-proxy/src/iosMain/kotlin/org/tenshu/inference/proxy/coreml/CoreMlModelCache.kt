package org.tenshu.inference.proxy.coreml

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.proxy.CompiledCache
import org.tenshu.inference.proxy.CompiledCacheNotClearedException
import org.tenshu.inference.proxy.LoadRecording
import org.tenshu.inference.proxy.CompiledFiles
import org.tenshu.inference.proxy.CompiledSweep
import org.tenshu.inference.proxy.CompiledTower
import org.tenshu.inference.proxy.SpaceRefusal
import org.tenshu.inference.proxy.MarkerRead
import org.tenshu.inference.proxy.PrepareStep
import org.tenshu.inference.proxy.catalog.CoreMlDiskBudget
import org.tenshu.inference.proxy.catalog.TarEntry
import org.tenshu.inference.proxy.catalog.TarFormatException
import org.tenshu.inference.proxy.catalog.TarReader
import platform.CoreML.MLModel
import platform.CoreML.compileModelAtURL
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL

/** The prepare step could not finish. No marker was written, so nothing reads as ready. */
class CoreMlPrepareException(message: String) : Exception(message)

/** The steps of [CoreMlModelCache.prepare], in order (§3.4): the common [PrepareStep]. */
typealias CoreMlPrepareStep = PrepareStep

/**
 * The two things [CoreMlModelCache] asks of Core ML, behind a seam so the ordering of the prepare
 * step is testable without a model.
 */
interface CoreMlCompiler {
    /** `MLModel.compileModel`: an `.mlpackage` in, the path of a `.mlmodelc` in a temporary place out. */
    fun compile(packagePath: String): String

    /** Loads the compiled model once with the shipped compute units and drops it. */
    fun firstOpen(compiledPath: String, spec: CoreMlTowerSpec)
}

/** [CoreMlCompiler] over `platform.CoreML`: what every product graph uses. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object SystemCoreMlCompiler : CoreMlCompiler {

    override fun compile(packagePath: String): String = memScoped {
        val failure = alloc<ObjCObjectVar<NSError?>>()
        val compiled: NSURL = MLModel.compileModelAtURL(NSURL.fileURLWithPath(packagePath), failure.ptr)
            ?: throw CoreMlLoadException(
                "cannot compile $packagePath: ${failure.value?.localizedDescription ?: "no NSError reported"}",
            )
        compiled.path ?: throw CoreMlLoadException("compiling $packagePath returned a URL with no path")
    }

    override fun firstOpen(compiledPath: String, spec: CoreMlTowerSpec) {
        CoreMlSession.open(compiledPath, spec).close()
    }
}

/**
 * Verified archive → compiled model on disk (`ios-on-device-inference.md` §3.4). Answers *"where
 * is the compiled tower for this archive digest, if it is ready?"*
 *
 * Layout, under the directory [files] is rooted at, with `<base>` = `coreml` unless told otherwise:
 * ```
 * <base>/<digest-tag>/<role>.mlmodelc    the compiled tower
 * <base>/<digest-tag>/ready              the marker: the archive digest and the OS build
 * <base>/tmp/<role>/                     the unpacked package, while a prepare runs
 * ```
 * `<digest-tag>` is a prefix of the archive's sha256, so a path names one content for its life.
 *
 * ⚠⚠ **The marker is written last, and no marker means not ready.** Every step before it can be
 * interrupted (a crash, a suspension, a full disk) and leaves a directory that looks complete. The
 * backend claims a capability only for a path [compiledPath] returns, so a half-prepared model is
 * never opened and the next [prepare] redoes the work.
 *
 * ⚠ The compiled model is not hashed. It is derived on the device from verified bytes, in a
 * container only this app writes, and it is trusted only when the marker names the digest asked for.
 *
 * ⚠ The archive's own digest is the caller's to verify (the model store does, against the signed
 * catalog). This class takes it as a name.
 *
 * @param files rooted at the app-private, backup-excluded inference directory (§3.1).
 * @param root the absolute directory [files] is rooted at: Core ML takes `NSURL`s, not store keys.
 * @param base where the cache keeps everything it writes, as a path inside [files].
 *   ⚠⚠ A directory of its own, never a re-rooted [files]: [prepare] reads the archive through the
 *   same database, by the model store's own key, and a database that prefixed every path would
 *   look for the archive under the cache's directory.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class CoreMlModelCache(
    private val files: FileDatabase,
    private val root: String,
    private val compiler: CoreMlCompiler = SystemCoreMlCompiler,
    private val osBuild: () -> String = { NSProcessInfo.processInfo.operatingSystemVersionString },
    base: String = BASE,
) {
    private val base: String = base.trim('/')

    /** The towers' specs by role, as [prepare] was last given them: what a first open is checked against. */
    private val specs = HashMap<String, CoreMlTowerSpec>()

    /**
     * ⚠⚠ The order of the steps, what a throw at each of them leaves, the marker written last and
     * removed first: all of that is [CompiledCache]'s, in common code, where it is tested on every
     * target. This class is only the primitives ([Files]): one act each, no order of its own.
     */
    val rules = CompiledCache(Files())

    /**
     * The absolute path of the compiled [role] for [archiveDigest], or `null` when it is to be
     * compiled. ⚠⚠ A marker that is there and cannot be read **throws**: it is not a tower that
     * was never prepared ([tower]).
     */
    fun compiledPath(role: String, archiveDigest: String): String? = when (val held = tower(role, archiveDigest)) {
        is CompiledTower.Ready -> held.path
        CompiledTower.Absent -> null
        is CompiledTower.Unreadable -> throw CoreMlPrepareException(held.why)
    }

    /** Where the compiled [role] for [archiveDigest] stands, as three answers. */
    fun tower(role: String, archiveDigest: String): CompiledTower = rules.tower(role, archiveDigest)

    /** Deletes the marker of [archiveDigest], then its compiled directory. @return whether both are gone. */
    fun discard(archiveDigest: String) = rules.discard(archiveDigest)

    /** The OS build the ready marker for [archiveDigest] recorded, or `null` when there is none. */
    fun preparedOnOsBuild(archiveDigest: String): String? =
        files.readBytes(markerPath(archiveDigest))?.get()?.decodeToString()?.let { markerValue(it, MARKER_OS_BUILD) }

    /**
     * Unpacks, compiles and marks the tower in [archivePath] (a path inside [files]). Returns the
     * compiled model's absolute path; a tower that is already ready returns at once.
     *
     * @param onStep called as each step starts, in order. ⚠⚠ **It may throw**, and that is how a
     *   prepare is stopped (a teardown, a move to the background): whatever it throws, at
     *   whichever step, nothing of the tower is left ([CompiledCache]).
     * @throws CoreMlPrepareException, [TarFormatException], [CoreMlLoadException],
     *   [CoreMlRejectedModelException] or what [onStep] threw; and, for too little free space,
     *   `CompiledSpaceInsufficientException` with the two figures (it was a
     *   [CoreMlPrepareException] with a sentence). After any of them no marker exists.
     */
    suspend fun prepare(
        spec: CoreMlTowerSpec,
        archivePath: String,
        archiveDigest: String,
        /** ⚠ [LoadRecording.UNRECORDED] is the latency harness's: the prepare step passes readiness's. */
        loads: LoadRecording = LoadRecording.UNRECORDED,
        onStep: (CoreMlPrepareStep) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        specs[spec.role] = spec
        try {
            rules.prepare(spec.role, archivePath, archiveDigest, proceed = {}, onStep = onStep, loads = loads)
        } catch (notCleared: CompiledCacheNotClearedException) {
            throw CoreMlPrepareException(notCleared.message.orEmpty())
        }
        absolute(compiledRelative(spec.role, archiveDigest))
    }

    /**
     * Deletes every compiled directory whose digest is not in [liveDigests], and the staging area.
     *
     * ⚠ The caller names what is live, because each tower has its own digest and directory: after
     * a new generation is marked ready, and at launch for a directory no install record names
     * (§3.2). ⚠ Never on a realm reset: compiled models are public weights (§3.1). ⚠ Not while a
     * [prepare] runs: the staging area is that prepare's.
     */
    fun sweep(liveDigests: Set<String>): CompiledSweep = rules.sweep(liveDigests)

    /** Bytes on disk under the cache: what the Models screen adds to the archives' size (§3.4). */
    fun sizeOnDisk(): Long = directorySize(absolute(base))

    private inner class Files : CompiledFiles {

        /**
         * ⚠⚠ `readBytes` answers `null` both for no file and for a file it could not read (a phone
         * before its first unlock), so a `null` is followed by a stat: a marker that is **there**
         * is unreadable, not absent.
         */
        override fun marker(archiveDigest: String): MarkerRead {
            val path: String = markerPath(archiveDigest)
            val bytes: ByteArray? = try {
                files.readBytes(path)?.get()
            } catch (failure: Exception) {
                return MarkerRead.Unreadable("the ready marker at $path could not be read: $failure")
            }
            if (bytes == null) {
                val there: Boolean = try {
                    files.fileSize(path) != null
                } catch (failure: Exception) {
                    return MarkerRead.Unreadable("the ready marker at $path could not be looked at: $failure")
                }
                return if (there) MarkerRead.Unreadable("the ready marker at $path is there and gave no bytes") else MarkerRead.Absent
            }
            return MarkerRead.Held(markerValue(bytes.decodeToString(), MARKER_DIGEST))
        }

        override fun towerThere(role: String, archiveDigest: String): Boolean =
            NSFileManager.defaultManager.fileExistsAtPath(towerPath(role, archiveDigest))

        override fun towerPath(role: String, archiveDigest: String): String = absolute(compiledRelative(role, archiveDigest))

        override fun tagOf(archiveDigest: String): String = digestTag(archiveDigest)

        /**
         * ⚠⚠ [CoreMlDiskBudget.towerNeed], the very estimate the download's budget is made of, so
         * a download it admitted is not refused here (§14.17.5). An unknown free space is not a
         * refusal: the steps then fail on their own, before the marker.
         */
        override fun spaceRefusal(archivePath: String): SpaceRefusal? {
            val archiveSize: Long = files.fileSize(archivePath) ?: return SpaceRefusal.NoArchive(archivePath)
            val free: Long = files.availableSpaceBytes() ?: return null
            val needed: Long = CoreMlDiskBudget.towerNeed(archiveSize)
            return if (free < needed) SpaceRefusal.Insufficient(archivePath, needed, free) else null
        }

        override fun unpack(archivePath: String, role: String): String {
            val staging: String = stagingOf(role)
            return absolute("$staging/${packageName(TarReader.unpack(files, archivePath, staging))}")
        }

        override fun compile(packagePath: String): String = compiler.compile(packagePath)

        override fun move(temporary: String, role: String, archiveDigest: String) {
            val to: String = towerPath(role, archiveDigest)
            val manager = NSFileManager.defaultManager
            manager.createDirectoryAtPath(to.substringBeforeLast('/'), withIntermediateDirectories = true, attributes = null, error = null)
            val reason: String? = memScoped {
                val failure = alloc<ObjCObjectVar<NSError?>>()
                if (manager.moveItemAtPath(temporary, toPath = to, error = failure.ptr)) {
                    null
                } else {
                    failure.value?.localizedDescription ?: "no NSError reported"
                }
            }
            if (reason != null) throw CoreMlPrepareException("cannot move the compiled model into $to: $reason")
        }

        override fun dropTemporary(temporary: String) = gone(temporary, "Core ML's output")

        override fun firstOpen(role: String, archiveDigest: String) {
            val spec: CoreMlTowerSpec = specs[role] ?: throw CoreMlPrepareException("'$role' is not a tower this cache was given")
            compiler.firstOpen(towerPath(role, archiveDigest), spec)
        }

        override fun writeMarker(archiveDigest: String) {
            files.writeRawByteArray(
                markerPath(archiveDigest),
                "$MARKER_DIGEST=$archiveDigest\n$MARKER_OS_BUILD=${osBuild()}\n".encodeToByteArray(),
            )
        }

        override fun deleteMarker(archiveDigest: String) = gone(absolute(markerPath(archiveDigest)), "the ready marker")

        override fun deleteTowers(archiveDigest: String) = gone(absolute(digestDirectory(archiveDigest)), "the compiled directory")

        override fun deleteStaging(role: String) = gone(absolute(stagingOf(role)), "the unpacked package of $role")

        /** ⚠ No directory is nothing to sweep; one that is there and cannot be listed is not known to be empty. */
        override fun entries(): List<String>? {
            val manager = NSFileManager.defaultManager
            if (!manager.fileExistsAtPath(absolute(base))) return emptyList()
            return manager.contentsOfDirectoryAtPath(absolute(base), error = null)?.map { it as String }
        }

        /** ⚠ A tower's directory goes marker first, like a discard. */
        override fun deleteEntry(name: String) {
            gone(absolute("$base/$name/$MARKER"), "the ready marker")
            gone(absolute("$base/$name"), "the cache entry")
        }

        /** Removes [path], and throws unless it **is not there now**: a delete that failed is not a delete. */
        private fun gone(path: String, what: String) {
            val manager = NSFileManager.defaultManager
            manager.removeItemAtPath(path, error = null)
            if (manager.fileExistsAtPath(path)) throw CompiledCacheNotClearedException("could not delete $what at $path")
        }
    }

    private fun stagingOf(role: String): String = "$base/tmp/$role"

    /** The archive is one `.mlpackage` directory; anything else is not a tower. */
    private fun packageName(entries: List<TarEntry>): String {
        val tops: Set<String> = entries.map { it.path.substringBefore('/') }.toSet()
        val top: String = tops.singleOrNull()
            ?: throw TarFormatException("expected one top-level .mlpackage directory, found ${tops.sorted()}")
        if (!top.endsWith(".mlpackage") || entries.any { '/' !in it.path }) {
            throw TarFormatException("expected one top-level .mlpackage directory, found '$top'")
        }
        return top
    }

    private fun directorySize(path: String): Long {
        val manager = NSFileManager.defaultManager
        val children: List<*> = manager.subpathsOfDirectoryAtPath(path, error = null) ?: return 0L
        var total = 0L
        children.forEach { child ->
            val attributes = manager.attributesOfItemAtPath("$path/${child as String}", error = null)
            if (attributes?.get(NSFileType) == NSFileTypeRegular) {
                total += (attributes?.get(NSFileSize) as? Number)?.toLong() ?: 0L
            }
        }
        return total
    }

    private fun absolute(relative: String): String = "${root.trimEnd('/')}/$relative"

    private fun digestDirectory(digest: String): String = "$base/${digestTag(digest)}"

    private fun compiledRelative(role: String, digest: String): String = "${digestDirectory(digest)}/$role.mlmodelc"

    private fun markerPath(digest: String): String = "${digestDirectory(digest)}/$MARKER"

    /** ⚠ Sanitised and bounded: a digest is catalog-supplied text that becomes a directory name. */
    private fun digestTag(digest: String): String {
        val tag: String = digest.filter { it in '0'..'9' || it in 'a'..'f' }.take(DIGEST_TAG_LENGTH)
        require(tag.length == DIGEST_TAG_LENGTH) { "'$digest' is not a sha256 in lower-case hex" }
        return tag
    }

    private fun markerValue(marker: String, key: String): String? =
        marker.lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    companion object {
        /** The default `base`. */
        const val BASE: String = "coreml"
        const val MARKER: String = "ready"
        private const val DIGEST_TAG_LENGTH: Int = 16
        private const val MARKER_DIGEST: String = "digest"
        private const val MARKER_OS_BUILD: String = "osBuild"
    }
}
