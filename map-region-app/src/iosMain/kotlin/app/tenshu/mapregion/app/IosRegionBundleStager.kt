@file:OptIn(ExperimentalForeignApi::class)

package app.tenshu.mapregion.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.fileHandleForUpdatingAtPath

/**
 * iOS [RegionBundleStager] over a plain container directory — the Darwin twin of
 * [JvmRegionBundleStager][app.tenshu.mapregion.app.JvmRegionBundleStager] (Android `filesDir`,
 * desktop cache dir). The render-hop drain writes a region bundle into a `<id>.pmtiles.part` temp
 * via positional `NSFileHandle` writes, then promotes it onto `<id>.pmtiles` with an atomic
 * `moveItemAtPath` ([commit]) so a partial drain never presents as complete.
 *
 * **Where the bytes land ([iosMapRegionStagingDirectory]): Application Support, not Caches.** This
 * matches every other durable iOS store in the repo (`iosRecordedWorkoutOutbox`,
 * `IosOpenMoveJournal`, `IosRecordingJournal`) and, more to the point, the *Android* precedent this
 * class mirrors — the stager is bound to `filesDir/map-regions` there, never `cacheDir`. A staged
 * bundle is the file the basemap renderer holds open; letting iOS reclaim it under storage pressure
 * mid-render is exactly the failure the atomic-commit dance exists to avoid.
 *
 * The directory is flagged **excluded from iCloud backup**: a staged bundle is a re-drainable
 * derivative of the proxy's canonical store (hundreds of MB), so backing it up would be pure waste
 * and an App-Review flag.
 *
 * Positional writes go through `fileHandleForUpdatingAtPath` rather than a whole-file write: a
 * region is far too large to hold in memory, and the drain hands us one bounded window
 * ([RegionStageWindowBytes]) at a time.
 */
class IosRegionBundleStager(
    private val baseDir: String,
) : RegionBundleStager {

    private val fm = NSFileManager.defaultManager

    override fun stagedPath(regionId: String): String = finalPath(regionId)

    override fun isStaged(regionId: String, expectedSize: Long): Boolean =
        fileSize(finalPath(regionId)) == expectedSize

    override fun writeChunk(regionId: String, offset: Long, bytes: ByteArray) {
        ensureBaseDir()
        val temp = tempPath(regionId)
        // offset == 0 starts a fresh temp (the interface contract) — truncate any prior partial by
        // recreating the file, which also gives `fileHandleForUpdatingAtPath` something to open.
        if (offset == 0L || !fm.fileExistsAtPath(temp)) {
            fm.removeItemAtPath(temp, error = null)
            fm.createFileAtPath(temp, contents = null, attributes = null)
        }
        val handle = NSFileHandle.fileHandleForUpdatingAtPath(temp) ?: return
        // The error-returning Foundation variants (`seekToFileOffset:`/`closeFile` are deprecated and
        // K/N does not surface them). Errors are swallowed deliberately: `stageRegionBundle` treats a
        // short/absent staged file as a failed drain and discards it, which is the honest signal.
        handle.seekToOffset(offset.toULong(), error = null)
        handle.writeData(bytes.toNSData(), error = null)
        handle.closeAndReturnError(null)
    }

    override fun commit(regionId: String) {
        ensureBaseDir()
        val temp = tempPath(regionId)
        val target = finalPath(regionId)
        fm.removeItemAtPath(target, error = null)
        if (!fm.moveItemAtPath(temp, toPath = target, error = null)) {
            // Rename refused (cross-volume / sandbox oddity): fall back to a copy, then drop the temp.
            fm.copyItemAtPath(temp, toPath = target, error = null)
            fm.removeItemAtPath(temp, error = null)
        }
    }

    override fun discard(regionId: String) {
        fm.removeItemAtPath(tempPath(regionId), error = null)
    }

    private fun ensureBaseDir() {
        if (fm.fileExistsAtPath(baseDir)) return
        fm.createDirectoryAtPath(baseDir, withIntermediateDirectories = true, attributes = null, error = null)
        NSURL.fileURLWithPath(baseDir).setResourceValue(
            value = NSNumber(bool = true),
            forKey = NSURLIsExcludedFromBackupKey,
            error = null,
        )
    }

    /** File length in bytes, or `null` when nothing sits at [path]. */
    private fun fileSize(path: String): Long? {
        val attributes = fm.attributesOfItemAtPath(path, error = null) ?: return null
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue
    }

    private fun finalPath(regionId: String): String = "$baseDir/$regionId.pmtiles"
    private fun tempPath(regionId: String): String = "$baseDir/$regionId.pmtiles.part"
}

/**
 * The default iOS staging root: `<Application Support>/[subdirectory]`, the twin of Android's
 * `filesDir/map-regions`. Falls back to the temporary directory only when Foundation reports no
 * Application Support container (it always does on a real app; the fallback keeps this total rather
 * than throwing at DI time).
 */
fun iosMapRegionStagingDirectory(subdirectory: String = "map-regions"): String {
    val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .filterIsInstance<String>()
        .firstOrNull()
        ?: NSTemporaryDirectory()
    return "$base/$subdirectory"
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        memScoped { NSData.create(bytes = allocArrayOf(this@toNSData), length = size.convert()) }
    }
