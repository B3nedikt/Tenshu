package org.tenshu.files.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

object ContentProviderUtil {

    private const val TAG = "ContentProviderUtil"

    /**
     * The source file's own timestamp (epoch **millis**), read via the
     * ContentResolver on the Drive side — where the `content://` grant lives.
     * Used to seed `File.CreatedAt` from the picked file's real date rather than
     * the import moment.
     *
     * This must be read client-side: the Android Photo Picker serves bytes
     * through a redacted/on-access copy, so a proxy-side `fstat` on the shared
     * descriptor sees ~the access moment, not the capture date. The authoritative
     * value lives in provider metadata that only the grant-holder can query.
     *
     * Tries, in order of authority:
     *  1. [MediaStore.MediaColumns.DATE_TAKEN] — capture time, **millis** (photos/videos).
     *  2. [DocumentsContract.Document.COLUMN_LAST_MODIFIED] — SAF documents, **millis**.
     *  3. [MediaStore.MediaColumns.DATE_MODIFIED] — **seconds** (×1000 here).
     *  4. For `file://` URIs, `File.lastModified()`.
     *
     * Returns `null` when nothing usable is present (the caller then falls back to
     * the proxy `fstat`, then to `now`). Best-effort — any failure yields `null`.
     */
    fun getSourceDateMillis(context: Context, uri: Uri): Long? {
        if (ContentResolver.SCHEME_FILE == uri.scheme) {
            return uri.path?.let { File(it).lastModified().takeIf { millis -> millis > 0L } }
        }
        if (ContentResolver.SCHEME_CONTENT != uri.scheme) return null

        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null

                fun readPositive(columnName: String, unitToMillis: Long): Long? {
                    val idx = cursor.getColumnIndex(columnName)
                    if (idx == -1 || cursor.isNull(idx)) return null
                    val raw = cursor.getLong(idx)
                    return if (raw > 0L) raw * unitToMillis else null
                }

                readPositive(MediaStore.MediaColumns.DATE_TAKEN, 1L)
                    ?: readPositive(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 1L)
                    ?: readPositive(MediaStore.MediaColumns.DATE_MODIFIED, 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading source date for URI: $uri", e)
            null
        }
    }

    /**
     * Retrieves the display name of a file from a content URI.
     *
     * This method queries the ContentProvider associated with the URI for the
     * OpenableColumns.DISPLAY_NAME column.
     *
     * @param context The Context used to access the ContentResolver.
     * @param uri The content URI (e.g., from a file picker or MediaStore) for which
     *            to retrieve the file name.
     * @return The display name of the file as a String, or null if the name
     *         could not be determined (e.g., URI is invalid, column not found,
     *         or an error occurred).
     */
    fun getFileName(context: Context, uri: Uri): String? {
        var fileName: String? = null

        // Only attempt to query if the scheme is "content"
        if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (displayNameIndex != -1) {
                            fileName = cursor.getString(displayNameIndex)
                        } else {
                            // Fallback if DISPLAY_NAME is not available (less common for openable URIs)
                            // You could try to derive it from the path, but it's less reliable.
                            // For simplicity, we'll just log it here.
                            Log.w(
                                TAG,
                                "OpenableColumns.DISPLAY_NAME column not found for URI: $uri"
                            )
                        }
                    }
                }
            } catch (e: SecurityException) {
                // This can happen if the calling app does not have permission to access the URI.
                // Ensure URI permissions are granted if this URI is from another app.
                Log.e(
                    TAG, "SecurityException querying URI: $uri. " +
                            "Ensure you have permission to access this URI.", e
                )
                return null // Or rethrow if this is a critical failure condition
            } catch (e: Exception) {
                // Catch any other unexpected exceptions during the query
                Log.e(TAG, "Error querying URI for file name: $uri", e)
                return null
            }
        } else if (ContentResolver.SCHEME_FILE == uri.scheme) {
            // If it's a file URI, try to get the name from the path
            fileName = uri.lastPathSegment
        }
        // You could add more scheme handling here if necessary (e.g., "http", "https")

        if (fileName == null && uri.scheme != ContentResolver.SCHEME_CONTENT) {
            // A more generic fallback if no specific scheme handling worked,
            // though lastPathSegment might not always be a user-friendly filename.
            fileName = uri.lastPathSegment
            if (fileName != null) {
                Log.w(TAG, "Falling back to lastPathSegment for URI: $uri, FileName: $fileName")
            }
        }

        return fileName
    }
}
