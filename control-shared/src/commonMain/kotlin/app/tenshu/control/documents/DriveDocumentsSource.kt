package app.tenshu.control.documents

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.NotFoundError
import org.tenshu.core.TError
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.ListBuilder
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.equalTo
import org.tenshu.core.file.ContentModifiedAt
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.Folder
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.ThumbnailLarge
import org.tenshu.core.file.contentModifiedAtOrCreated
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.files
import org.tenshu.core.file.isPdf
import org.tenshu.core.file.path
import org.tenshu.core.file.system.FileSystem
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.core.ifEmptyError
import org.tenshu.core.relation.eager
import org.tenshu.core.resolve
import org.tenshu.file.client.fileService
import org.tenshu.sync.client.syncService
import org.tenshu.sync.closeContentStream
import org.tenshu.sync.openContentStream
import org.tenshu.sync.readContentRange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The realm's files as the OS file dialogs see them — the platform-neutral half of Android's
 * `DriveDocumentsProvider` (`docs/proposals/cross-app-file-access.md` P3, D-C: Control hosts it,
 * because Control *is* the proxy).
 *
 * Reads through Control's **in-process** [TenshuClient], like the macOS File Provider bridge
 * (`ControlFileBridge`), whose read helpers this mirrors. Read-only by design (D-H).
 *
 * ⚠ A document id is the item's realm **path** ([DriveDocumentIds]). A rename or move breaks a
 * URI an app was granted earlier, and ⚠ a **different** file later saved at the same path is what
 * that URI then opens — acceptable for a picker, whose grants are almost always used at once.
 * Stable ids would need the identifier map the macOS bridge keeps.
 */
