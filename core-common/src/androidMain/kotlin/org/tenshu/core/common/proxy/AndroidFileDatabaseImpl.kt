package org.tenshu.core.common.proxy

import android.content.Context
import android.util.Log
import org.tenshu.core.TByteArray
import org.tenshu.core.common.client.FileByteArrayDto
import org.tenshu.core.common.impl.TByteArrayDto
import java.io.File
import java.io.IOException


class AndroidFileDatabaseImpl(private val context: Context) : AndroidFileDatabase {

    companion object {
        private const val TAG = "FileDatabaseImpl"
    }

    override fun writeRawByteArray(filePath: String, bytes: ByteArray) {
        if (filePath.isBlank()) {
            throw IllegalArgumentException("File path cannot be blank.")
        }

        val internalFilesDir: File = context.filesDir
        val file = File(internalFilesDir, filePath)

        try {
            // Check if the target path already exists and is a directory
            if (file.exists() && file.isDirectory) {
                val errorMessage =
                    "Cannot write file. Path '$filePath' already exists and is a directory."
                Log.e(TAG, errorMessage)
                throw IOException(errorMessage)
            }

            // Create parent directories if they don't exist
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    val errorMsg = "Failed to create parent directory: ${parentDir.absolutePath}"
                    Log.e(TAG, errorMsg)
                    // Check if the reason mkdirs failed is because a *file* exists
                    // where a directory component was supposed to be.
                    if (parentDir.isFile) {
                        throw IOException("Cannot create parent directory. A file exists at: ${parentDir.absolutePath}")
                    }
                    throw IOException(errorMsg)
                }
                Log.d(TAG, "Created parent directory: ${parentDir.absolutePath}")
            } else if (parentDir != null && parentDir.isFile) {
                // This checks if a parent path component is already a file
                val errorMessage =
                    "Cannot create parent directory for '$filePath'. Path '${parentDir.absolutePath}' exists and is a file, not a directory."
                Log.e(TAG, errorMessage)
                throw IOException(errorMessage)
            }

