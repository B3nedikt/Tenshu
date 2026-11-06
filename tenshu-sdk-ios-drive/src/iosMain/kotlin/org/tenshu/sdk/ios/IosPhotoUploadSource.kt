package org.tenshu.sdk.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.tenshu.file.proxy.ios.IosUploadSourceReader

/**
 * Drive's [IosUploadSourceReader]: a `phasset://<localIdentifier>` source reads the PhotoKit
 * asset's original bytes; anything else is [IosFileUploadSource]'s.
 *
 * ⚠⚠ **Why this is Drive's and not the base binding** (`ios-per-app-frameworks.md` D6): the base
 * runtime is in every app's framework, so a PhotoKit call there put `Photos.framework` into every
 * app's binary. Drive is the only app that hands an upload a `phasset://` source (its picker and
 * its backup scope), so Drive alone binds this, through `DriveLocalRuntime`'s `appOverrides`.
 *
 * ⚠ Throws rather than returning empty on every failure path, as the base reader does.
 */
internal object IosPhotoUploadSource : IosUploadSourceReader {

    override suspend fun readBytes(uriString: String): ByteArray {
        if (IosPhotoLibrary.localIdentifierOf(uriString) == null) {
            return IosFileUploadSource.readBytes(uriString)
        }
        return withContext(Dispatchers.IO) {
            val asset = IosPhotoLibrary.assetFor(uriString)
                ?: error("PhotoKit asset not found: $uriString")
            IosPhotoLibrary.readOriginalBytes(asset)
                ?: error("PhotoKit read failed: $uriString")
        }
    }
}
