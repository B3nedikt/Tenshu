package app.tenshu.mapregion.app

import java.io.File
import java.io.RandomAccessFile

/**
 * JVM (Android + desktop) [RegionBundleStager] over a plain directory: the render-hop drain writes
 * a region bundle into a `<id>.pmtiles.part` temp via positional writes, then promotes it onto
 * `<id>.pmtiles` with an atomic rename ([commit]) so a partial drain never presents as complete.
 *
 * [baseDir] is app-private storage supplied per platform (Android `filesDir`, desktop cache dir),
 * so the staged path is one MapLibre can open directly as `pmtiles://file://…` — on Android that
 * already lives under `filesDir`, so `:ui-map`'s `stageRegionFile` copy no-ops.
 */
class JvmRegionBundleStager(
    private val baseDir: File,
) : RegionBundleStager {

    override fun stagedPath(regionId: String): String = finalFile(regionId).absolutePath

    override fun isStaged(regionId: String, expectedSize: Long): Boolean {
        val file = finalFile(regionId)
        return file.isFile && file.length() == expectedSize
    }

    override fun writeChunk(regionId: String, offset: Long, bytes: ByteArray) {
        val temp = tempFile(regionId)
        temp.parentFile?.mkdirs()
        if (offset == 0L && temp.exists()) temp.delete()
        RandomAccessFile(temp, "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
        }
    }

    override fun commit(regionId: String) {
        val temp = tempFile(regionId)
        val target = finalFile(regionId)
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            // Cross-device / rename-unsupported fallback: copy then drop the temp.
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    override fun discard(regionId: String) {
        tempFile(regionId).delete()
    }

    private fun finalFile(regionId: String): File = File(baseDir, "$regionId.pmtiles")
    private fun tempFile(regionId: String): File = File(baseDir, "$regionId.pmtiles.part")
}
