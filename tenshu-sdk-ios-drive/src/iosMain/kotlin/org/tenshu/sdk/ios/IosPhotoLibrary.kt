@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableData
import platform.Foundation.appendData
import platform.Photos.PHAsset
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHFetchOptions
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * Thin PhotoKit access for the iOS Synced-Folders reframe (ios-drive-parity.md P6b).
 *
 * Central home for the two PhotoKit reads the backup path needs: resolving a
 * `phasset://<localIdentifier>` back to its `PHAsset` (used by [IosMediaSourceProvider] to
 * enumerate and by [IosFilesRepositoryPlatformDelegate] to read bytes), and streaming an asset's
 * **original** resource bytes to a `ByteArray`. Enumeration + authorization requesting live in the
 * provider / Swift; this is the shared plumbing.
 *
 * The uploaded bytes ride the ordinary route-1 upload op — which, since P3b, stages + enqueues the
 * sealed chunks on the background `URLSession`. So a photo backed up here survives suspension the
 * same way a picker upload does.
 */
internal object IosPhotoLibrary {

    const val SCHEME = "phasset://"

    /** `phasset://<localIdentifier>` for a [PHAsset]. */
    fun uriFor(localIdentifier: String): String = SCHEME + localIdentifier

    /** The `localIdentifier` inside a `phasset://…` URI, or null if it isn't one. */
    fun localIdentifierOf(uri: String): String? =
        if (uri.startsWith(SCHEME)) uri.removePrefix(SCHEME).ifEmpty { null } else null

    /** Resolve a `phasset://` URI (or bare local id) to its [PHAsset], or null if gone. */
    fun assetFor(uri: String): PHAsset? {
        val localId = localIdentifierOf(uri) ?: uri
        val result = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localId), options = null)
        return result.firstObject() as? PHAsset
    }

    /** The asset's original filename (e.g. `IMG_0421.HEIC`), or a fallback derived from its id. */
    fun originalFileName(asset: PHAsset): String {
        val resource = PHAssetResource.assetResourcesForAsset(asset).firstOrNull() as? PHAssetResource
        return resource?.originalFilename
            ?: (asset.localIdentifier.substringBefore('/').ifEmpty { "photo" })
    }

    /**
     * Read the asset's **original** resource bytes (full quality, incl. video) into a single
     * `ByteArray` via [PHAssetResourceManager.requestDataForResource]. Suspends until the
     * completion handler fires; null on any PhotoKit error or if the asset has no resource.
     *
     * v1 accumulates the whole file in memory (like the D2a picker read) — the P3b background
     * session then chunks + streams the sealed bytes. A future refinement could write to a temp
     * file and hand the URL to the background session directly.
     */
    suspend fun readOriginalBytes(asset: PHAsset): ByteArray? {
        val resource = PHAssetResource.assetResourcesForAsset(asset).firstOrNull() as? PHAssetResource
            ?: return null
        val options = PHAssetResourceRequestOptions().apply {
            setNetworkAccessAllowed(true) // fetch from iCloud if the original isn't local
        }
        val buffer = NSMutableData()
        return suspendCancellableCoroutine { continuation ->
            PHAssetResourceManager.defaultManager().requestDataForAssetResource(
                resource = resource,
                options = options,
                dataReceivedHandler = { data: NSData? -> if (data != null) buffer.appendData(data) },
                completionHandler = { error: NSError? ->
                    if (error != null) {
                        continuation.resume(null)
                    } else {
                        continuation.resume(buffer.toByteArray())
                    }
                },
            )
        }
    }
}

/** Filter for images-only vs images+video fetches (mirrors the provider's `includeVideo`). */
internal fun imageOnlyFetchOptions(includeVideo: Boolean): PHFetchOptions = PHFetchOptions()

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, size.convert()) }
    return out
}
