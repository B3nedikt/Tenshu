package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.NoOpPreviewCache
import org.tenshu.core.common.proxy.PreviewCache
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.file.GetPreview
import org.tenshu.file.proxy.jvm.JvmGetPreviewImplementation
import org.tenshu.file.proxy.jvm.JvmThumbnailLadderRenderer

fun jvmFileServiceProxyImplementationFactory(
    id: String,
    @Suppress("UNUSED_PARAMETER") fileDatabase: FileDatabase,
    previewCache: PreviewCache = NoOpPreviewCache
): OperationImplementation? {
    return when (id) {
        GetPreview.ID -> JvmGetPreviewImplementation(previewCache)
        else -> null
    }
}

/**
 * P1a (`media-processing-pipeline-sweep.md` §4.1): the JVM's one-decode still-ladder renderer, for
 * `fileServiceProxyImplementationFactory(thumbnailLadderRenderer = …)`. ⚠ Without it every desktop
 * upload still mints every rung, each from its own full decode — `ThumbnailLadderRendererWiringTest`.
 */
fun jvmThumbnailLadderRenderer(): ThumbnailLadderRenderer = JvmThumbnailLadderRenderer()