            // Write the bytes to the file. Overwrites if it's already a file.
            file.outputStream().use { outputStream ->
                outputStream.write(bytes)
            }
            Log.d(TAG, "Successfully wrote ${bytes.size} bytes to ${file.absolutePath}")

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException writing to ${file.absolutePath}", e)
            throw IOException("Security error writing to storage at $filePath", e)
        } catch (e: IOException) {
            // Log the original exception if it's not one we've already logged and re-thrown by specific checks
            if (!e.message.orEmpty().contains("already exists and is a directory") &&
                !e.message.orEmpty().contains("Failed to create parent directory") &&
                !e.message.orEmpty()
                    .contains("Path '${file.parentFile?.absolutePath}' exists and is a file")
            ) {
                Log.e(TAG, "IOException writing to ${file.absolutePath}", e)
            }
            throw e
        }
    }

    override fun writeByteArray(filePath: String, bytes: TByteArray) {

        if (bytes is TByteArrayDto) {
            writeRawByteArray(filePath, bytes.get())
        }
    }

    override fun readBytes(filePath: String): TByteArray? {
        if (filePath.isBlank()) {
            throw IllegalArgumentException("File path cannot be blank.")
        }

        val internalFilesDir: File = context.filesDir
        val internalFilePath = File(internalFilesDir, filePath).path

        val file = getFile(internalFilePath) ?: return null
        return FileByteArrayDto(file)
    }

    override fun fileSize(filePath: String): Long? {
        if (filePath.isBlank()) return null
        val internalFilesDir: File = context.filesDir
        val file = File(internalFilesDir, filePath)
        return if (file.exists() && file.isFile) file.length() else null
    }

    /**
     * True positional read so the file-content emit path stays
     * `O(CHUNK)` on Android — never materialises the whole DSLR JPEG
     * (the original OOM). `RandomAccessFile.seek` + a single
     * `CHUNK`-sized buffer; the final chunk is trimmed to the bytes
     * actually read. Correctness-by-construction here (no Android SDK
     * in the agent build env) — verified in Android Studio per the PR's
     * smoke-test ask.
     */
    override fun readBytesRange(filePath: String, offset: Long, length: Int): ByteArray? {
        if (filePath.isBlank()) return null
        val internalFilesDir: File = context.filesDir
        val file = File(internalFilesDir, filePath)
        if (!file.exists() || !file.isFile) return null
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (offset >= raf.length()) return ByteArray(0)
                raf.seek(offset)
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = raf.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                if (read == length) buf else buf.copyOf(read)
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException ranged-reading ${file.absolutePath}", e)
            null
        }
    }

    /**
     * True positional write so the file-content *read* path stays
     * `O(CHUNK)` on Android — the P2 manifest applier reassembles a
     * DSLR JPEG one decrypted chunk at a time into a temp file rather
     * than buffering the whole file in heap (the read-side mirror of
     * the original OOM). `RandomAccessFile.seek` + a single
     * `CHUNK`-sized buffer. Correctness-by-construction here (no
     * Android SDK in the agent build env) — verified in Android Studio
     * per the PR's smoke-test ask.
     */
    override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) {
        if (filePath.isBlank()) {
            throw IllegalArgumentException("File path cannot be blank.")
        }
        val internalFilesDir: File = context.filesDir
        val file = File(internalFilesDir, filePath)
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs()
        try {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.seek(offset)
                raf.write(bytes)
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException ranged-writing ${file.absolutePath}", e)
            throw e
        }
    }

    /** Atomic O(1) rename so the P2 promote step stays `O(CHUNK)`. */
    override fun moveFile(fromPath: String, toPath: String) {
        val dir: File = context.filesDir
        val from = File(dir, fromPath)
        // ⚠⚠ Absent source is the ORDINARY case, not an error: `File.Data` is `OnDemand`, so a
        // synced file whose bytes were never fetched has nothing here and the move is metadata-only.
        // Without this, `renameTo` fails and the fallback below opens a file that does not exist and
        // throws — inside `MoveFileImplementation`, after it has detached the row from its old parent
        // and before it saves the destination, leaving the file in neither listing.
        // ⚠ Before `to.delete()` below, so retrying a move that already relocated the bytes cannot
        // delete the only copy and then fail.
        // Every other implementation already returns here (`JvmFileDatabase`, `IosFileDatabase`,
        // `InMemoryFileDatabase`, and the interface default); this one was the outlier.
        if (!from.exists() || !from.isFile) return
        val to = File(dir, toPath)
        to.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (to.exists()) to.delete()
        if (!from.renameTo(to)) {
            // Cross-device or other rename failure: fall back to a
            // streamed copy (still bounded — Java's transferTo / 8K
            // buffer, never the whole file in heap) then delete source.
            from.inputStream().use { input ->
                to.outputStream().use { output -> input.copyTo(output) }
            }
            from.delete()
        }
    }

    override fun deleteFile(filePath: String) {
        if (filePath.isBlank()) return
        val file = File(context.filesDir, filePath)
        if (file.exists() && file.isFile) file.delete()
    }

    /** ⚠ `File.lastModified()` returns `0L` for an absent file — mapped to null. */
    override fun lastModifiedMs(filePath: String): Long? {
        if (filePath.isBlank()) return null
        val file = File(context.filesDir, filePath)
        if (!file.exists() || !file.isFile) return null
        return file.lastModified().takeIf { it > 0L }
    }

    override fun availableSpaceBytes(): Long? = context.filesDir.usableSpace.takeIf { it > 0L }

    override fun getFile(androidInternalFilePath: String): File? {
        val file = File(androidInternalFilePath)

        return try {
            if (file.exists() && file.isFile && file.canRead()) {
                return file
            } else {
                if (!file.exists()) {
                    Log.w(TAG, "File not found: ${file.absolutePath}")
                } else if (!file.isFile) {
                    Log.w(TAG, "Path is a directory, not a file, cannot read: ${file.absolutePath}")
                } else if (!file.canRead()) {
                    Log.w(TAG, "File cannot be read (permissions?): ${file.absolutePath}")
                }
                null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException reading from ${file.absolutePath}", e)
            null
        } catch (e: IOException) { // Catches FileNotFoundException as it's a subclass
            Log.e(TAG, "IOException reading from ${file.absolutePath}", e)
            null
        }
    }
}