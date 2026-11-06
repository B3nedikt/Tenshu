@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.ingest.backup.MediaType
import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.ingest.backup.MediaBucket
import app.tenshu.ingest.backup.MediaItem
import app.tenshu.ingest.backup.MediaSourceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.NSPredicate
import platform.Foundation.NSSortDescriptor
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.numberWithInt
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeAny
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumFavorites
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumPanoramas
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumScreenshots
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumSelfPortraits
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumUserLibrary
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetCollectionTypeSmartAlbum
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHFetchOptions

/**
 * iOS PhotoKit [MediaSourceProvider] for the Synced-Folders reframe (ios-drive-parity.md P6b) —
 * the iOS analogue of `AndroidMediaSourceProvider`, plugged into the SHARED, source-generic
 * `SyncedFolderController` (commonMain) with no engine change. It answers the two questions the
 * backup loop asks about the photo library: "what buckets?" and "what's new since the cursor?".
 *
 * **Buckets:** the user's photo library ("All Photos", the `smartAlbumUserLibrary` smart album),
 * plus — only when All Photos is *not* selected — the user's own albums, so the "a new album
 * appeared" prompt has something to diff (see [userLibrarySelected] for why that condition is
 * load-bearing). The per-item **cursor is the asset's `creationDate` epoch-millis** — a
 * monotonic watermark the engine advances as each upload lands (§3.5); `listNewItems` fetches
 * strictly-newer assets oldest-first via a `creationDate > cursor` predicate.
 *
 * **`observeChanges` returns `emptyFlow()` in v1** — the live "new photo → auto-drain" trigger (a
 * `PHPhotoLibraryChangeObserver`) is deferred; the foreground "Back up now" catch-up and the P6a
 * background wake drive drains, which matches the honest "open the app to finish" iOS UX (iOS
 * grants no guaranteed background wake anyway). Enumeration requires photo-library authorization
 * (requested Swift-side); an unauthorized library simply fetches empty.
 */
