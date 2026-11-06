package app.tenshu.drive.ui.common

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import app.tenshu.drive.data.StreamingContentHandle
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges an open [StreamingContentHandle] to the platform media player as a seekable, read-only
 * `content://` fd (D2b S4, `docs/proposals/drive-streaming-preview.md` §3.5). `VideoView` /
 * `MediaPlayer` consume a `Uri`, not a raw fd; this provider's [openFile] returns the
 * `openProxyFileDescriptor`-backed [ParcelFileDescriptor] for a registered handle, so every player
 * read routes back to on-demand range decryption ([openStreamingProxyFd]). The whole-file
 * plaintext never lands on disk.
 *
 * **Why no cross-app grant.** Declared `exported="false"`, `grantUriPermissions="false"`. The uri
 * is only ever opened by Drive's own process: `MediaPlayer.setDataSource(context, uri)` calls
 * `ContentResolver.openFileDescriptor` in-process (same uid as the provider), then hands the
 * resulting raw fd to the media server. The media server never touches the provider, so nothing
 * about the stream leaves the app this way (threat-model A5 — no new exposure beyond the in-app
 * decrypted bytes the player already sees).
 *
 * The proxy fd's `onRelease` owns the proxy-side session close (fires when the player releases its
 * fd); [unregister] only drops the lookup entry. Registry keyed by the handle's own opaque id.
 */
internal class StreamingVideoProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        // Streaming is gated on API 26+ everywhere it is registered (isStreamingPreviewSupported),
        // but guard anyway so the openProxyFileDescriptor call below is statically safe (minSdk 24).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            throw FileNotFoundException("Streaming requires API 26+")
        }
        val id = uri.lastPathSegment
        val registered = id?.let { registry[it] }
            ?: throw FileNotFoundException("No active stream for $uri")
        return openStreamingProxyFd(requireNotNull(context), registered.handle)
    }

    override fun getType(uri: Uri): String? =
        uri.lastPathSegment?.let { registry[it]?.mimeType }

    // Playback only needs openFile + getType; the write/query surface is inert.
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    private data class Registered(val handle: StreamingContentHandle, val mimeType: String)

    companion object {
        /** Must match the `android:authorities` suffix declared in drive-android's manifest. */
        private const val AUTHORITY_SUFFIX = "streaming"

        private val registry = ConcurrentHashMap<String, Registered>()

        /**
         * Register [handle] and return the `content://` uri a player can open. [mimeType] backs
         * [getType] so the player picks the right demuxer.
         */
        fun register(context: Context, handle: StreamingContentHandle, mimeType: String): Uri {
            registry[handle.handle] = Registered(handle, mimeType)
            return Uri.parse("content://${context.packageName}.$AUTHORITY_SUFFIX/${handle.handle}")
        }

        /** Drop the lookup entry. The proxy fd's `onRelease` owns the session close. */
        fun unregister(uri: Uri) {
            uri.lastPathSegment?.let { registry.remove(it) }
        }
    }
}