class DriveDocumentsSource(
    private val client: () -> TenshuClient,
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
    private val thumbnailTimeout: Duration = THUMBNAIL_TIMEOUT,
    private val readTimeout: Duration = READ_TIMEOUT,
    private val maxWholeFileBytes: Long = MAX_WHOLE_FILE_BYTES,
) {

    /** The direct children of the folder [documentId] names. */
    suspend fun children(documentId: String): List<DriveDocument> {
        val path = DriveDocumentIds.pathOf(documentId)
        return bounded("list '$path'") {
            fileSystem().root.files
                .childrenOf(path)
                .select { documentProjection() }
                .flow()
                .first()
                .fold(
                    onSuccess = { list -> list.toList().map { it.toDocument() } },
                    onFailure = { throw it.asDocumentsException("list", path) },
                )
        }
    }

    /** The item [documentId] names. The root is synthetic: it has no row of its own. */
    suspend fun document(documentId: String): DriveDocument {
        val path = DriveDocumentIds.pathOf(documentId)
        if (path == DriveDocumentIds.ROOT) return ROOT_DOCUMENT
        return bounded("read '$path'") {
            fileSystem().root.files
                .fileAt(path)
                .select { documentProjection() }
                .resolve()
                .fold(
                    onSuccess = { it?.toDocument() ?: throw notFound(path) },
                    onFailure = { throw it.asDocumentsException("read", path) },
                )
        }
    }

    /**
     * Opens the file [documentId] names for ranged reads.
     *
     * Through the sync service's content stream when the file has one: a seekable,
     * chunk-at-a-time read (`drive-streaming-preview.md` §3.5), so a file that is not on this
     * device is fetched only as far as the reader actually reads, and a large file never exists
     * whole in this process.
     *
     * ⚠⚠ A file with no stream (its bytes only local, not pushed — or the stream failed to open
     * for any other reason) is read whole into memory, and only up to [maxWholeFileBytes]. Control
     * is the proxy for every Tenshu app on a phone-sized heap: a freshly recorded 2 GB video picked
     * before it synced must be refused, not take the proxy down with an OOM.
     */
    suspend fun openContent(documentId: String): DriveDocumentContent {
        val path = DriveDocumentIds.pathOf(documentId)
        if (path == DriveDocumentIds.ROOT) throw notFound(path)
        val sync = client().syncService
        val opening = bounded("open '$path'") {
            sync.openContentStream(File.ID, path, File.Data.id).fold(onSuccess = { it }, onFailure = { null })
        }
        if (opening == null) {
            val size = document(documentId).size
            if (size > maxWholeFileBytes) {
                throw DriveDocumentsException(
                    DriveDocumentsException.Kind.UNAVAILABLE,
                    "'$path' has no content stream and is $size bytes, over the $maxWholeFileBytes in-memory limit",
                )
            }
            return LocalContent(bounded("read '$path'") { fileSystem().readBytes(path) })
        }
        val handle = opening.handle.get()
        return StreamedContent(
            size = opening.totalSize.get(),
            readRange = { offset, length ->
                withTimeoutOrNull(readTimeout) {
                    sync.readContentRange(handle, offset, length).fold(
                        onSuccess = { it.getOrNull() },
                        onFailure = { null },
                    )
                }
            },
            closeStream = { sync.closeContentStream(handle) },
        )
    }

    /**
     * The stored thumbnail that best answers a [maxEdge]-pixel request, or `null` when the realm
     * holds none for this file.
     *
     * ⚠⚠ Reads the stored `LIST` / `LARGE` classes only, never `File.Preview` (which would read
     * the original) and never a hero class (naming one declares it wanted for this device and
     * starts a realm-wide backfill). The same rule as the macOS bridge's `storedThumbnail`.
     */
    suspend fun thumbnail(documentId: String, maxEdge: Int): ByteArray? {
        val path = DriveDocumentIds.pathOf(documentId)
        if (path == DriveDocumentIds.ROOT || !carriesStills(nameOf(path))) return null
        return bounded("thumbnail '$path'", thumbnailTimeout) {
            val file = fileSystem().root.files.fileAt(path)
            suspend fun read(): ByteArray? = file
                .select { relations(eager(File.Thumbnail), eager(File.ThumbnailLarge)) }
                .resolve()
                .fold(
                    onSuccess = { entity ->
                        if (entity == null) throw notFound(path)
                        bestStoredThumbnail(
                            maxEdge,
                            list = entity.get(File.Thumbnail)?.getOrNull(),
                            large = entity.get(File.ThumbnailLarge)?.getOrNull(),
                        )
                    },
                    onFailure = { throw it.asDocumentsException("thumbnail", path) },
                )
            // The classes are `Syncable(OnDemand)`: projecting them without `eager` is what pulls
            // one that is not on this device yet; the eager read first keeps a local one fast.
            // Only the class this request would pick is pulled — a scrolled folder of remote
            // photos otherwise costs two fetches per row.
            read() ?: run {
                val wanted = if (maxEdge > GetStoredThumbnail.LIST_PX) File.ThumbnailLarge else File.Thumbnail
                file.select { relations(wanted) }.resolve().fold(
                    onSuccess = {},
                    onFailure = { throw it.asDocumentsException("thumbnail pull", path) },
                )
                read()
            }
        }
    }

    private fun fileSystem(): FileSystem = client().fileService.defaultFileSystem

    private suspend fun <T> bounded(what: String, timeout: Duration = requestTimeout, block: suspend () -> T): T =
        try {
            withTimeout(timeout) { block() }
        } catch (e: TimeoutCancellationException) {
            throw DriveDocumentsException(DriveDocumentsException.Kind.UNAVAILABLE, "$what timed out")
        }

    companion object {
        /** A listing or open that has not answered by now is reported as unavailable. */
        val REQUEST_TIMEOUT: Duration = 30.seconds

        /**
         * Thumbnails get far less: the system picker loads them one at a time per provider, so a
         * slow one holds up every row behind it. Past this the row keeps its type icon.
         */
        val THUMBNAIL_TIMEOUT: Duration = 8.seconds

        /** The most a file with no content stream may be read whole into Control's memory. */
        const val MAX_WHOLE_FILE_BYTES: Long = 64L * 1024 * 1024

        /**
         * The most one ranged read may wait for its chunk. Past it the reading app gets an I/O
         * error rather than a read that never returns.
         */
        val READ_TIMEOUT: Duration = 60.seconds

        private val ROOT_DOCUMENT = DriveDocument(
            documentId = DriveDocumentIds.ROOT,
            name = "",
            isFolder = true,
            size = 0L,
            modifiedAtMillis = null,
            hasStillThumbnail = false,
        )
    }
}

/** One file or folder, in plain values. */
data class DriveDocument(
    val documentId: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val modifiedAtMillis: Long?,
    /** Whether a still thumbnail class is ever minted for this kind of file. */
    val hasStillThumbnail: Boolean,
)

/** A failed documents request: [Kind.NOT_FOUND] means absence and nothing else. */
class DriveDocumentsException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NOT_FOUND, UNAVAILABLE }
}

/** An open file's bytes, read by range. [close] must be called once the reader is done. */
interface DriveDocumentContent {
    val size: Long

    /**
     * Up to [length] bytes from [offset]; fewer at the end of the file, empty past it, and
     * `null` when the read failed.
     */
    suspend fun read(offset: Long, length: Int): ByteArray?

    suspend fun close()
}

internal class LocalContent(private val bytes: ByteArray) : DriveDocumentContent {
    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size || length <= 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }

    override suspend fun close() {}
}

