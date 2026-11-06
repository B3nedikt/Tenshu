package app.tenshu.drive.data

import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.ingest.backup.MediaBucket
import app.tenshu.ingest.backup.MediaItem
import app.tenshu.ingest.backup.MediaSourceProvider
import app.tenshu.ingest.backup.MediaType
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.tenshu.ingest.backup.documentTreeDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * Android SAF document-tree [MediaSourceProvider] (Phase 3b — `docs/proposals/
 * drive-synced-folders-phase2.md` §3.2 "separate adapters, same engine"). Backs up
 * arbitrary user folders the gallery can't reach — WhatsApp Documents, Downloads,
 * an export folder — granted via `OPEN_DOCUMENT_TREE` rather than `READ_MEDIA_*`.
 *
 * Each **bucket id IS a persisted tree URI**: the controller's selected buckets are
 * the persisted `OPEN_DOCUMENT_TREE` URIs (`BackupPreferences.observeDocumentTreeUris`),
 * and [listNewItems] walks one tree. The item **cursor is `lastModified` millis**
 * (a SAF tree has no `MediaStore` `_ID`), so a strict `mtime > cursor` window is
 * "new since last drain" (§3.5). The tree is **flattened** — nested files land in
 * one `/Backup/<device>/<folder>` directory (v1 limit; matches the flat gallery
 * model). [observeChanges] registers a [ContentObserver] on each tree's
 * children-documents URI for a prompt **live trigger**, degrading to the start /
 * settings / manual triggers if the backing `DocumentsProvider` posts no change
 * notifications.
 *
 * Reuses the same per-file data plane as the manual folder upload
 * (`AndroidFilesRepositoryPlatformDelegate` — `DocumentFile` enumeration +
 * `uploadSourceFile`); the only new capability vs that path is the **persistent**
 * grant (`DocumentTreeUris` + `takePersistableUriPermission`).
 *
 * Not locally compilable in the headless build env (no Android SDK); correctness
 * by construction + Studio verification, like the other `Android*` actuals.
 */
class AndroidDocumentTreeSourceProvider(
    private val context: Context,
) : MediaSourceProvider {

    /**
     * The persisted document-tree grants, as one bucket per tree URI. Enumerates
     * `persistedUriPermissions` (the grants Drive actually holds) intersected with
     * read access, so a revoked tree silently drops out. [includeVideo] is ignored
     * — a document tree is type-agnostic.
     */
    override suspend fun listBuckets(includeVideo: Boolean): List<MediaBucket> =
        withContext(Dispatchers.IO) {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .mapNotNull { permission ->
                    val uriString = permission.uri.toString()
                    val tree = DocumentFile.fromTreeUri(context, permission.uri) ?: return@mapNotNull null
                    MediaBucket(
                        id = uriString,
                        displayName = tree.name ?: documentTreeDisplayName(uriString),
                        itemCount = tree.listFiles().count { !it.isDirectory },
                    )
                }
        }

    /**
     * Live trigger: emit whenever any [watched] tree's contents change. Each tree's
     * **children-documents URI** is observable — `DocumentsProvider`s (e.g.
     * `ExternalStorageProvider`) call `notifyChange` on it as files are added /
     * removed, and `notifyForDescendants = true` extends that to nested folders.
     * Coarse "something changed" is enough — the controller diffs each tree against
     * its cursor on the next drain. One shared observer is registered per tree and
     * all are dropped on close. Best-effort: a provider that never notifies simply
     * falls back to the start / settings / manual triggers (no regression vs before).
     */
    override fun observeChanges(watched: Set<String>): Flow<Unit> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        val resolver = context.contentResolver
        watched.forEach { uriString ->
            runCatching {
                val treeUri = uriString.toUri()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri),
                )
                resolver.registerContentObserver(childrenUri, true, observer)
            }
        }
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    override suspend fun listNewItems(
        bucketId: String,
        sinceCursor: String?,
        includeVideo: Boolean,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val since = sinceCursor?.toLongOrNull() ?: Long.MIN_VALUE
        val root = DocumentFile.fromTreeUri(context, bucketId.toUri())
            ?: return@withContext emptyList()
        val items = mutableListOf<MediaItem>()
        collectNewFiles(root, since, items)
        items.sortedBy { it.cursor.toLongOrNull() ?: 0L }
    }

    private fun collectNewFiles(dir: DocumentFile, since: Long, out: MutableList<MediaItem>) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                collectNewFiles(child, since, out)
            } else {
                val modified = child.lastModified()
                if (modified <= since) continue
                val name = child.name ?: continue
                out += MediaItem(
                    uri = child.uri.toString(),
                    fileName = name,
                    addedAtMillis = modified,
                    cursor = modified.toString(),
                    type = MediaType.IMAGE,
                )
            }
        }
    }
}
