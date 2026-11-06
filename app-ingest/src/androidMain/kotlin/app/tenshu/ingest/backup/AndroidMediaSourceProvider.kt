package app.tenshu.ingest.backup

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * Android [MediaSourceProvider] (`docs/proposals/drive-synced-folders.md` §4.1).
 * Buckets are `MediaStore` image (and, when opted in, video) buckets — `DCIM/Camera`,
 * `Screenshots`, `WhatsApp Images`, … — enumerable with just `READ_MEDIA_IMAGES`
 * (+ `READ_MEDIA_VIDEO`) and **no** SAF tree grant (§3.2), held by the **Drive**
 * process. Bucket id = `BUCKET_ID`; item cursor = `_ID` (monotonic, so a strict
 * `_ID > cursor` window is exactly "new since last drain", §3.5). Item uris are
 * `content://` strings the existing `uploadSourceFileBackground` path opens.
 *
 * Not locally compilable in the headless build env (no Android SDK); correctness
 * is by construction + Studio verification, like `AndroidFilesRepositoryPlatformDelegate`.
 */
class AndroidMediaSourceProvider(
    private val context: Context,
) : MediaSourceProvider {

    override suspend fun listBuckets(includeVideo: Boolean): List<MediaBucket> =
        withContext(Dispatchers.IO) {
            val byId = linkedMapOf<String, MutableBucket>()
            collections(includeVideo).forEach { collection ->
                queryBuckets(collection, byId)
            }
            byId.values.map { it.toBucket() }
        }

    override fun observeChanges(watched: Set<String>): Flow<Unit> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
        }
        // Observe the whole collection(s); the controller diffs the watched buckets
        // against their cursors, so a coarse "something changed" trigger is enough.
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    override suspend fun listNewItems(
        bucketId: String,
        sinceCursor: String?,
        includeVideo: Boolean,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val since = sinceCursor?.toLongOrNull() ?: -1L
        collections(includeVideo).flatMap { collection ->
            queryNewItems(collection, bucketId, since)
        }.sortedBy { it.cursor.toLongOrNull() ?: 0L }
    }

    private data class Collection(val uri: Uri, val type: MediaType)

    private fun collections(includeVideo: Boolean): List<Collection> = buildList {
        add(Collection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE))
        if (includeVideo) add(Collection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO))
    }

    private class MutableBucket(val id: String, val name: String) {
        var count = 0
        var sampleUri: String? = null
        fun toBucket() = MediaBucket(
            id = id,
            displayName = name,
            itemCount = count,
            sampleThumbnailUri = sampleUri,
        )
    }

    private fun queryBuckets(collection: Collection, into: MutableMap<String, MutableBucket>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        context.contentResolver.query(
            collection.uri, projection, null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdCol) ?: continue
                val name = cursor.getString(bucketNameCol) ?: bucketId
                val bucket = into.getOrPut(bucketId) { MutableBucket(bucketId, name) }
                bucket.count++
                if (bucket.sampleUri == null) {
                    bucket.sampleUri =
                        ContentUris.withAppendedId(collection.uri, cursor.getLong(idCol)).toString()
                }
            }
        }
    }

    private fun queryNewItems(
        collection: Collection,
        bucketId: String,
        since: Long,
    ): List<MediaItem> {
        // ⚠ SIZE rides the projection the drain already runs — same cursor, same query, no
        // extra resolver round-trip and no extra permission. It is what lets the banner say
        // how many bytes a backup has left instead of how big the file in flight is
        // (`drive-upload-progress.md` §14.4).
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
        )
        val selection =
            "${MediaStore.MediaColumns.BUCKET_ID} = ? AND ${MediaStore.MediaColumns._ID} > ?"
        val args = arrayOf(bucketId, since.toString())
        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection.uri, projection, selection, args,
            "${MediaStore.MediaColumns._ID} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items += MediaItem(
                    uri = ContentUris.withAppendedId(collection.uri, id).toString(),
                    fileName = cursor.getString(nameCol) ?: "$id",
                    addedAtMillis = cursor.getLong(dateCol) * 1000L,
                    cursor = id.toString(),
                    type = collection.type,
                    // ⚠ `isNull`, not `getLong`: a null SIZE reads back as 0, which is a
                    // perfectly good size for an empty file and a lie for a row MediaStore
                    // has not finished writing (`IS_PENDING`, a file placed outside the
                    // store and not yet scanned). Unknown must stay unknown — see
                    // [MediaItem.sizeBytes].
                    sizeBytes = if (cursor.isNull(sizeCol)) null else cursor.getLong(sizeCol),
                )
            }
        }
        return items
    }
}
