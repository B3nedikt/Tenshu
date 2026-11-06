package app.tenshu.control.documents

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.drive_documents_root_title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform
import org.tenshu.core.common.TenshuClient
import java.io.FileNotFoundException

/**
 * Tenshu Drive in the system file dialogs — "Open from…", `ACTION_OPEN_DOCUMENT`,
 * `ACTION_GET_CONTENT` (`docs/proposals/cross-app-file-access.md` P3).
 *
 * Hosted by **Control**, not Drive (D-C): Control is the proxy and holds the keys, so a read is one
 * in-process hop, and the root survives Drive being uninstalled. Everything realm-shaped lives in
 * [DriveDocumentsSource]; this class only translates to cursors and file descriptors.
 *
 * **Who sees what (D-D, threat model A5).** The listing calls come from the system picker
 * (DocumentsUI, which holds `MANAGE_DOCUMENTS`), not from the app that asked; that app receives a
 * per-URI grant for the one file the user picked. Plaintext reaches it through a **proxy file
 * descriptor** whose reads decrypt on demand — the whole file never lands on disk.
 *
 * - **Read-only** (D-H): no create/write/delete flags, and a write mode is refused.
 * - **API 26+**: [StorageManager.openProxyFileDescriptor] is what makes a remote file seekable
 *   without downloading it whole. Below 26 the root is simply not offered.
 * - **The master switch** ([DriveDocumentsAccess]) is checked on every call.
 * - ⚠ Methods run on binder threads and block on the in-process client (`runBlocking`), bounded by
 *   [DriveDocumentsSource]'s timeouts. ⚠ The provider is created before `Application.onCreate`,
 *   so Koin is resolved per call, never in [onCreate].
 */
class DriveDocumentsProvider : DocumentsProvider() {

    private val source = DriveDocumentsSource(client = { KoinPlatform.getKoin().get<TenshuClient>() })

