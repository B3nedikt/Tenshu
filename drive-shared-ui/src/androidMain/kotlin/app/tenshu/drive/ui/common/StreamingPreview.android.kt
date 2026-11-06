package app.tenshu.drive.ui.common

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.RequiresApi
import app.tenshu.drive.data.StreamingContentHandle
import kotlinx.coroutines.runBlocking

actual fun isStreamingPreviewSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

/**
 * Wrap [handle] in a seekable read-only [ParcelFileDescriptor] (D2b S3,
 * `drive-streaming-preview.md` §3.3) — a normal fd a player consumes, backed by on-demand range
 * decryption. Each player read drives [ProxyFileDescriptorCallback.onRead] on a dedicated
 * [HandlerThread]; it blocks on [StreamingContentHandle.readRange] (decrypting only the chunk(s)
 * the range touches) and copies the bytes out. The whole-file plaintext never lands on disk
 * (threat-model A5 streaming amendment). The session is closed and the thread quit on
 * [ProxyFileDescriptorCallback.onRelease] (when the fd is closed).
 *
 * `runBlocking` in `onRead`/`onRelease` is intentional: `ProxyFileDescriptorCallback` expects a
 * synchronous return, and these callbacks already run off the main thread on the [HandlerThread].
 *
 * API 26+ only ([isStreamingPreviewSupported]); below it the caller uses the D2a temp-file path.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal fun openStreamingProxyFd(
    context: Context,
    handle: StreamingContentHandle,
): ParcelFileDescriptor {
    val storageManager = requireNotNull(context.getSystemService(StorageManager::class.java)) {
        "StorageManager unavailable"
    }
    val thread = HandlerThread("tenshu-stream-${handle.handle}").apply { start() }
    val callback = object : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = handle.totalSize

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            val bytes = runBlocking { handle.readRange(offset, size) }
                ?: throw ErrnoException("onRead", OsConstants.EIO)
            bytes.copyInto(data)
            return bytes.size
        }

        // onWrite / onFsync keep their defaults (throw EBADF) — this fd is read-only.

        override fun onRelease() {
            runBlocking { handle.close() }
            thread.quitSafely()
        }
    }
    return storageManager.openProxyFileDescriptor(
        ParcelFileDescriptor.MODE_READ_ONLY,
        callback,
        Handler(thread.looper),
    )
}