internal class StreamedContent(
    override val size: Long,
    private val readRange: suspend (offset: Long, length: Int) -> ByteArray?,
    private val closeStream: suspend () -> Unit,
) : DriveDocumentContent {
    override suspend fun read(offset: Long, length: Int): ByteArray? {
        // Never ask past the end: the reader's buffer is often larger than what is left.
        if (offset >= size || length <= 0) return ByteArray(0)
        val clamped = minOf(length.toLong(), size - offset).toInt()
        return readRange(offset, clamped)
    }

    override suspend fun close() = closeStream()
}

/**
 * Document ids are realm paths: `/` is the root, `/Photos/cat.jpg` a file. Validated on the way
 * in, because the id arrives from another process.
 */
object DriveDocumentIds {
    const val ROOT = "/"

    /** The realm path [documentId] names; a malformed id is `NOT_FOUND`. */
    fun pathOf(documentId: String): String {
        if (documentId == ROOT) return ROOT
        val valid = documentId.startsWith('/') &&
            documentId.substring(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        if (!valid) throw DriveDocumentsException(DriveDocumentsException.Kind.NOT_FOUND, "malformed id '$documentId'")
        return documentId
    }
}

/** The smallest stored class that covers a [maxEdge]-pixel request, else whichever exists. */
internal fun bestStoredThumbnail(maxEdge: Int, list: ByteArray?, large: ByteArray?): ByteArray? =
    if (maxEdge > GetStoredThumbnail.LIST_PX) large ?: list else list ?: large

/** An image, a video's poster frame or a PDF's first page — the files a still is minted for. */
internal fun carriesStills(name: String): Boolean =
    isPdf(name) || fileCategoryForName(name).let { it == FileCategory.Image || it == FileCategory.Video }

private fun nameOf(path: String): String = path.substringAfterLast('/')

private fun notFound(path: String) =
    DriveDocumentsException(DriveDocumentsException.Kind.NOT_FOUND, "no file at '$path'")

/** A missing row, or a missing folder above it, is `NOT_FOUND`; anything else may be retried. */
private fun TError.asDocumentsException(op: String, path: String): DriveDocumentsException =
    if (this is NotFoundError) notFound(path)
    else DriveDocumentsException(DriveDocumentsException.Kind.UNAVAILABLE, "$op failed for '$path': $this")

private fun <T : org.tenshu.core.Data> ListBuilder<Selector<T>>.documentProjection() {
    fields(File.Field.PATH)
    relations(File.Size, File.CreatedAt, File.LastModifiedAt, File.ContentModifiedAt)
}

private fun Entity<File>.toDocument(): DriveDocument {
    val realmPath = this.path.get()
    val isFolder = type.get() == Folder.ID
    val createdAt = get(File.CreatedAt)?.get()
    val name = nameOf(realmPath)
    return DriveDocument(
        documentId = realmPath,
        name = name,
        isFolder = isFolder,
        size = if (isFolder) 0L else get(File.Size)?.get() ?: 0L,
        // The file's own date, never the moment it was uploaded or moved; a folder has none and
        // keeps the realm's "last touched" (as the macOS bridge does).
        modifiedAtMillis = if (isFolder) get(File.LastModifiedAt)?.get()
        else contentModifiedAtOrCreated(get(File.ContentModifiedAt)?.get(), createdAt),
        hasStillThumbnail = !isFolder && carriesStills(name),
    )
}

private suspend fun FileSystem.readBytes(path: String): ByteArray =
    root.files
        .fileAt(path)
        .select { relations(File.Data) }
        .resolve()
        .fold(
            onSuccess = { entity ->
                // `getOrNull()` is graceful on a cache miss (TenshuFilesRepository.getRemoteFile).
                entity?.get(File.Data)?.getOrNull()
                    ?: if (entity == null) throw notFound(path)
                    else throw DriveDocumentsException(DriveDocumentsException.Kind.UNAVAILABLE, "no bytes for '$path'")
            },
            onFailure = { throw it.asDocumentsException("read", path) },
        )

/**
 * Children of the folder at [folderPath], segment by segment so every hop is a direct-child key
 * lookup (the proxy's point-read fast path — `TenshuFilesRepository.navigateTo`). A missing folder
 * fails via [ifEmptyError]; an empty one is an empty list.
 */
private fun TList<File>.childrenOf(folderPath: String): TList<File> {
    if (folderPath == DriveDocumentIds.ROOT || folderPath.isEmpty()) return this
    var list = this
    var prefix = ""
    for (segment in folderPath.trimStart('/').split('/')) {
        prefix = "$prefix/$segment"
        val level = prefix
        list = list.search<Folder> { path equalTo level }.ifEmptyError().files
    }
    return list
}

private fun TList<File>.fileAt(fullPath: String) =
    childrenOf(fullPath.substringBeforeLast('/').ifEmpty { DriveDocumentIds.ROOT })
        .search<File> { path equalTo fullPath }