    /** Thumbnails are small in-memory blobs, so one shared thread serves all their fds. */
    private val thumbnailThread by lazy { HandlerThread("tenshu-documents-thumbnails").apply { start() } }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val context = requireNotNull(context)
        cursor.setNotificationUri(
            context.contentResolver,
            android.provider.DocumentsContract.buildRootsUri(DriveDocumentsAccess.authority(context)),
        )
        if (!isServing()) return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, DriveDocumentIds.ROOT)
            add(Root.COLUMN_TITLE, rootTitle())
            // ⚠ No FLAG_SUPPORTS_IS_CHILD: that flag is what lets an app pick a whole FOLDER
            // (ACTION_OPEN_DOCUMENT_TREE) and then list it — the app would see names, not just the
            // files the user chose (D-D). Picking files only, until a folder grant is decided.
            add(Root.COLUMN_FLAGS, 0)
            add(Root.COLUMN_ICON, context.applicationInfo.icon)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        requireServing()
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val document = request { source.document(documentId) }
        cursor.addDocument(if (document.documentId == DriveDocumentIds.ROOT) document.copy(name = rootTitle()) else document)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        requireServing()
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        request { source.children(parentDocumentId) }.forEach { cursor.addDocument(it) }
        return cursor
    }

    override fun getDocumentType(documentId: String): String {
        requireServing()
        return mimeTypeOf(request { source.document(documentId) })
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        requireServing()
        if (mode.any { it == 'w' || it == 'a' || it == 't' }) {
            throw UnsupportedOperationException("Tenshu Drive documents are read-only (mode '$mode')")
        }
        val content = request(signal) { source.openContent(documentId) }
        if (signal?.isCanceled == true) {
            runBlocking { content.close() }
            throw OperationCanceledException()
        }
        val thread = HandlerThread("tenshu-document").apply { start() }
        return openContentFd(content, thread, ownsThread = true)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        requireServing()
        val maxEdge = sizeHint?.let { maxOf(it.x, it.y) } ?: DEFAULT_THUMBNAIL_EDGE
        val bytes = request(signal) { source.thumbnail(documentId, maxEdge) }
            ?: throw FileNotFoundException("no thumbnail for '$documentId'")
        val fd = openContentFd(LocalContent(bytes), thumbnailThread, ownsThread = false)
        return AssetFileDescriptor(fd, 0, bytes.size.toLong())
    }

    private fun isServing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && DriveDocumentsAccess.isEnabled(requireNotNull(context))

    private fun requireServing() {
        if (!isServing()) throw FileNotFoundException("Tenshu Drive is not shared with other apps")
    }

    private fun rootTitle(): String = runBlocking { getString(Res.string.drive_documents_root_title) }

    /**
     * Runs [block] against the realm, abandoning it when [signal] is cancelled — the picker cancels
     * the thumbnails of rows that scrolled away, and a stale relay pull must not hold the line.
     *
     * ⚠ **Every** failure surfaces as [FileNotFoundException]: it is the one exception callers of
     * `openInputStream` are written to catch, and anything else crashes the third-party app that
     * asked. Only a cancellation stays a cancellation.
     */
    private fun <T> request(signal: CancellationSignal? = null, block: suspend () -> T): T =
        try {
            runBlocking {
                val work = async { block() }
                signal?.setOnCancelListener { work.cancel() }
                try {
                    work.await()
                } finally {
                    signal?.setOnCancelListener(null)
                }
            }
        } catch (e: CancellationException) {
            throw OperationCanceledException()
        } catch (e: DriveDocumentsException) {
            Log.w(TAG, "documents request failed (${e.kind}): ${e.message}")
            throw FileNotFoundException(e.message)
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "documents request failed", e)
            throw FileNotFoundException("Tenshu Drive could not answer: ${e.message}")
        }

    /**
     * A seekable, read-only fd over [content]. Every read the consuming app makes arrives in
     * `onRead` on [thread] and blocks on [DriveDocumentContent.read]; the content is closed when
     * the app closes the fd.
     */
    private fun openContentFd(
        content: DriveDocumentContent,
        thread: HandlerThread,
        ownsThread: Boolean,
    ): ParcelFileDescriptor {
        // isServing() already gated on API 26; restated so the call below is statically safe.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw FileNotFoundException("requires API 26+")
        return openProxyFd(content, thread, ownsThread)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openProxyFd(
        content: DriveDocumentContent,
        thread: HandlerThread,
        ownsThread: Boolean,
    ): ParcelFileDescriptor {
        val storage = requireNotNull(requireNotNull(context).getSystemService(StorageManager::class.java))
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = content.size

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                // The master switch also stops a descriptor that is already open (D-D).
                if (!isServing()) throw ErrnoException("onRead", OsConstants.EACCES)
                val bytes = runBlocking { content.read(offset, size) }
                    ?: throw ErrnoException("onRead", OsConstants.EIO)
                bytes.copyInto(data)
                return bytes.size
            }

            // onWrite / onFsync keep their defaults (EBADF): the fd is read-only.

            override fun onRelease() {
                try {
                    runBlocking { content.close() }
                } finally {
                    if (ownsThread) thread.quitSafely()
                }
            }
        }
        return try {
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
        } catch (e: Exception) {
            try {
                runBlocking { content.close() }
            } finally {
                if (ownsThread) thread.quitSafely()
            }
            throw FileNotFoundException("could not open a descriptor: ${e.message}")
        }
    }

    private fun MatrixCursor.addDocument(document: DriveDocument) {
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, document.documentId)
            add(Document.COLUMN_DISPLAY_NAME, document.name)
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(document))
            add(Document.COLUMN_SIZE, if (document.isFolder) null else document.size)
            add(Document.COLUMN_LAST_MODIFIED, document.modifiedAtMillis)
            add(Document.COLUMN_FLAGS, if (document.hasStillThumbnail) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
        }
    }

    private companion object {
        const val TAG = "DriveDocumentsProvider"
        const val ROOT_ID = "drive"

        /** What a thumbnail request with no size hint is answered at — the `LARGE` class. */
        const val DEFAULT_THUMBNAIL_EDGE = 256

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun mimeTypeOf(document: DriveDocument): String {
            if (document.isFolder) return Document.MIME_TYPE_DIR
            val extension = document.name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
    }
}
