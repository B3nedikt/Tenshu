package org.tenshu.core.common.proxy

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Persistent [PreviewCache] backed by `<filesDir>/.previews/<key>` — the
 * Control-side, on-disk cure for the Binder-watchdog kill described in
 * `docs/tech-debt.md` §"File Previews Are Regenerated From the Full File
 * on Every Access (No Cache)".
 *
 * ### Storage layout
 *
 * `<context.filesDir>/.previews/<sha256-hex>__<w>x<h>` — one file per
 * cache entry. The `.previews/` dot-prefix segregates the cache root
 * from user content (`AndroidFileDatabaseImpl` writes user bytes
 * directly under `filesDir`, never inside `.previews/`); the directory
 * is created lazily on first `put`.
 *
 * ### Key encoding
 *
 * SHA-256 hex is fixed-length, lower-case, and ASCII — safe as a file
 * name on every filesystem we run on. The `__<w>x<h>` discriminator is
 * appended verbatim. `/` is **not** used in the on-disk key (the
 * commonMain `cacheKey` helper does use `/` for the in-memory map; the
 * Android impl rewrites it to `__` to keep entries flat — one directory,
 * O(1) listing, no nested mkdir).
 */
class AndroidPreviewCache(
    private val context: Context,
    override val maxEntryBytes: Int = PreviewCache.DEFAULT_MAX_ENTRY_BYTES
) : PreviewCache {

    companion object {
        private const val TAG = "PreviewCache"
        private const val DIR_NAME = ".previews"
    }

    private val cacheDir: File
        get() = File(context.filesDir, DIR_NAME)

    override fun get(contentHash: String, width: Int, height: Int): ByteArray? {
        val file = entryFile(contentHash, width, height)
        return try {
            if (file.exists() && file.isFile && file.canRead()) {
                file.readBytes()
            } else {
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "IOException reading preview cache entry ${file.absolutePath}", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException reading preview cache entry ${file.absolutePath}", e)
            null
        }
    }

    override fun put(
        contentHash: String,
        width: Int,
        height: Int,
        bytes: ByteArray
    ): Boolean {
        if (bytes.size > maxEntryBytes) return false
        val dir = cacheDir
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create preview cache dir ${dir.absolutePath}")
            return false
        }
        val target = entryFile(contentHash, width, height)
        return try {
            // Write-to-temp + rename so a crash mid-write never leaves a
            // truncated entry that a later `get` would return as a
            // valid-but-corrupt preview.
            val tmp = File(dir, "${target.name}.tmp")
            tmp.outputStream().use { it.write(bytes) }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                // Fallback: copy + delete (cross-fs rename failure shouldn't
                // happen inside one filesDir but defensive).
                tmp.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.delete()
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "IOException writing preview cache entry ${target.absolutePath}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException writing preview cache entry ${target.absolutePath}", e)
            false
        }
    }

    private fun entryFile(contentHash: String, width: Int, height: Int): File =
        File(cacheDir, "${contentHash}__${width}x${height}")
}
