@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.tenshu.file.proxy.ios.IosUploadSourceReader
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * The app-agnostic iOS [IosUploadSourceReader]: a `file://` URL or a bare path into bytes. It is
 * what every route-1 runtime binds by default (`FirstPartyLocalRuntime.assembleBase`).
 *
 * This used to live in `IosFilesRepositoryPlatformDelegate.readFileBytes`, on the *client* side of
 * the upload, where it ran while evaluating the upload op's arguments. That is what made an
 * unreadable source fatal: the throw happened before any `TResult` existed, so nothing converted
 * it and it escaped into an unhandled `viewModelScope.launch` — `abort()` on Kotlin/Native. Here
 * it runs inside the operation and `LocalTaskExecutor` owns the failure.
 * See `docs/proposals/drive-upload-source-read.md`.
 *
 * ⚠⚠ **No PhotoKit here, on purpose** (`ios-per-app-frameworks.md` D6). This module is linked and
 * exported by every app's framework, so a PhotoKit call here would put `Photos.framework` into
 * every app's binary. A `phasset://` source is read by Drive's `IosPhotoUploadSource`, which Drive
 * binds over this one through its `appOverrides`; any other runtime that is handed such a source
 * fails loudly below rather than reading it as a path.
 *
 * ⚠ Still used client-side by `contentHashOf`, which hashes the bytes *before* committing to an
 * upload it may be able to skip. That caller catches; this one must not swallow.
 */
@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
object IosFileUploadSource : IosUploadSourceReader {

    /** The scheme Drive's PhotoKit reader owns. Spelled here only to refuse it. */
    const val PHOTO_ASSET_SCHEME: String = "phasset://"

    /**
     * Whole-file read off the calling thread (a picked video can be tens of MB). A `file://` URL or
     * bare path reads from disk.
     *
     * ⚠ Throws rather than returning empty on every failure path — a zero-byte upload reporting
     * success is the failure mode this whole track exists to remove.
     */
    override suspend fun readBytes(uriString: String): ByteArray =
        withContext(Dispatchers.IO) {
            check(!uriString.startsWith(PHOTO_ASSET_SCHEME)) {
                "A PhotoKit source ($uriString) needs Drive's reader, and this runtime binds none"
            }
            val path = pathOf(uriString) ?: error("Unreadable upload source: $uriString")
            // ⚠ `dataWithContentsOfFile` returns nil for a DIRECTORY as well as for a missing
            // file, and an iOS document package (`.key`, `.pages`, `.numbers`, `.rtfd`) IS a
            // directory — which is how a picked Keynote file crashed Drive. Say which case it is;
            // "File not found" for a path that plainly exists sent one investigation the wrong way.
            val data = NSData.dataWithContentsOfFile(path)
                ?: error(
                    "Could not read upload source: $path — it is missing, unreadable, or a " +
                        "directory (an iOS document package is a directory, and cannot be " +
                        "uploaded as a flat file)"
                )
            data.toByteArray()
        }

    /** A filesystem path from a picker `file://` URI (percent-decoded via NSURL) or a bare path. */
    fun pathOf(uriString: String): String? =
        if (uriString.startsWith("file:")) NSURL(string = uriString)?.path else uriString

    private fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        if (size == 0) return ByteArray(0)
        val result = ByteArray(size)
        result.usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, size.convert())
        }
        return result
    }
}
