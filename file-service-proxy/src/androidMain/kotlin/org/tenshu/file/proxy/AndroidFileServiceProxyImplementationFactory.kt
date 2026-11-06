package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.NoOpPreviewCache
import org.tenshu.core.common.proxy.PreviewCache
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.common.proxy.file.AndroidFileReplaceImplementation
import org.tenshu.core.common.proxy.file.AndroidFileUploadImplementation
import org.tenshu.core.common.proxy.file.AndroidGetPreviewImplementation
import org.tenshu.core.common.proxy.file.AndroidMotionThumbnailRenderer
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.system.ReplaceSourceFile
import org.tenshu.core.file.system.ReplaceSourceFileBg
import org.tenshu.core.file.system.UploadSourceFile
import org.tenshu.core.file.system.UploadSourceFileBg
import org.tenshu.files.util.ParcelFileDescriptorCache
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.sync.common.SyncBlockEmitterHolder

fun androidFileServiceProxyImplementationFactory(
    id: String,
    fileDatabase: FileDatabase,
    fileDescriptorCache: ParcelFileDescriptorCache,
    syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    previewCache: PreviewCache = NoOpPreviewCache,
    deviceId: String? = null,
    storageCapacityHolder: StorageCapacityHolder? = null,
    /**
     * M4 (`photos-video-support.md` §4.4): the `MOTION`-class mint for the descriptor-backed
     * upload/replace ops. ⚠ Null by default like the recorder above, and with the same silent
     * failure — pass [androidMotionThumbnailRenderer] here **and** to the common factory the same
     * module falls through to (`MotionThumbnailRendererWiringTest`).
     */
    motionThumbnailRenderer: MotionThumbnailRenderer? = null,
): OperationImplementation? {
    return when (id) {
        UploadSourceFile.ID -> AndroidFileUploadImplementation(
            fileDatabase,
            fileDescriptorCache,
            syncBlockEmitterHolder,
            deviceId,
            storageCapacityHolder,
            motionThumbnailRenderer,
        )

        ReplaceSourceFile.ID -> AndroidFileReplaceImplementation(
            fileDatabase,
            fileDescriptorCache,
            syncBlockEmitterHolder,
            deviceId,
            storageCapacityHolder,
            motionThumbnailRenderer,
        )

        // Background (cancellable Layer-2) variants reuse the same impls —
        // docs/proposals/drive-folder-upload.md §3a.
        UploadSourceFileBg.ID -> AndroidFileUploadImplementation(
            fileDatabase,
            fileDescriptorCache,
            syncBlockEmitterHolder,
            deviceId,
            storageCapacityHolder,
            motionThumbnailRenderer,
        )

        ReplaceSourceFileBg.ID -> AndroidFileReplaceImplementation(
            fileDatabase,
            fileDescriptorCache,
            syncBlockEmitterHolder,
            deviceId,
            storageCapacityHolder,
            motionThumbnailRenderer,
        )

        GetPreview.ID -> AndroidGetPreviewImplementation(previewCache)
        else -> null
    }
}

/**
 * The Android `MOTION`-class mint (`MediaCodec` + `MediaMuxer`, framework only) for
 * [androidFileServiceProxyImplementationFactory] and `fileServiceProxyImplementationFactory`.
 * One instance serves both — it holds no state between renders.
 */
fun androidMotionThumbnailRenderer(): MotionThumbnailRenderer = AndroidMotionThumbnailRenderer()
