package org.tenshu.file.proxy.ios

/**
 * Resolves the platform source reference carried by
 * [org.tenshu.core.file.system.UploadSourceFile.Params.URI] to bytes — a `phasset://<localIdentifier>`
 * PhotoKit asset, a `file://` URL, or a bare path.
 *
 * ## Why this is a seam rather than a read in this module
 *
 * PhotoKit knowledge lives in `:tenshu-sdk-ios-drive` (`IosPhotoLibrary`), and the dependency runs
 * **that** way — `:tenshu-sdk-ios-drive` → `:tenshu-sdk-ios-core` → `:control-shared-proxy` → `api(:file-service-proxy)` — so this
 * module cannot import it. Injecting also means one implementation serves both readers: the upload
 * op here, and the client-side `contentHashOf` duplicate check, which still hashes bytes before an
 * upload it may avoid. Two copies of "turn a source reference into bytes" is exactly the kind of
 * thing that drifts.
 *
 * ## The contract
 *
 * ⚠⚠ **Throwing is correct here, and is the entire point of the phase.** A source that cannot be
 * read — an iOS document *package* (`.key` is a directory), a lapsed PhotoKit identifier, a file
 * deleted mid-drain — must throw, because this runs **inside** the operation and
 * `LocalTaskExecutor`'s catch-all converts the throw into a `TResult.Failure` the caller already
 * handles. That is precisely what reading client-side could not do: there, the read happened while
 * evaluating the op's *arguments*, so no `TResult` existed yet and the throw escaped into an
 * unhandled `viewModelScope.launch` — `abort()` on Kotlin/Native.
 * See `docs/proposals/drive-upload-source-read.md` §1.
 *
 * ⚠⚠ **Never return an empty array to signal failure.** A zero-byte upload that reports success is
 * the failure mode this whole track exists to remove, and it would be silent.
 */
fun interface IosUploadSourceReader {

    /** The bytes behind [uriString]. Throws if the source cannot be read at all. */
    suspend fun readBytes(uriString: String): ByteArray
}
