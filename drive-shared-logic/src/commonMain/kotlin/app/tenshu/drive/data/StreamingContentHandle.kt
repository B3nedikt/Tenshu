package app.tenshu.drive.data

/**
 * A client-side handle to an open seekable content stream (D2b S3,
 * `docs/proposals/drive-streaming-preview.md` §3.5). Returned by
 * [FilesRepository.openStreamingContent]; bridges a platform consumer (the Android
 * `openProxyFileDescriptor` `onRead`, later iOS `AVAssetResourceLoaderDelegate` / web Service
 * Worker) to the `ReadContentRange` / `CloseContentStream` ops without exposing the proxy/IPC
 * types. [readRange] decrypts only the chunk(s) the range touches (bounded memory); [close]
 * releases the proxy-side session.
 *
 * Decoupled from the client by construction: the repository injects the op-calling closures, so
 * this stays plain logic (and unit-testable with fakes).
 */
class StreamingContentHandle(
    /** Opaque proxy-side session handle. */
    val handle: String,
    /** Total plaintext size of the file, for the consumer's content-length up front. */
    val totalSize: Long,
    private val readRangeFn: suspend (handle: String, offset: Long, length: Int) -> ByteArray?,
    private val closeFn: suspend (handle: String) -> Unit,
) {
    /**
     * Read up to [length] bytes at [offset]. Returns the bytes (≤ [length]; empty at/after EOF),
     * or `null` if the stream is closed or a covering chunk fails its integrity gate.
     */
    suspend fun readRange(offset: Long, length: Int): ByteArray? = readRangeFn(handle, offset, length)

    /** Close the stream and release its proxy-side decrypted-chunk buffers. Idempotent. */
    suspend fun close() = closeFn(handle)
}
