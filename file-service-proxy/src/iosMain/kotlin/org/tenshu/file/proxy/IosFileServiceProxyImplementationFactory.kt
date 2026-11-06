package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.NoOpPreviewCache
import org.tenshu.core.common.proxy.PreviewCache
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.system.ReplaceSourceFile
import org.tenshu.core.file.system.ReplaceSourceFileBg
import org.tenshu.core.file.system.UploadSourceFile
import org.tenshu.core.file.system.UploadSourceFileBg
import org.tenshu.file.proxy.ios.IosGetPreviewImplementation
import org.tenshu.file.proxy.ios.IosReplaceSourceFileImplementation
import org.tenshu.file.proxy.ios.IosUploadSourceFileImplementation
import org.tenshu.file.proxy.ios.IosUploadSourceReader
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.sync.common.SyncBlockEmitterHolder

/**
 * iOS platform overlay for the file-service ops that need a native backend — the K/N twin of
 * [jvmFileServiceProxyImplementationFactory] / `androidFileServiceProxyImplementationFactory`.
 * Consulted **before** the common [fileServiceProxyImplementationFactory] in the iOS
 * `platformImplementationFactory` (control-shared-proxy iosMain), so `GetPreview` resolves to the
 * UIKit/PDFKit renderer and the by-reference upload ops resolve to the readers below; everything
 * else falls through to the common factory unchanged.
 *
 * Closes P4e (docs/proposals/ios-drive-parity.md §P4) and Ph1b of
 * `docs/proposals/drive-upload-source-read.md`.
 */
fun iosFileServiceProxyImplementationFactory(
    id: String,
    fileDatabase: FileDatabase,
    previewCache: PreviewCache = NoOpPreviewCache,
    uploadSourceReader: IosUploadSourceReader? = null,
    syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    deviceId: String? = null,
    storageCapacityHolder: StorageCapacityHolder? = null,
): OperationImplementation? {
    // ⚠⚠ Loud on a wiring mistake, never a fallthrough. Returning null here would let the id reach
    // the common factory, which has no implementation for it, and the user would see a generic
    // "no implementation" at upload time — a symptom three layers from its cause. The reader is
    // supplied by the SDK layer (it owns PhotoKit); its absence is a DI bug, not a runtime state.
    fun reader(): IosUploadSourceReader = uploadSourceReader
        ?: error(
            "No IosUploadSourceReader wired — $id cannot resolve its source. The iOS runtime must " +
                "register one (see IosUploadSourceReader)."
        )

    return when (id) {
        GetPreview.ID -> IosGetPreviewImplementation(previewCache)

        UploadSourceFile.ID, UploadSourceFileBg.ID -> IosUploadSourceFileImplementation(
            fileDatabase, reader(), syncBlockEmitterHolder, deviceId, storageCapacityHolder,
        )

        ReplaceSourceFile.ID, ReplaceSourceFileBg.ID -> IosReplaceSourceFileImplementation(
            fileDatabase, reader(), syncBlockEmitterHolder, deviceId, storageCapacityHolder,
        )

        else -> null
    }
}