class IosMediaSourceProvider(
    /**
     * Whether the user is backing up "All Photos".
     *
     * ⚠ **The one place iOS is not Android.** An Android bucket is a directory, so a
     * photo lives in exactly one; an iOS album is a *view*, so an asset in an album is
     * ALSO in the user library. Offering albums alongside a selected All Photos would
     * upload every album member twice — once to `/Backup/<device>/All Photos`, once to
     * `/Backup/<device>/<Album>` — and the §3.5 dedup backstop would not catch it,
     * because it dedups per destination path and these are different paths.
     *
     * So albums are enumerated only once All Photos is deselected, i.e. exactly when
     * they stop being redundant. Defaults to `true` (albums hidden) so any caller that
     * does not wire this up keeps today's single-bucket behaviour.
     */
    private val userLibrarySelected: suspend () -> Boolean = { true },
) : MediaSourceProvider {

    private companion object {
        /** Smart albums worth offering: real user intent, not just a media-type view.
         *  Deliberately NOT "Recents" (that is the user library) nor the media-type
         *  albums (Videos, Live Photos), which slice rather than curate. */
        val CURATED_SMART_ALBUMS = listOf(
            PHAssetCollectionSubtypeSmartAlbumScreenshots,
            PHAssetCollectionSubtypeSmartAlbumFavorites,
            PHAssetCollectionSubtypeSmartAlbumSelfPortraits,
            PHAssetCollectionSubtypeSmartAlbumPanoramas,
        )
    }

    /** The default bucket id — the user photo library smart album. */
    private val userLibraryBucketId = DriveBackupSurface.USER_LIBRARY_BUCKET_ID

    override suspend fun listBuckets(includeVideo: Boolean): List<MediaBucket> {
        val allPhotosSelected = userLibrarySelected()
        return withContext(Dispatchers.Default) {
            val library = userLibraryCollection() ?: return@withContext emptyList()
            val userLibrary = MediaBucket(
                id = userLibraryBucketId,
                displayName = "All Photos",
                itemCount = countIn(library, includeVideo),
            )
            // Albums are redundant while All Photos is selected — and worse than
            // redundant, they would double-upload. See [userLibrarySelected].
            if (allPhotosSelected) listOf(userLibrary) else listOf(userLibrary) + userAlbums(includeVideo)
        }
    }

    /**
     * The user's albums, **unconditionally** — the settings picker has to show them even
     * while All Photos is selected, which is exactly the case [listBuckets] hides them in.
     * That split is deliberate: the engine sees the guarded view (so it can never
     * double-upload), the picker sees the whole truth (so the user can switch).
     */
    suspend fun listAlbums(includeVideo: Boolean = false): List<MediaBucket> =
        withContext(Dispatchers.Default) { userAlbums(includeVideo) }

    /**
     * The albums offered as backup targets: the user's hand-made albums, plus a small
     * curated set of smart albums.
     *
     * ⚠ An earlier revision excluded smart albums outright, reasoning that they overlap
     * the user library. That reasoning was wrong: in album mode the library is
     * deselected, so *that* overlap is moot — and the overlap that does remain
     * (album-vs-album) applies just as much to hand-made albums, since a photo can sit
     * in two of those too. Excluding smart albums bought no safety and removed the
     * albums most people actually have: plenty of libraries contain zero user albums
     * but always contain Screenshots and Favourites.
     *
     * "Recents" is still excluded — it *is* the user library under another name, so
     * offering it would reintroduce the real overlap the mode split exists to prevent.
     */
    private fun userAlbums(includeVideo: Boolean): List<MediaBucket> {
        val albums = ArrayList<MediaBucket>()
        collect(PHAssetCollectionTypeAlbum, PHAssetCollectionSubtypeAny, includeVideo, albums)
        for (subtype in CURATED_SMART_ALBUMS) {
            collect(PHAssetCollectionTypeSmartAlbum, subtype, includeVideo, albums)
        }
        return albums
    }

    private fun collect(
        type: platform.Photos.PHAssetCollectionType,
        subtype: platform.Photos.PHAssetCollectionSubtype,
        includeVideo: Boolean,
        into: MutableList<MediaBucket>,
    ) {
        val result = PHAssetCollection.fetchAssetCollectionsWithType(type, subtype, options = null)
        for (index in 0 until result.count.toInt()) {
            val collection = result.objectAtIndex(index.toULong()) as? PHAssetCollection ?: continue
            val count = countIn(collection, includeVideo)
            // Empty albums are dropped: nothing to back up, and they would only pad the
            // picker. They reappear as soon as they hold something.
            if (count == 0) continue
            into += MediaBucket(
                id = collection.localIdentifier,
                displayName = collection.localizedTitle ?: collection.localIdentifier,
                itemCount = count,
            )
        }
    }

    private fun countIn(collection: PHAssetCollection, includeVideo: Boolean): Int =
        PHAsset.fetchAssetsInAssetCollection(collection, options = fetchOptions(null, includeVideo))
            .count.toInt()

    override fun observeChanges(watched: Set<String>): Flow<Unit> = emptyFlow()

    override suspend fun listNewItems(
        bucketId: String,
        sinceCursor: String?,
        includeVideo: Boolean,
    ): List<MediaItem> = withContext(Dispatchers.Default) {
        val collection = collectionFor(bucketId) ?: return@withContext emptyList()
        val result = PHAsset.fetchAssetsInAssetCollection(
            collection,
            options = fetchOptions(sinceCursor, includeVideo),
        )
        val items = ArrayList<MediaItem>(result.count.toInt())
        for (index in 0 until result.count.toInt()) {
            val asset = result.objectAtIndex(index.toULong()) as? PHAsset ?: continue
            val createdMillis = (asset.creationDate?.timeIntervalSince1970 ?: 0.0).times(1000.0).toLong()
            items += MediaItem(
                uri = IosPhotoLibrary.uriFor(asset.localIdentifier),
                fileName = IosPhotoLibrary.originalFileName(asset),
                addedAtMillis = createdMillis,
                cursor = createdMillis.toString(),
                type = if (asset.mediaType == PHAssetMediaTypeVideo) MediaType.VIDEO else MediaType.IMAGE,
            )
        }
        items
    }

    /** Resolve a bucket id back to its collection: the user-library sentinel, else a
     *  PhotoKit `localIdentifier` for one of the user's albums. */
    private fun collectionFor(bucketId: String): PHAssetCollection? =
        if (bucketId == userLibraryBucketId) {
            userLibraryCollection()
        } else {
            PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(
                listOf(bucketId),
                options = null,
            ).firstObject() as? PHAssetCollection
        }

    private fun userLibraryCollection(): PHAssetCollection? =
        PHAssetCollection.fetchAssetCollectionsWithType(
            PHAssetCollectionTypeSmartAlbum,
            PHAssetCollectionSubtypeSmartAlbumUserLibrary,
            options = null,
        ).firstObject() as? PHAssetCollection

    /** A fetch bound by "strictly newer than [sinceCursor]" + the image/video media-type filter,
     *  sorted oldest-first so the cursor advances monotonically. */
    private fun fetchOptions(sinceCursor: String?, includeVideo: Boolean): PHFetchOptions =
        PHFetchOptions().apply {
            sortDescriptors = listOf(
                NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = true),
            )
            predicate = buildPredicate(sinceCursor, includeVideo)
        }

    private fun buildPredicate(sinceCursor: String?, includeVideo: Boolean): NSPredicate {
        val mediaClause = if (includeVideo) {
            "(mediaType == %@ OR mediaType == %@)"
        } else {
            "mediaType == %@"
        }
        val mediaArgs: List<Any> = if (includeVideo) {
            listOf(NSNumber.numberWithInt(PHAssetMediaTypeImage.toInt()), NSNumber.numberWithInt(PHAssetMediaTypeVideo.toInt()))
        } else {
            listOf(NSNumber.numberWithInt(PHAssetMediaTypeImage.toInt()))
        }
        return if (sinceCursor != null) {
            val sinceDate = NSDate.dateWithTimeIntervalSince1970(sinceCursor.toLong() / 1000.0)
            NSPredicate.predicateWithFormat(
                "creationDate > %@ AND $mediaClause",
                argumentArray = listOf(sinceDate) + mediaArgs,
            )
        } else {
            NSPredicate.predicateWithFormat(mediaClause, argumentArray = mediaArgs)
        }
    }
}
